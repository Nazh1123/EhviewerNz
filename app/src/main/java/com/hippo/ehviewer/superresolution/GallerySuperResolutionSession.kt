package com.hippo.ehviewer.superresolution

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.translation.ImageProcessingPriority
import com.hippo.ehviewer.translation.TranslationRuntime
import com.hippo.ehviewer.translation.TranslationWindow
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
    private val results = mutableMapOf<Int, File>()
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

    private fun changed() { reader?.changed(); SuperResolutionTasks.changed() }
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
                delay(250)
                while (enabled && generation == epoch && (browsing || fullGallery)) {
                    val page = pages().firstOrNull { it !in states } ?: break
                    activePage = page; progress = 0; message = context.getString(R.string.sr_preparing); changed()
                    try {
                        val file = withContext(Dispatchers.IO) { process(provider, page, epoch) }
                        ensureActive()
                        if (generation != epoch) break
                        results[page] = file
                        states[page] = R.string.sr_done
                        forced.remove(page)
                        progress = 100; message = null
                        restore()
                    } catch (_: YieldToTranslation) {
                        message = context.getString(R.string.sr_wait_translation); changed()
                        // The next admission waits behind all translation tickets.
                        yield()
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: AnimatedPage) { states[page] = R.string.sr_animation
                    } catch (_: MissingModels) {
                        states[page] = R.string.sr_models_required
                        message = context.getString(R.string.sr_models_required)
                    } catch (_: PageOverBudget) { states[page] = R.string.sr_memory_unavailable
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
    private class AnimatedPage : RuntimeException()
    private class PageOverBudget : RuntimeException()
    private class MissingModels : RuntimeException()

    private suspend fun process(provider: GalleryProvider2, page: Int, epoch: Int): File {
        val source = File.createTempFile("sr-original-", ".image", context.cacheDir)
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
                TranslationRuntime.lock.withLock { TranslationRuntime.closeIdleModels() }
                SuperResolutionModels.lock.withLock {
                    val store = SuperResolutionStore(context, identity, options)
                    val key = store.key(source)
                    val force = withContext(Dispatchers.Main) { page in forced }
                    if (force) store.invalidate(key)
                    if (!force) store.find(key)?.let { return@withLock store.retain(it) }
                    if (!models.store.ready()) throw MissingModels()
                    fun checkPriority() {
                        if (ImageProcessingPriority.work.translationPending) throw YieldToTranslation()
                    }
                    checkPriority()
                    val budget = SuperResolutionMemoryBudget.current(context)
                    val bitmap = decodeOriginal(source, budget)
                    try {
                        val files = models.files()
                        val engine = engines.acquire(options.mode) {
                            SuperResolution(files,
                                if (options.mode == "cpu") SuperResolutionBackend.CPU else SuperResolutionBackend.AUTO,
                                if (options.mode == "auto_fast") SuperResolutionPipeline.GPU_FP16 else SuperResolutionPipeline.GPU_FP32)
                        }
                        val result = engine.upscale(bitmap, budget) { done, total ->
                            checkPriority()
                            val value = done * 100 / total.coerceAtLeast(1)
                            scope.launch {
                                if (generation == epoch && activePage == page) {
                                    progress = value
                                    message = context.getString(when {
                                        engine.usingVulkan -> R.string.sr_progress_gpu
                                        engine.fellBack -> R.string.sr_progress_fallback
                                        else -> R.string.sr_progress
                                    }, value)
                                    changed()
                                }
                            }
                        }
                        try { checkPriority(); store.save(key, result) }
                        finally { result.recycle() }
                    } finally { bitmap.recycle() }
                }
            }
        } finally { source.delete() }
    }

    private fun decodeOriginal(source: File, budget: Long): Bitmap {
        val header = ByteArray(21)
        val count = source.inputStream().use { it.read(header) }
        if ((count >= 3 && String(header, 0, 3, Charsets.US_ASCII) == "GIF") ||
            (count >= 21 && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && header[20].toInt() and 2 != 0)) throw AnimatedPage()
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            if (info.isAnimated) throw AnimatedPage()
            if (!SuperResolution.accepts(info.size.width, info.size.height, budget)) throw PageOverBudget()
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (!SuperResolution.accepts(bounds.outWidth, bounds.outHeight, budget)) throw PageOverBudget()
        return checkNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    }

    private fun restore() {
        loader?.cancel()
        if (!enabled || !browsing || reader?.hasPage(current) != false) return
        val file = results[current] ?: return
        val page = current
        val epoch = generation
        loader = scope.launch {
            var bitmap: Bitmap? = null
            var overBudget = false
            try {
                withContext(Dispatchers.IO) {
                    SuperResolutionModels.lock.withLock {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Saved SR image unavailable")
                        if (bounds.outWidth.toLong() * bounds.outHeight * 4 > SuperResolutionMemoryBudget.current(context)) {
                            overBudget = true
                            return@withLock
                        }
                        bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
                    }
                }
                ensureActive()
                if (overBudget && generation == epoch) { message = context.getString(R.string.sr_memory_unavailable); changed() }
                if (enabled && current == page && generation == epoch && browsing) bitmap?.let {
                    val image = checkNotNull(Image.create(it))
                    bitmap = null
                    reader?.display(page, image) ?: image.recycle()
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) {
                if (generation == epoch) { states.remove(page); results.remove(page); start() }
            } catch (_: OutOfMemoryError) { message = context.getString(R.string.sr_memory_unavailable)
            } finally { bitmap?.recycle() }
        }
    }

    fun close() {
        closed = true; disable(); reader = null
        // Disposal is independent of the canceled worker, and holds the same model lock.
        scope.launch { worker?.join(); withContext(Dispatchers.IO) { SuperResolutionModels.lock.withLock { engines.close() } }; scope.cancel() }
    }
}
