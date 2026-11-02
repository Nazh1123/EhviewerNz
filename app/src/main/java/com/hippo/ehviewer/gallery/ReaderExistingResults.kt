package com.hippo.ehviewer.gallery

import android.content.Context
import androidx.preference.PreferenceManager
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.superresolution.EnhancedTilePage
import com.hippo.ehviewer.translation.NativeMemoryPolicy
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.*

/** Bounded directional prefetch; a missing gallery manifest short-circuits all page work. */
class ReaderExistingResults(context: Context, private val provider: GalleryProvider2,
                            private val info: GalleryInfo?) : AutoCloseable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    val resultIndex = ReaderResultIndex(context, provider, info)
    private var maintenance: Job? = null
    private val loads = mutableMapOf<Int, Job>()
    // A successful lookup may outlive its decoded pixels. Zero flags memoize a missing result.
    private val loaded = mutableMapOf<Int, Int>()
    private val sources = mutableMapOf<Int, GalleryResultIndex.Source>()
    @Volatile private var page = -1
    private var direction = 1
    private var generation = 0
    private var noResults = false
    private var active = true
    private val preferences = PreferenceManager.getDefaultSharedPreferences(app)
    internal var decodeTranslation: (UniFile) -> Image? = { file ->
        file.openInputStream().use { input ->
            android.graphics.BitmapFactory.decodeStream(input)?.let { bitmap ->
                Image.decode(android.graphics.drawable.BitmapDrawable(app.resources, bitmap), false)
            }
        }
    }
    private val changed: (GalleryResultIndex.Companion.Change) -> Unit = { event ->
        if (event.identity == GalleryResultIndex.identity(provider, info)) {
            noResults = false
            for ((position, source) in sources.toMap()) if (source.name == event.source) {
                loads.remove(position)?.cancel()
                loaded.remove(position)
                provider.clearExistingResult(position, event.kind == GalleryResultIndex.Kind.TL,
                    event.kind == GalleryResultIndex.Kind.SR)
            }
            refresh()
        }
    }
    init { provider.setExistingResultLoader { refresh() }; GalleryResultIndex.listeners.add(changed) }
    fun onPageChanged(index: Int) {
        if (page != index && page >= 0) direction = if (index > page) 1 else -1
        page = index
        provider.retainExistingResults(index)
        loaded.keys.removeAll { kotlin.math.abs(it - index) > 1 }
        sources.keys.removeAll { kotlin.math.abs(it - index) > 1 }
        refresh()
    }
    // Original readiness must not discard decoded overlays or cancel an in-flight restore.
    fun onPageReady(index: Int) { if (index == page && !isLoaded(index) && loads[index]?.isActive != true) refresh() }
    fun resume() { active = true; reset(); resultIndex.reload(); refresh() }
    fun pause() { active = false; reset() }
    private fun reset() {
        generation++
        job?.cancel(); maintenance?.cancel()
        loads.values.forEach { it.cancel() }; loads.clear()
        loaded.clear(); sources.clear()
        maintenance = null; noResults = false
    }

    private fun refresh() {
        val enabled = preferences.getBoolean(GalleryResultIndex.PREFER_EXISTING, false)
        provider.setPreferExistingResults(enabled)
        if (!enabled) {
            reset()
            provider.clearExistingResults()
            return
        }
        if (!active || noResults || page !in 0 until provider.size()) return
        val current = page
        val next = current + direction
        for (position in loads.keys.toList()) if (position != current && position != next) loads.remove(position)?.cancel()
        job?.cancel()
        val epoch = generation
        job = scope.launch {
            try {
                val hasResults = resultIndex.hasResults()
                if (epoch != generation || current != page) return@launch
                if (!hasResults) {
                    noResults = true
                    provider.clearExistingResults()
                    return@launch
                }
                load(current)?.join()
                if (current != page) return@launch
                if (next in 0 until provider.size()) load(next)?.join()
                // Full local reconciliation follows visible-page publication, never blocks it.
                if (maintenance == null) maintenance = scope.launch {
                    try {
                        val index = resultIndex.index()
                        withContext(Dispatchers.IO) { index.reconcile(provider) }
                    }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { android.util.Log.w("ExistingResults", "Cannot reconcile results", error) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { android.util.Log.w("ExistingResults", "Result index unavailable", error) }
        }
    }

    private fun hasResults(position: Int, flags: Int): Boolean =
        (flags and ReaderResultIndex.TRANSLATION == 0 || provider.hasTranslatedPage(position)) &&
            (flags and ReaderResultIndex.SUPER_RESOLUTION == 0 || provider.hasEnhancedPage(position))

    private fun isLoaded(position: Int): Boolean = loaded[position]?.let { hasResults(position, it) } == true

    private fun load(position: Int): Job? {
        if (isLoaded(position)) return null
        loads[position]?.takeIf { it.isActive }?.let { return it }
        val epoch = generation
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                val saved = resultIndex.page(position)
                saved.source?.let { sources[position] = it }
                supervisorScope {
                    launch {
                        var tiles: EnhancedTilePage? = null
                        try {
                            withContext(Dispatchers.IO) {
                                saved.enhanced?.takeIf {
                                    it.state == "complete" && it.scale in listOf(2, 4, 8) && !provider.hasEnhancedPage(position)
                                }?.let { hit ->
                                    EnhancedTilePage(File(app.cacheDir, "result-reader"), hit.width / hit.scale,
                                        hit.height / hit.scale, hit.scale).apply {
                                        tiles = this // Cleanup also owns a decode cancelled before Main delivery.
                                        useFinalImage(checkNotNull(hit.file))
                                        // Prepare a neighbor before exposing it to UI/GL threads, avoiding trim/decode contention.
                                        if (position != page && NativeMemoryPolicy(app).canRetain()) {
                                            val display = app.resources.displayMetrics
                                            try { warmFirstRegion(display.widthPixels, display.heightPixels) }
                                            catch (_: Exception) { trim(); activate() }
                                            catch (_: OutOfMemoryError) { trim(); activate() }
                                        }
                                    }
                                }
                            }
                            ensureActive()
                            if (epoch == generation && kotlin.math.abs(position - page) <= 1 && !provider.hasEnhancedPage(position)) {
                                tiles?.let { provider.setExistingEnhancedTiles(position, it); tiles = null }
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { android.util.Log.w("ExistingResults", "Enhanced result unavailable", error) }
                        finally { tiles?.trim() }
                    }
                    launch {
                        var translated: Image? = null
                        try {
                            withContext(Dispatchers.IO) {
                                val hit = saved.translation?.takeIf { it.state == "complete" }
                                // A large speculative decode must not evict the visible overlay.
                                if (!provider.hasTranslatedPage(position) && hit != null &&
                                    (position == page || provider.canPrefetchTranslationResult(page,
                                        hit.width.toLong() * hit.height * 4))) translated = hit.file?.let(decodeTranslation)
                            }
                            ensureActive()
                            if (epoch == generation && kotlin.math.abs(position - page) <= 1 && !provider.hasTranslatedPage(position)) {
                                translated?.let { provider.setExistingTranslation(position, it); translated = null }
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { android.util.Log.w("ExistingResults", "Translated result unavailable", error) }
                        catch (error: OutOfMemoryError) { android.util.Log.w("ExistingResults", "Not enough memory for saved translation", error) }
                        finally { translated?.recycle() }
                    }
                }
                if (epoch == generation && hasResults(position, saved.flags)) loaded[position] = saved.flags
            } finally {
                if (loads[position] === currentCoroutineContext()[Job]) loads.remove(position)
            }
        }.also { loads[position] = it; it.start() }
    }
    override fun close() {
        GalleryResultIndex.listeners.remove(changed)
        provider.setExistingResultLoader(null)
        resultIndex.close()
        scope.cancel()
    }
}
