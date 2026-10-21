package com.hippo.ehviewer.gallery

import android.content.Context
import androidx.preference.PreferenceManager
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.superresolution.EnhancedTilePage
import com.hippo.ehviewer.translation.TranslationStorage
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
    private var indexTask: Deferred<GalleryResultIndex?>? = null
    private var maintenance: Job? = null
    private val loads = mutableMapOf<Int, Job>()
    private val loaded = mutableSetOf<Int>()
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
            if (indexTask?.isCompleted == true && indexTask?.isCancelled == false && sources.isEmpty()) indexTask = null
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
        loaded.removeAll { kotlin.math.abs(it - index) > 1 }
        sources.keys.removeAll { kotlin.math.abs(it - index) > 1 }
        refresh()
    }
    // Original readiness must not discard decoded overlays or cancel an in-flight restore.
    fun onPageReady(index: Int) { if (index == page && index !in loaded && loads[index]?.isActive != true) refresh() }
    fun resume() { active = true; reset(); refresh() }
    fun pause() { active = false; reset() }
    private fun reset() {
        generation++
        job?.cancel(); indexTask?.cancel(); maintenance?.cancel()
        loads.values.forEach { it.cancel() }; loads.clear()
        loaded.clear(); sources.clear()
        indexTask = null; maintenance = null; noResults = false
    }
    private fun index(): Deferred<GalleryResultIndex?> = indexTask ?: scope.async(Dispatchers.IO) {
        GalleryResultIndex.dropMemory()
        val download = info?.let { EhApplication.getDownloadManager(app).getDownloadInfo(it.gid) }
        val directory = TranslationStorage.resultDirectory(app, download, provider, false)
        GalleryResultIndex.open(app, GalleryResultIndex.identity(provider, info), info?.gid, directory, false)
            .takeIf { it.hasEntries() }
    }.also { indexTask = it }

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
                val index = index().await()
                if (epoch != generation || current != page) return@launch
                if (index == null || !withContext(Dispatchers.IO) { index.hasEntries() }) {
                    noResults = true
                    indexTask = null
                    provider.clearExistingResults()
                    return@launch
                }
                load(index, current)?.join()
                if (current != page) return@launch
                if (next in 0 until provider.size()) load(index, next)?.join()
                // Full local reconciliation follows visible-page publication, never blocks it.
                if (maintenance == null) maintenance = scope.launch(Dispatchers.IO) {
                    try { index.reconcile(provider) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { android.util.Log.w("ExistingResults", "Cannot reconcile results", error) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { android.util.Log.w("ExistingResults", "Result index unavailable", error) }
        }
    }

    private fun load(index: GalleryResultIndex, position: Int): Job? {
        if (position in loaded) return null
        loads[position]?.takeIf { it.isActive }?.let { return it }
        val epoch = generation
        return scope.launch(start = CoroutineStart.LAZY) {
            var complete = true
            try {
                val source = sources[position] ?: withContext(Dispatchers.IO) {
                    GalleryResultIndex.source(provider, position, info?.gid)
                }.also { sources[position] = it }
                supervisorScope {
                    launch {
                        var tiles: EnhancedTilePage? = null
                        try {
                            withContext(Dispatchers.IO) {
                                index.find(source, GalleryResultIndex.Kind.SR)?.takeIf {
                                    it.state == "complete" && it.scale in listOf(2, 4, 8)
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
                                val hit = index.find(source, GalleryResultIndex.Kind.TL)?.takeIf { it.state == "complete" }
                                // A large speculative decode must not evict the visible overlay.
                                if (position != page && hit != null && !provider.canPrefetchTranslationResult(page,
                                        hit.width.toLong() * hit.height * 4)) complete = false
                                else if (!provider.hasTranslatedPage(position)) translated = hit?.file?.let(decodeTranslation)
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
                if (epoch == generation && complete) loaded += position
            } finally {
                if (loads[position] === currentCoroutineContext()[Job]) loads.remove(position)
            }
        }.also { loads[position] = it; it.start() }
    }
    override fun close() {
        GalleryResultIndex.listeners.remove(changed)
        provider.setExistingResultLoader(null)
        scope.cancel()
    }
}
