package com.hippo.ehviewer.gallery

import android.content.Context
import androidx.preference.PreferenceManager
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.superresolution.EnhancedTilePage
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.lib.image.Image
import java.io.File
import kotlinx.coroutines.*

/** Loads only indexed visible-page results. Never starts a processing session or reads source pixels. */
class ReaderExistingResults(context: Context, private val provider: GalleryProvider2,
                            private val info: GalleryInfo?) : AutoCloseable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var page = -1
    private var generation = 0
    private var scanned = false
    private var reloadManifest = true
    private var noResults = false
    private var active = true
    private val preferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val changed: (String) -> Unit = { identity ->
        if (identity == GalleryResultIndex.identity(provider, info)) {
            noResults = false
            provider.clearExistingResults()
            refresh()
        }
    }
    init { provider.setExistingResultLoader { refresh() }; GalleryResultIndex.listeners.add(changed) }
    fun onPageChanged(index: Int) { page = index; refresh() }
    fun onPageReady(index: Int) { if (index == page) { provider.clearExistingResults(); refresh() } }
    fun resume() { active = true; scanned = false; reloadManifest = true; noResults = false; refresh() }
    fun pause() { active = false; generation++; job?.cancel() }
    private fun refresh() {
        val enabled = preferences.getBoolean(GalleryResultIndex.PREFER_EXISTING, false)
        provider.setPreferExistingResults(enabled)
        if (!enabled) provider.clearExistingResults()
        val current = page
        generation++; val epoch = generation
        job?.cancel()
        if (!active || !enabled || current !in 0 until provider.size()) return
        // Remember a gallery-wide miss until publication or resume; page changes need no IO.
        if (noResults) return
        job = scope.launch {
            var translated: Image? = null
            try {
                val pair = withContext(Dispatchers.IO) {
                    if (reloadManifest) { GalleryResultIndex.dropMemory(); reloadManifest = false }
                    val download = info?.let { EhApplication.getDownloadManager(app).getDownloadInfo(it.gid) }
                    val directory = TranslationStorage.resultDirectory(app, download, provider, false)
                    val index = GalleryResultIndex.open(app, GalleryResultIndex.identity(provider, info), info?.gid, directory, false)
                    // Probe the two manifests before requesting any source metadata or listing pages.
                    if (!index.hasEntries()) return@withContext null
                    if (!scanned) {
                        index.reconcile(provider)
                        scanned = true
                    }
                    val source = GalleryResultIndex.source(provider, current, info?.gid)
                    val tl = if (!provider.hasTranslatedPage(current)) index.find(source, GalleryResultIndex.Kind.TL) else null
                    translated = tl?.takeIf { it.state == "complete" }?.file?.openInputStream()?.use {
                        android.graphics.BitmapFactory.decodeStream(it)?.let { bitmap ->
                            Image.decode(android.graphics.drawable.BitmapDrawable(app.resources, bitmap), false)
                        }
                    }
                    val sr = if (!provider.hasEnhancedPage(current)) index.find(source, GalleryResultIndex.Kind.SR) else null
                    val tiles = sr?.takeIf { it.state == "complete" && it.scale in listOf(2, 4, 8) }?.let {
                        EnhancedTilePage(File(app.cacheDir, "result-reader").apply { mkdirs() }, it.width / it.scale,
                            it.height / it.scale, it.scale).apply { useFinalImage(checkNotNull(it.file)) }
                    }
                    translated to tiles
                }
                ensureActive()
                if (epoch != generation || page != current) return@launch
                if (pair == null) { noResults = true; return@launch }
                pair.first?.let {
                    if (!provider.hasTranslatedPage(current)) { provider.setExistingTranslation(current, it); translated = null }
                }
                pair.second?.let { if (!provider.hasEnhancedPage(current)) provider.setExistingEnhancedTiles(current, it) else it.trim() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { android.util.Log.w("ExistingResults", "Result unavailable", error) }
            finally { translated?.recycle() }
        }
    }
    override fun close() { GalleryResultIndex.listeners.remove(changed); provider.setExistingResultLoader(null); scope.cancel() }
}
