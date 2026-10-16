package com.hippo.ehviewer.superresolution

import android.content.Context
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.translation.ImageProcessingPriority
import com.hippo.ehviewer.translation.GalleryPageWork
import com.hippo.ehviewer.translation.GalleryPageWorkRegistry
import com.hippo.ehviewer.translation.NativeMemoryPolicy
import com.hippo.ehviewer.translation.TranslationRuntime
import com.hippo.ehviewer.translation.TranslationWindow
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.ehviewer.translation.engine.SuperResolution
import com.hippo.ehviewer.translation.engine.SuperResolutionBackend
import com.hippo.ehviewer.translation.engine.SuperResolutionPipeline
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Application-owned queue. Only the visible page is decoded into the reader's texture cache. */
internal class GallerySuperResolutionSession(
    private val context: Context,
    readerSource: GalleryProvider2,
    val identity: String,
    val galleryInfo: GalleryInfo?,
) {
    interface Reader {
        fun changed()
        fun display(page: Int, image: Image)
        fun hasPage(page: Int): Boolean
        fun clear()
        fun displayTiles(page: Int, tiles: EnhancedTilePage) = Unit
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SuperResolutionSettings(context)
    private val models = SuperResolutionModels(context)
    private val sourceTemplate = readerSource.createTranslationProvider(context) ?: readerSource
    private val ownsSource = sourceTemplate !== readerSource
    private val engines = SuperResolutionSessionCache<SuperResolution>()
    var reader: Reader? = null
        private set
    private var worker: Job? = null
    private var loader: Job? = null
    private var browsing = true
    @Volatile private var generation = 0
    private var closed = false
    private var options = settings.read()
    private var singlePage: Int? = null
    private val results = mutableMapOf<Int, EnhancedTilePage>()
    internal val metrics = mutableMapOf<Int, SuperResolutionPageMetrics>()
    val states = mutableMapOf<Int, Int>()
    var current = -1
        private set
    var size = readerSource.size()
        private set
    var enabled = settings.automatic
        private set
    var fullGallery = false
        private set
    var working = false
        private set
    var activePage: Int? = null
        private set
    var progress = 0
        private set
    var message: String? = null
        private set
    val title get() = galleryInfo?.title ?: context.getString(R.string.sr_title)
    fun pages(): List<Int> = when {
        fullGallery -> TranslationWindow.fullPages(current, size)
        singlePage != null -> listOfNotNull(singlePage).filter { it in 0 until size }
        else -> TranslationWindow.pages(current, size, options.ahead)
    }
    val completed get() = pages().count { it in states }
    val failed get() = pages().count { states[it] == R.string.sr_failed_short }
    val total get() = pages().size
    private val pageWork = GalleryPageWorkRegistry.acquire(identity)
    private val pageQueue = GalleryPageWork.Queue(
        active = { !closed && enabled && (browsing || fullGallery) },
        pages = { pages() }, settled = { it in states },
        ready = { size > 0 },
    ).also { pageWork.register(GalleryPageWork.Stage.SUPER_RESOLUTION, it) }

    fun attach(observer: Reader) { reader = observer; browsing = true; restore(); changed(); start() }
    fun detach(observer: Reader, leaving: Boolean) {
        if (reader !== observer) return
        reader = null
        browsing = false
        loader?.cancel()
        if (leaving && !fullGallery) disable()
        else if (!fullGallery) worker?.cancel()
        if (worker == null && !fullGallery) releaseModels()
        changed()
    }

    fun pageChanged(page: Int) {
        if (closed || page < 0) return
        current = page
        if (!fullGallery && activePage?.let { it !in pages() } == true) worker?.cancel()
        restore()
        changed()
        start()
    }

    fun resume() {
        browsing = true
        val updated = settings.read()
        if (updated != options) {
            generation++
            worker?.cancel()
            loader?.cancel()
            options = updated
            states.clear(); results.clear(); reader?.clear()
        }
        if (singlePage == null && !fullGallery) enabled = settings.automatic
        restore(); changed(); start()
    }

    fun pause(leaving: Boolean) {
        browsing = false
        loader?.cancel()
        if (leaving && !fullGallery) disable()
        else if (!fullGallery) worker?.cancel()
        if (worker == null && !fullGallery) releaseModels()
        changed()
    }

    fun enable(): Boolean {
        enabled = true; singlePage = null; message = null
        restore(); start(); changed()
        return true
    }

    fun disable() {
        enabled = false; fullGallery = false; generation++
        message = null
        worker?.cancel(); loader?.cancel()
        reader?.clear(); changed()
        releaseModels()
    }

    fun full() {
        if (enable()) { fullGallery = true; start(); changed() }
    }

    fun retry(page: Int) {
        generation++; worker?.cancel(); loader?.cancel()
        states.remove(page); results.remove(page)
        enabled = true
        singlePage = if (settings.automatic || fullGallery) null else page
        message = null
        forced.add(page)
        reader?.clear(); start(); changed()
    }
    private val forced = mutableSetOf<Int>()

    fun invalidate(page: Int) {
        generation++; worker?.cancel(); loader?.cancel()
        states.remove(page); results.remove(page); forced.add(page)
        reader?.clear(); start(); changed()
    }

    private fun changed() { pageWork.changed(); reader?.changed(); SuperResolutionTasks.changed() }
    private fun releaseModels() {
        scope.launch(Dispatchers.IO) { SuperResolutionModels.lock.withLock { engines.clear() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start() {
        if (closed || !enabled || worker != null || (!browsing && !fullGallery)) return
        if (size > 0 && pages().all { it in states }) { restore(); return }
        working = true
        try { SuperResolutionTasks.wake(context) }
        catch (error: RuntimeException) {
            working = false; enabled = false
            message = context.getString(R.string.sr_failed, error.message.orEmpty()); changed(); return
        }
        val epoch = generation
        worker = scope.launch(start = CoroutineStart.ATOMIC) {
            // Every worker owns a new source-only provider; never stop a reader's provider.
            var source: GalleryProvider2? = null
            try {
                yield() // Publish the worker before any synchronous source failure/cleanup.
                source = if (ownsSource) sourceTemplate.createTranslationProvider(context) else null
                if (source == null && fullGallery) error(context.getString(R.string.sr_source_unavailable))
                val provider = source ?: sourceTemplate
                source?.start()
                var attempts = 0
                while (provider.size() == GalleryProvider2.STATE_WAIT && attempts++ < 30) delay(200)
                check(provider.size() > 0) { context.getString(R.string.sr_source_unavailable) }
                size = provider.size()
                changed()
                delay(250)
                while (enabled && generation == epoch && (browsing || fullGallery)) {
                    message = context.getString(R.string.sr_wait_translation)
                    changed()
                    val page = pageWork.await(GalleryPageWork.Stage.SUPER_RESOLUTION) ?: break
                    activePage = page; progress = 0; message = context.getString(R.string.sr_preparing); changed()
                    try {
                        val (tiles, stats) = withContext(Dispatchers.IO) { process(provider, page, epoch) }
                        ensureActive()
                        if (generation != epoch) break
                        results[page] = tiles
                        metrics[page] = stats
                        states[page] = R.string.sr_done
                        forced.remove(page)
                        progress = 100; message = null
                        restore()
                    } catch (_: YieldToTranslation) {
                        message = context.getString(R.string.sr_wait_translation); changed()
                        // The next admission waits behind all translation tickets.
                        yield()
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: SuperResolutionPageProcessor.Animation) { states[page] = R.string.sr_animation
                    } catch (_: MissingModels) {
                        states[page] = R.string.sr_models_required
                        message = context.getString(R.string.sr_models_required)
                    } catch (_: SuperResolutionPageProcessor.MemoryBudget) { states[page] = R.string.sr_memory_unavailable
                    } catch (error: Exception) {
                        states[page] = R.string.sr_failed_short
                        message = context.getString(R.string.sr_failed, error.message.orEmpty())
                    } catch (_: LinkageError) { states[page] = R.string.sr_failed_short
                    } catch (_: OutOfMemoryError) { states[page] = R.string.sr_memory_unavailable }
                    changed()
                }
                if (fullGallery && pages().all { it in states }) {
                    message = context.getString(R.string.sr_gallery_done, completed, failed)
                    fullGallery = false
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: Exception) {
                enabled = false
                message = context.getString(R.string.sr_failed, error.message.orEmpty())
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { source?.stop() }
                    activePage = null; worker = null; working = false; changed()
                    if (!closed && enabled && (browsing || fullGallery) && pages().any { it !in states }) start()
                    else if (!browsing || !enabled) releaseModels()
                }
            }
        }
        changed()
    }

    private class YieldToTranslation : CancellationException()
    private class MissingModels : RuntimeException()

    private suspend fun process(provider: GalleryProvider2, page: Int, epoch: Int): Pair<EnhancedTilePage, SuperResolutionPageMetrics> {
        val source = File.createTempFile("sr-original-", ".image", context.cacheDir)
        val selected = options
        try {
            var saved = false
            for (attempt in 0 until 30) {
                currentCoroutineContext().ensureActive()
                if (provider.save(page, checkNotNull(UniFile.fromFile(source)))) { saved = true; break }
                if (attempt == 0) withContext(Dispatchers.Main) { provider.prepareTranslationSource(page) }
                delay(500)
            }
            check(saved) { context.getString(R.string.sr_source_unavailable) }
            if (ImageProcessingPriority.work.translationPending) withContext(Dispatchers.Main) {
                if (generation == epoch) { message = context.getString(R.string.sr_wait_translation); changed() }
            }
            return ImageProcessingPriority.work.run(translation = false) {
                TranslationRuntime.lock.withLock {
                    // Keep a warm translator across a paired page boundary when Android
                    // reports enough headroom; low-memory devices still release it first.
                    if (!NativeMemoryPolicy(context).canRetain()) TranslationRuntime.closeIdleModels()
                }
                SuperResolutionModels.lock.withLock {
                    val store = SuperResolutionStore(context, identity, selected)
                    val key = store.key(source)
                    val force = withContext(NonCancellable + Dispatchers.Main) { forced.remove(page) }
                    if (force) { store.invalidate(key); store.invalidateTiles(key) }
                    var engine: SuperResolution? = null
                    fun checkPriority() {
                        if (ImageProcessingPriority.work.translationPending) throw YieldToTranslation()
                    }
                    fun report(value: Int) {
                        scope.launch {
                            if (generation == epoch && activePage == page) {
                                progress = value
                                message = context.getString(when {
                                    engine?.usingVulkan == true -> R.string.sr_progress_gpu
                                    engine?.fellBack == true -> R.string.sr_progress_fallback
                                    else -> R.string.sr_progress
                                }, value)
                                changed()
                            }
                        }
                    }
                    SuperResolutionPageProcessor(context).process(source, store, key,
                        SuperResolutionMemoryBudget.current(context), ::checkPriority,
                        render = { bitmap, region, callback ->
                            if (engine == null) {
                                if (!models.store.ready()) throw MissingModels()
                                val files = models.files()
                                engine = engines.acquire("${selected.mode}:${selected.halfArithmetic}:${selected.batchTiles}") {
                                    SuperResolution(files,
                                        if (selected.mode == "cpu") SuperResolutionBackend.CPU else SuperResolutionBackend.AUTO,
                                        if (selected.mode == "auto_fast") SuperResolutionPipeline.GPU_FP16 else SuperResolutionPipeline.GPU_FP32,
                                        halfArithmetic = selected.halfArithmetic, batchTiles = selected.batchTiles)
                                }
                            }
                            checkNotNull(engine).upscaleRegion(bitmap, region.left, region.top, region.width(), region.height(),
                                8L * 1024 * 1024) { done, total -> checkPriority(); callback(done, total) }
                        }, progress = ::report,
                        ready = { tiles ->
                            withContext(Dispatchers.Main) {
                                ensureActive()
                                if (generation == epoch && enabled && browsing && current == page && reader != null) {
                                    reader?.displayTiles(page, tiles)
                                    true
                                } else false
                            }
                        }, grouped = true).also { (tiles, _) ->
                            if (selected.persist) {
                                val download = galleryInfo?.let { EhApplication.getDownloadManager(context).getDownloadInfo(it.gid) }
                                val directory = download?.let { TranslationStorage.galleryDir(context, it) }
                                    ?: provider.translationDirectory
                                if (download?.state == DownloadInfo.STATE_FINISH && TranslationStorage.hasGalleryDirectory(download))
                                    checkNotNull(directory) { "Gallery download directory unavailable" }
                                if (directory != null) SuperResolutionExport.save(tiles, directory, provider.getTranslationFilename(page))
                            }
                        }
                }
            }
        } finally { source.delete() }
    }

    private fun restore() {
        if (!enabled || !browsing || reader?.hasPage(current) != false) return
        val tiles = results[current] ?: return
        if (tiles.directory.isDirectory) reader?.displayTiles(current, tiles)
        else { states.remove(current); results.remove(current); start() }
    }

    fun close() {
        closed = true; disable(); reader = null
        pageWork.remove(GalleryPageWork.Stage.SUPER_RESOLUTION, pageQueue)
        GalleryPageWorkRegistry.release(identity, pageWork)
        // Disposal is independent of the canceled worker, and holds the same model lock.
        scope.launch { worker?.join(); withContext(Dispatchers.IO) { SuperResolutionModels.lock.withLock { engines.close() } }; scope.cancel() }
    }
}
