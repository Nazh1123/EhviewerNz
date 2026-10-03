package com.hippo.ehviewer.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.ehviewer.R
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.ResumablePipeline
import li.joye.yakuyomi.engine.TranslationStage
import li.joye.yakuyomi.engine.TranslationResume

/** Application-owned work; the reader is a detachable observer. All scheduling state lives on Main. */
internal class GalleryTranslationSession(
    private val context: Context,
    var provider: GalleryProvider2,
    private val ownsProvider: Boolean,
    val identity: String,
    val galleryInfo: GalleryInfo?,
    private var knownSize: Int,
) {
    interface Reader {
        fun changed()
        fun hasTranslatedPage(page: Int): Boolean
        fun display(page: Int, image: Image)
        fun clearTranslations() = Unit
    }

    var reader: Reader? = null
    @Volatile var browsing = true
        private set
    // Logical reader lifetime survives temporary backgrounding and Activity recreation.
    var readerOpen = true
        private set
    val settings = TranslationSettings(context)
    @Volatile private var sourceLanguage = GallerySourceLanguage()
    private fun resolvedOptions(options: TranslationOptions) = sourceLanguage.options(options)
    private suspend fun identifySourceLanguage(options: TranslationOptions, texts: List<String>,
                                              request: TranslationPageRequest, epoch: Int) {
        if (options.source != TranslationLanguages.AUTO_SOURCE) return
        val detected = sourceLanguage.observe(texts) {
            if (generation != epoch) throw SupersededTranslationPage()
            request.ensureRelevant()
        }
        if (detected != null) withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, context.getString(R.string.translation_source_detected,
                TranslationLanguages.displayName(detected, context.resources.configuration.locales[0])),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private val models = TranslationModels(context)
    private val memoryPolicy = NativeMemoryPolicy(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val states = mutableMapOf<Int, Int>()
    val timings = mutableMapOf<Int, PageStats>()
    private val sourceAttempts = mutableMapOf<Int, Int>()
    private val forced = mutableSetOf<Int>()
    private val partialRetries = mutableSetOf<Int>()
    private val restorePages = mutableSetOf<Int>()
    // Content/configuration were checked when these files were produced or first adopted.
    // Keep paths, not bitmaps, across reader recreation and memory-cache eviction.
    private val completedFiles = mutableMapOf<Int, UniFile>()
    private val completedKeys = mutableMapOf<Int, String>()
    private val completedNames = mutableMapOf<Int, String>()
    private val retainedKeys = mutableSetOf<String>()
    private var retentionJob: Job? = null
    private var resultLoader: Job? = null
    private var loadingPages = emptyList<Int>()
    private var readingDirection = 1
    var activeOptions = settings.readForResults()
    var pageProgress = TranslationPageProgress()
    var current = -1
    private var pendingStart = -1
    @Volatile var enabled = false
        private set
    var fullGallery = false
        private set
    var working = false
        private set
    private var sourceStarted = !ownsProvider
    private var activated = false
    private var sourceUsed = false
    val size: Int get() = if (sourceStarted) provider.size() else knownSize
    @Volatile private var generation = 0
    private var worker: Job? = null
    private var serviceRequested = false
    @Volatile private var activeRequest: TranslationPageRequest? = null
    // Main owns admission and visible progress; each coroutine carries its own request.
    private val activeRequests = linkedMapOf<Int, TranslationPageRequest>()
    private val resultLock = Mutex()
    private val sourceLock = Mutex()
    private class NativeModels(
        val epoch: Int, val identity: String,
        val engine: ResumablePipeline, val translator: AutoCloseable,
    ) : AutoCloseable {
        override fun close() { try { engine.close() } finally { translator.close() } }
    }
    private val retainedModels = RetainedTranslationModels<NativeModels>(
        TranslationRuntime.modelScope, TranslationRuntime.lock,
        idleRemaining = { withContext(Dispatchers.Main) { TranslationTasks.modelIdleRemainingMillis() } },
        canRetain = { enabled && browsing && memoryPolicy.canRetain() },
    )

    fun releaseIdleModels() = retainedModels.release()

    // Caller owns TranslationRuntime.lock; transfer parked models for disposal off Main.
    internal fun takeIdleModels(): AutoCloseable? = retainedModels.take()

    val title: String get() = galleryInfo?.title?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.translation_service_label)
    val total: Int get() = if (fullGallery) size.coerceAtLeast(0) else
        TranslationWindow.pages(current, size, activeOptions.ahead).size
    val completed: Int get() = queueCompleted()
    val failed: Int get() = queuePages().count { states[it] in setOf(R.string.translation_failed, R.string.translation_models_required) }
    val partial: Int get() = queuePages().count { states[it] == R.string.translation_partial }
    val activePage: Int? get() = activeRequest?.takeUnless { it.isObsolete }?.page

    fun onPageChanged(page: Int) {
        if (current >= 0 && page != current) readingDirection = if (page > current) 1 else -1
        current = page
        retainCompletedResults()
        if (states[page] in imageStates && reader?.hasTranslatedPage(page) == false)
            restorePages.add(page)
        val active = activeRequest?.takeUnless { it.isObsolete }
        val anchor = active?.page ?: pendingStart.takeIf { it >= 0 } ?: page
        if (!enabled || kotlin.math.abs(page.toLong() - anchor) > 2 || activeRequest?.isObsolete == true) {
            pendingStart = page
            val newWindow = TranslationWindow.pages(page, size, activeOptions.ahead)
            val obsolete = if (activeOptions.backend == TranslationBackend.LLM_API)
                activeRequests.values.filter {
                    kotlin.math.abs(page.toLong() - it.page) > 2 && it.page !in newWindow
                }
            else listOfNotNull(active)
            obsolete.forEach {
                it.supersede()
                if (it.retryMissing && completedFiles.containsKey(it.page))
                    states[it.page] = imageState(completedFiles.getValue(it.page))
                else if (!it.restoring && states[it.page] !in completedStates) states.remove(it.page)
                sourceAttempts.remove(it.page)
                forced.remove(it.page)
                partialRetries.remove(it.page)
            }
            if (activeOptions.backend == TranslationBackend.LLM_API) {
                selectActiveRequest()
                if (activeRequest == null) pageProgress = TranslationPageProgress()
            }
            else pageProgress = TranslationPageProgress()
        } else pendingStart = minOf(page, anchor)
        restoreReaderResults()
        changed()
        enqueue(page, false)
    }

    fun setBrowsing(visible: Boolean) {
        browsing = visible
        if (visible && reader != null) readerOpen = true
        if (visible) onPageChanged(current) else {
            // Active JNI work owns its models until a stage boundary. Parked models
            // can be disposed off Main immediately under the inference lock.
            retainedModels.release()
            cancelResultLoader()
            // Preserve the in-flight page and the reader's forward order in full mode.
            changed()
            enqueue(current, false)
        }
    }

    fun enable(): Boolean {
        if (enabled) return true
        val options = settings.readForResults()
        if (activeOptions.cacheIdentity() != options.cacheIdentity()) {
            reader?.clearTranslations()
            timings.clear()
            completedFiles.clear()
            completedKeys.clear()
            completedNames.clear()
            retainedKeys.clear()
        }
        activeOptions = options
        enabled = true
        pendingStart = current
        states.clear()
        completedFiles.forEach { (page, file) -> states[page] = imageState(file) }
        sourceAttempts.clear()
        forced.clear()
        partialRetries.clear()
        restorePages.clear()
        activated = true
        retainCompletedResults()
        restoreReaderResults()
        changed()
        enqueue(current, false)
        return enabled
    }

    fun translateFullGallery(): Boolean {
        if (!enable() || !enabled) return false
        fullGallery = true
        // Promotion shares the ordinary queue's foreground execution and wake lock.
        if (working && !ensureExecutionService()) return false
        changed()
        enqueue(current, false)
        return enabled
    }

    private fun resultStore(options: TranslationOptions, sourceName: String? = null): TranslationResultStore {
        val download = galleryInfo?.let { EhApplication.getDownloadManager(context).getDownloadInfo(it.gid) }
        val directory = download?.let { TranslationStorage.galleryDir(context, it) }
        // An available source folder can retain each completed page while the rest
        // of the gallery is still downloading, paused, or failed.
        val downloaded = download != null && directory != null
        if (options.persistDownloaded && download?.state == DownloadInfo.STATE_FINISH &&
            TranslationStorage.hasGalleryDirectory(download)) {
            checkNotNull(directory) { "Gallery download directory unavailable" }
        }
        return TranslationResultStore(TranslationStorage.cacheDir(context),
            directory, options, galleryInfo?.gid, downloaded,
            sourceName)
    }

    /** Settled reader pages bypass inference; still adopt them after persistence is enabled. */
    private fun retainCompletedResults() {
        if (!enabled || !activeOptions.persistDownloaded || completedKeys.isEmpty() || retentionJob != null) return
        val options = activeOptions
        val epoch = generation
        if (completedKeys.values.all { it in retainedKeys }) return
        retentionJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    TranslationRuntime.lock.withLock {
                        retainCompletedResults(epoch, options)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.w("GalleryTranslation", "Cannot retain completed reader results", error)
            } finally {
                retentionJob = null
                if (generation != epoch) retainCompletedResults()
            }
        }
    }

    // Caller owns TranslationRuntime.lock so eviction/deletion cannot race with adoption.
    private suspend fun retainCompletedResults(epoch: Int, options: TranslationOptions) {
        if (!options.persistDownloaded) return
        val keys = withContext(Dispatchers.Main) {
            if (generation == epoch) completedKeys.filterValues { it !in retainedKeys } else emptyMap()
        }
        val names = withContext(Dispatchers.Main) { completedNames.toMap() }
        if (keys.isEmpty()) return
        resultLock.withLock {
            val results = resultStore(options)
            if (!results.persists) return@withLock
            for ((page, key) in keys) {
                val pageResults = resultStore(options, names[page])
                val file = pageResults.existingImage(key)
                val retained = file?.let { pageResults.retain(key, it) }
                if (retained != null || pageResults.isSkipped(key)) withContext(Dispatchers.Main) {
                    if (generation == epoch && completedKeys[page] == key) {
                        if (retained != null) completedFiles[page] = retained
                        retainedKeys.add(key)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startWorker() {
        if (!enabled || !activated) return
        retainedModels.queued()
        working = true
        if (!ensureExecutionService()) return
        if (worker != null) return
        val options = activeOptions
        val epoch = generation
        // ATOMIC guarantees the cleanup runs even when disabled before the IO dispatch.
        worker = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val runningWorker = coroutineContext.job
            val yielded = AtomicBoolean(false)
            var turn: TranslationScheduler.Turn? = null
            try {
                // Assign on Main: cancellation returning to IO must not lose an admitted turn.
                withContext(Dispatchers.Main) {
                    turn = TranslationTasks.scheduler.acquire { schedulingPriority() }
                }
                TranslationRuntime.lock.withLock {
                    TranslationRuntime.closeIdleModels(this@GalleryTranslationSession)
                    withContext(Dispatchers.Main) {
                        if (!sourceStarted) {
                            if (sourceUsed) provider = requireNotNull(provider.createTranslationProvider(context))
                            provider.start()
                            sourceStarted = true
                            sourceUsed = true
                        }
                    }
                    var waits = 0
                    while (size == GalleryProvider2.STATE_WAIT && waits++ < 30) {
                        delay(1000)
                        ensureActive()
                    }
                    check(size > 0) { "Gallery source unavailable" }
                    withContext(Dispatchers.Main) { knownSize = size }
                    // A foreground native lease can survive queue turns without holding this lock idle.
                    retainCompletedResults(epoch, options)
                    resultStore(options).prune()
                    val retained = retainedModels.take()?.takeIf {
                        if (it.epoch == epoch && it.identity == options.cacheIdentity()) true
                        else { it.close(); false }
                    }
                    var engine: ResumablePipeline? = retained?.engine
                    var translator: AutoCloseable? = retained?.translator
                    var modelsUnavailable = false
                    val memoryEpoch = TranslationRuntime.memoryEpoch
                    fun retainNativeModels() = options.backend == TranslationBackend.NATIVE_LLM &&
                        enabled && browsing && generation == epoch &&
                        memoryEpoch == TranslationRuntime.memoryEpoch && memoryPolicy.canRetain()
                    try {
                        suspend fun process(request: TranslationPageRequest): Unit = withContext(request) {
                            val pageJob = currentCoroutineContext().job
                            val page = request.page
                            val source = File.createTempFile("translation-source-", ".img", context.cacheDir)
                            try {
                                status(epoch, page, R.string.translation_working)
                                if (!sourceLock.withLock { provider.save(page, requireNotNull(UniFile.fromFile(source))) }) {
                                    withContext(Dispatchers.Main) {
                                        if (generation == epoch && !request.isObsolete) {
                                            val attempts = (sourceAttempts[page] ?: 0) + 1
                                            sourceAttempts[page] = attempts
                                            states[page] = if (attempts < 30) R.string.translation_waiting
                                                else if (request.retryMissing) R.string.translation_partial else R.string.translation_failed
                                            if (attempts == 1) provider.prepareTranslationSource(page)
                                            if (request.force && attempts < 30) forced.add(page)
                                            if (request.retryMissing && attempts < 30) partialRetries.add(page)
                                            changed()
                                        }
                                    }
                                    delay(1000)
                                    return@withContext
                                }
                                ensureActive()
                                request.ensureRelevant()
                                request.sourceName = provider.getTranslationFilename(page)
                                var pageOptions = resolvedOptions(options)
                                var results = resultStore(pageOptions, request.sourceName)
                                withContext(Dispatchers.Main) {
                                    if (generation == epoch) completedNames[page] = checkNotNull(request.sourceName)
                                }
                                if (isAnimation(source)) {
                                    status(epoch, page, R.string.translation_animation)
                                    return@withContext
                                }
                                var key = results.key(source)
                                var preparationKey = results.preparationKey(source)
                                val cached = resultLock.withLock {
                                    if (request.force) return@withLock false
                                    val existing = results.existingImage(key)
                                    if (existing != null) {
                                        if (display(epoch, page, results.retain(key, existing), key, results.persists)) return@withLock true
                                        request.ensureRelevant()
                                        results.invalidate(key)
                                    }
                                    if (results.isSkipped(key)) {
                                        withContext(Dispatchers.Main) {
                                            completedKeys[page] = key
                                            if (results.persists) retainedKeys.add(key)
                                        }
                                        status(epoch, page, R.string.translation_no_text)
                                        true
                                    } else false
                                }
                                if (cached) return@withContext
                                var resume = if (request.retryMissing) results.readResume(key) else null
                                if (modelsUnavailable || !models.ready() ||
                                    options.backend == TranslationBackend.NATIVE_LLM &&
                                    options.source != TranslationLanguages.AUTO_SOURCE && !NativeModelStore(context).ready(options)) {
                                    modelsUnavailable = true
                                    modelsRequired(epoch, request)
                                    return@withContext
                                }
                                if (engine == null) {
                                    // Cached results need neither model checksums nor a language model.
                                    val modelSet = try {
                                        if (options.backend == TranslationBackend.ML_KIT && options.source != TranslationLanguages.AUTO_SOURCE) {
                                            check(OfflineTranslator.isReady(pageOptions.target, pageOptions.mlKitSource)) { "Language model missing" }
                                        }
                                        models.verified()
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (error: Exception) {
                                        modelsUnavailable = true
                                        modelsRequired(epoch, request)
                                        return@withContext
                                    }
                                    suspend fun createTranslator(selectedOptions: TranslationOptions): li.joye.yakuyomi.engine.Translator = when (selectedOptions.backend) {
                                        TranslationBackend.NATIVE_LLM -> NativeTranslator(context, selectedOptions,
                                            prefixCacheProvider = { TranslationRuntime.nativePrefixCache() }) {
                                            if (generation != epoch) throw SupersededTranslationPage()
                                            checkNotNull(activeRequest).ensureRelevant()
                                        }
                                        TranslationBackend.ML_KIT -> OfflineTranslator(selectedOptions.target, selectedOptions.mlKitSource)
                                        TranslationBackend.LLM_API -> ApiTranslator(selectedOptions)
                                    }
                                    val selected = if (options.source == TranslationLanguages.AUTO_SOURCE)
                                        GallerySourceTranslator({ resolvedOptions(options) }, ::createTranslator)
                                    else createTranslator(options)
                                    translator = selected as AutoCloseable
                                    val guarded = object : li.joye.yakuyomi.engine.DetailedTranslator {
                                        override suspend fun translateDetailed(queries: List<String>): li.joye.yakuyomi.engine.LlmTranslator.TranslateResult {
                                            val active = checkNotNull(currentCoroutineContext()[TranslationPageRequest])
                                            active.ensureRelevant()
                                            suspend fun call() = if (selected is li.joye.yakuyomi.engine.DetailedTranslator)
                                                selected.translateDetailed(queries)
                                            else li.joye.yakuyomi.engine.LlmTranslator.TranslateResult(selected.translate(queries))
                                            // The factory releases native models after both page branches
                                            // have joined; a background transition must not close active JNI.
                                            val result = if (options.backend == TranslationBackend.LLM_API) active.apiCall { call() }
                                                else call()
                                            active.ensureRelevant()
                                            return result
                                        }
                                    }
                                    engine = TranslationEngineFactory.create(context, modelSet, options, guarded,
                                        beforeImageStage = {
                                            (selected as? NativeTranslator)?.unloadModel()
                                            (selected as? GallerySourceTranslator)?.unloadNativeModel()
                                        },
                                        retainNativeModels = ::retainNativeModels,
                                        resolvedOptions = { resolvedOptions(options) },
                                        onRecognized = { lines ->
                                            identifySourceLanguage(options, lines.map { it.text },
                                                checkNotNull(currentCoroutineContext()[TranslationPageRequest]), epoch)
                                        })
                                    if (options.backend == TranslationBackend.LLM_API) checkNotNull(engine).warmUp()
                                }
                                if (request.force && !request.retryMissing) resultLock.withLock {
                                    results.invalidate(key)
                                    TranslationRuntime.preparedPages.invalidate(preparationKey)
                                }
                                val activeEngine = checkNotNull(engine)
                                request.ensureRelevant()
                                val bitmap = decodeBounded(source, options.backend)
                                // A new automatic task must classify fresh OCR from the current model.
                                var prepared = if (options.source == TranslationLanguages.AUTO_SOURCE && sourceLanguage.language == null)
                                    null else TranslationRuntime.preparedPages.take(preparationKey)
                                val reused = prepared != null
                                var retainPreparation = true
                                try {
                                    val report: suspend (TranslationStage, Float) -> Unit = { stage, fraction ->
                                        stageProgress(epoch, request, stage, fraction)
                                        if (stage == TranslationStage.TRANSLATE && fraction < 1f &&
                                            withContext(Dispatchers.Main) { TranslationTasks.scheduler.shouldYield(checkNotNull(turn)) }) {
                                            yielded.set(true)
                                            throw YieldTranslationTurn()
                                        }
                                    }
                                    // Retain completed native outputs even if the reader is disabled mid-call.
                                    if (prepared == null) prepared = withContext(NonCancellable) {
                                        activeEngine.prepare(bitmap, report) {
                                            pageJob.ensureActive()
                                            request.ensureRelevant()
                                        }
                                    }
                                    ensureActive()
                                    request.ensureRelevant()
                                    // First-page OCR can resolve "auto". Store this very page under the resolved
                                    // language identity so the next session cannot reuse an unspecified prompt.
                                    val afterOcrOptions = resolvedOptions(options)
                                    if (afterOcrOptions.cacheIdentity() != pageOptions.cacheIdentity()) {
                                        pageOptions = afterOcrOptions
                                        results = resultStore(pageOptions, request.sourceName)
                                        key = results.key(source)
                                        preparationKey = results.preparationKey(source)
                                        resume = if (request.retryMissing) results.readResume(key) else null
                                    }
                                    if (pageOptions.backend == TranslationBackend.NATIVE_LLM && prepared!!.regions.isNotEmpty() &&
                                        !NativeModelStore(context).ready(pageOptions)) {
                                        modelsUnavailable = true
                                        modelsRequired(epoch, request)
                                        return@withContext
                                    }
                                    if (pageOptions.backend == TranslationBackend.ML_KIT && prepared!!.regions.isNotEmpty()) {
                                        if (pageOptions.source == TranslationLanguages.AUTO_SOURCE) {
                                            status(epoch, page, R.string.translation_failed)
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, R.string.translation_source_unknown, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                            return@withContext
                                        }
                                        if (!OfflineTranslator.isReady(pageOptions.target, pageOptions.mlKitSource)) {
                                            modelsUnavailable = true
                                            modelsRequired(epoch, request)
                                            return@withContext
                                        }
                                    }
                                    if (resume != null) prepared?.restoreTranslations(pageOptions.cacheIdentity(), resume!!)
                                    if (withContext(Dispatchers.Main) { TranslationTasks.scheduler.shouldYield(checkNotNull(turn)) }) {
                                        yielded.set(true)
                                        throw YieldTranslationTurn()
                                    }
                                    val saved = finishTranslation(epoch, request, key, pageOptions, bitmap,
                                        resume = { prepared?.translationResume(pageOptions.cacheIdentity())?.takeIf { it.missingCount > 0 } }) {
                                        activeEngine.translatePrepared(bitmap, prepared, reused, report) { !request.isObsolete }
                                    }
                                    retainPreparation = !saved || prepared?.translationResume(pageOptions.cacheIdentity())?.missingCount?.let { it > 0 } == true
                                    ensureActive()
                                    request.ensureRelevant()
                                    if (!saved) status(epoch, page,
                                        if (yielded.get()) R.string.translation_waiting else if (request.retryMissing)
                                            R.string.translation_partial else R.string.translation_failed)
                                } finally {
                                    prepared?.let {
                                        if (retainPreparation) TranslationRuntime.preparedPages.put(preparationKey, it)
                                        else it.close()
                                    }
                                    bitmap.recycle()
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: SupersededTranslationPage) {
                                // Native work has returned safely; dispatch from the latest window next.
                            } catch (_: YieldTranslationTurn) {
                                status(epoch, page, R.string.translation_waiting)
                            } catch (error: Exception) {
                                android.util.Log.w("GalleryTranslation",
                                    "Page ${page + 1} failed before rendering (${error.javaClass.simpleName})")
                                status(epoch, page, if (request.retryMissing) R.string.translation_partial else R.string.translation_failed)
                            } finally {
                                source.delete()
                                withContext(NonCancellable + Dispatchers.Main) {
                                    finishRequest(epoch, request)
                                    TranslationTasks.scheduler.pageFinished(checkNotNull(turn))
                                }
                            }
                        }
                        runTranslationPages(options.pageConcurrency, parallelReady = {
                            engine != null && (options.source != TranslationLanguages.AUTO_SOURCE || sourceLanguage.language != null)
                        }, next = {
                            ensureActive()
                            if (yielded.get() || withContext(Dispatchers.Main) {
                                    TranslationTasks.scheduler.shouldYield(checkNotNull(turn))
                                }) {
                                yielded.set(true)
                                null
                            } else withContext(Dispatchers.Main) { nextRequest() }
                        }, process = ::process)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            if (generation == epoch) {
                                disable()
                                android.widget.Toast.makeText(context, R.string.translation_prepare_required, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        val readyEngine = engine
                        val readyTranslator = translator
                        if (retainNativeModels() && readyEngine != null && readyTranslator != null) {
                            retainedModels.park(NativeModels(epoch, options.cacheIdentity(), readyEngine, readyTranslator))
                            // Main can disable/re-enable or background the reader while IO parks.
                            if (!retainNativeModels()) retainedModels.take()?.close()
                        } else try { engine?.close() } finally { translator?.close() }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (generation == epoch) {
                        disable()
                        android.widget.Toast.makeText(context, R.string.translation_gallery_unavailable,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    // A cancelled native call may outlive disable()/enable(). Keep its worker
                    // registered until cleanup finishes so the replacement cannot reuse its source.
                    try {
                        if (worker === runningWorker) {
                            worker = null
                            working = false
                            if (generation == epoch && fullGallery && pendingPages().all { isPageSettled(it) } && size > 0) {
                                TranslationTasks.completed(this@GalleryTranslationSession, size, failed)
                                fullGallery = false
                                serviceRequested = false
                            }
                            if (ownsProvider && sourceStarted) {
                                try { provider.stop() } finally { sourceStarted = false }
                            }
                            if (!fullGallery && pendingPages().all { isPageSettled(it) }) serviceRequested = false
                            changed()
                        }
                    } finally {
                        turn?.let { TranslationTasks.scheduler.release(it) }
                        // Reader events can arrive after nextRequest() returned null while the
                        // engine is closing. Recheck before declaring the session idle.
                        if (enabled && worker == null && (fullGallery || yielded.get() || pendingPages().any { !isPageSettled(it) })) startWorker()
                        else if (worker == null) retainedModels.idle()
                    }
                }
            }
        }
    }

    private fun ensureExecutionService(): Boolean = try {
        if (!serviceRequested) {
            TranslationTasks.wake(context, this)
            serviceRequested = true
        }
        true
    } catch (_: RuntimeException) {
        disable()
        android.widget.Toast.makeText(context, R.string.translation_background_unavailable,
            android.widget.Toast.LENGTH_LONG).show()
        false
    }

    fun enqueue(page: Int, force: Boolean, retryMissing: Boolean = false) {
        if (!enabled || page < 0) return
        if (force) {
            activeRequests[page]?.supersede()
            if (activeOptions.backend == TranslationBackend.LLM_API) selectActiveRequest()
            if (retryMissing) {
                partialRetries.add(page)
                cancelResultLoader()
            } else {
                partialRetries.remove(page)
                invalidateResult(page)
            }
            forced.add(page); states.remove(page); sourceAttempts.remove(page)
            changed()
        }
        if (fullGallery || pendingPages().any { !isPageSettled(it) } || size <= 0) startWorker()
    }

    // Runs only on the UI thread. Rebuilding this list drops obsolete pending work on every turn.
    fun pendingPages(): List<Int> = pendingPageSequence().toList()

    private fun pendingPageSequence(): Sequence<Int> = if (!readerOpen && !fullGallery) emptySequence() else if (fullGallery)
        TranslationWindow.fullPageSequence(current, size,
            pendingStart.takeIf { it >= 0 } ?: current)
    else TranslationWindow.pages(current, size, activeOptions.ahead,
        pendingStart.takeIf { it >= 0 } ?: current).asSequence()

    fun isPageSettled(page: Int): Boolean = page !in forced &&
        (page in loadingPages || reader?.hasTranslatedPage(page) == true || states[page] in completedStates &&
            (states[page] !in imageStates || !browsing || reader == null ||
                if (fullGallery) page !in restorePages else page != current))

    private fun nextRequest(): TranslationPageRequest? {
        if (!enabled) return null
        val pages = pendingPages()
        if (fullGallery) {
            // Adopt valid reader images so detaching the reader cannot erase completed progress.
            pages.filter { it !in forced && states[it] == null && reader?.hasTranslatedPage(it) == true }
                .forEach { states[it] = completedFiles[it]?.let(::imageState) ?: R.string.translation_done }
        }
        val concurrent = activeOptions.backend == TranslationBackend.LLM_API
        val page = pages.firstOrNull { (!concurrent || it !in activeRequests) && !isPageSettled(it) } ?: return null
        val restoring = states[page] in imageStates && restorePages.remove(page)
        val request = TranslationPageRequest(page, forced.remove(page), restoring, partialRetries.remove(page))
        if (!concurrent) activeRequests.clear()
        activeRequests[page] = request
        if (!concurrent || activeRequest?.isObsolete != false) {
            pendingStart = page
            activeRequest = request
            pageProgress = request.progress
        }
        if (!restoring) states[page] = R.string.translation_working
        changed()
        return request
    }

    fun queuePages() = if (fullGallery) (0 until size.coerceAtLeast(0)).toList()
        else TranslationWindow.pages(current, size, activeOptions.ahead)

    fun queueCompleted() = queuePages().count { it !in forced &&
        (states[it] in completedStates || reader?.hasTranslatedPage(it) == true) }

    /** Current reader page, its lookahead, then all detached or out-of-window work. */
    internal fun schedulingPriority(): Int {
        if (!enabled) return 3
        // Current/lookahead checks are bounded by the reader window. Avoid allocating
        // and inspecting an entire gallery on Main for every scheduler decision.
        if (browsing && reader != null && (readerOpen || fullGallery)) {
            val window = TranslationWindow.pages(current, size, activeOptions.ahead)
            if (current in window && !isPageSettled(current)) return 0
            if (window.any { !isPageSettled(it) }) return 1
        }
        return if (size > 0 && pendingPageSequence().none { !isPageSettled(it) }) 3 else 2
    }

    private fun finishRequest(epoch: Int, request: TranslationPageRequest) {
        // A scheduler yield must preserve explicit retry intent despite an existing preview.
        if (enabled && generation == epoch && !request.isObsolete && request.force &&
            activeRequests[request.page] === request && states[request.page] == R.string.translation_waiting) {
            forced.add(request.page)
            if (request.retryMissing) partialRetries.add(request.page)
        }
        if (activeRequests[request.page] === request) activeRequests.remove(request.page)
        if (activeRequest === request) selectActiveRequest()
        if (enabled && generation == epoch) {
            changed()
            // The existing worker dispatches the next page. A replacement is started
            // only by its final cleanup after models and the scheduler turn are released.
        }
    }

    private fun selectActiveRequest() {
        if (activeRequest?.let { !it.isObsolete && activeRequests[it.page] === it } == true) return
        activeRequest = activeRequests.values.firstOrNull { !it.isObsolete }
        activeRequest?.let { pageProgress = it.progress }
    }

    private suspend fun status(epoch: Int, page: Int, resource: Int) = withContext(Dispatchers.Main) {
        val request = activeRequests[page]
        if (generation == epoch && request?.isObsolete != true) {
            if (request?.restoring != true || resource in completedStates) states[page] = resource
            changed()
        }
    }

    private suspend fun modelsRequired(epoch: Int, request: TranslationPageRequest) {
        status(epoch, request.page, if (request.retryMissing) R.string.translation_partial else R.string.translation_models_required)
        withContext(Dispatchers.Main) {
            if (enabled && generation == epoch && !request.isObsolete && browsing && request.page == current)
                android.widget.Toast.makeText(context, R.string.translation_models_required, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun stageProgress(epoch: Int, request: TranslationPageRequest,
                                      stage: TranslationStage, fraction: Float) = withContext(Dispatchers.Main) {
        if (enabled && generation == epoch && activeRequests[request.page] === request && !request.isObsolete) {
            request.progress.update(stage, fraction)
            if (activeRequest === request) changed()
        }
    }

    /** A finished render belongs to the source/configuration key even after the reader leaves.
     * Consume and release it inside the same non-cancellable block as inference: returning
     * an owned bitmap to a cancelled caller can discard it before either saving or recycling.
     */
    internal suspend fun finishTranslation(epoch: Int, request: TranslationPageRequest, key: String,
                                          options: TranslationOptions,
                                          original: Bitmap,
                                          resume: () -> TranslationResume? = { null },
                                          translate: suspend () -> PageResult): Boolean = withContext(NonCancellable) {
        val result = translate()
        try {
            when (result) {
                is PageResult.Translated -> {
                    resultLock.withLock {
                        // Download completion may have changed while this page was translating.
                        val sourceName = request.sourceName ?: provider.getTranslationFilename(request.page)
                        val results = resultStore(options, sourceName)
                        val checkpoint = resume()
                        TranslationOverlay.extract(original, result.page)
                        val writer: (java.io.OutputStream) -> Unit = { check(result.page.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        val output = if (checkpoint == null) results.writeImage(key, writer)
                            else results.writePartial(key, checkpoint, writer)
                        val relevant = withContext(Dispatchers.Main) {
                            enabled && generation == epoch && !request.isObsolete
                        }
                        if (relevant) {
                            withContext(Dispatchers.Main) {
                                if (enabled && generation == epoch && !request.isObsolete) {
                                    timings[request.page] = result.stats
                                    completedNames[request.page] = sourceName
                                }
                            }
                            check(display(epoch, request.page, output, key, results.persists)) { "Cannot decode translated page" }
                        }
                        results.prune()
                    }
                    true
                }
                is PageResult.Skipped -> {
                    resultLock.withLock {
                        val sourceName = request.sourceName ?: provider.getTranslationFilename(request.page)
                        val results = resultStore(options, sourceName)
                        results.recordSkipped(key)
                        withContext(Dispatchers.Main) {
                            if (enabled && generation == epoch && !request.isObsolete) {
                                completedKeys[request.page] = key
                                completedNames[request.page] = sourceName
                                if (results.persists) retainedKeys.add(key)
                            }
                        }
                    }
                    if (!request.isObsolete) status(epoch, request.page, R.string.translation_no_text)
                    true
                }
                is PageResult.Failed -> {
                    android.util.Log.w("GalleryTranslation", "Page ${request.page + 1} failed: ${result.reason}")
                    false
                }
            }
        } finally {
            if (result is PageResult.Translated) {
                result.page.recycle()
                result.analysis?.mask?.recycle()
            }
        }
    }

    private suspend fun display(epoch: Int, page: Int, file: UniFile, key: String, persistent: Boolean): Boolean {
        // Background work saves files only; never accumulate gallery bitmaps in memory.
        val needsImage = withContext(Dispatchers.Main) {
            browsing && reader != null && (!fullGallery ||
                kotlin.math.abs(page.toLong() - current) <= activeOptions.ahead)
        }
        val bitmap = if (needsImage) file.openInputStream().use { BitmapFactory.decodeStream(it) } ?: return false else null
        var transferred = false
        try {
            return withContext(Dispatchers.Main) {
                if (!enabled || generation != epoch || activeRequests[page]?.isObsolete == true) return@withContext false
                if (bitmap != null && browsing) reader?.let {
                    val image = Image.create(bitmap) ?: return@withContext false
                    it.display(page, image)
                    transferred = true
                }
                completedFiles[page] = file
                completedKeys[page] = key
                if (persistent) retainedKeys.add(key)
                states[page] = imageState(file)
                restorePages.remove(page)
                restoreReaderResults()
                changed()
                true
            }
        } finally { if (!transferred) bitmap?.recycle() }
    }

    fun disable() {
        enabled = false
        fullGallery = false
        serviceRequested = false
        generation++
        sourceLanguage = GallerySourceLanguage()
        retainedModels.release()
        cancelResultLoader()
        retentionJob?.cancel()
        activeRequests.values.forEach { it.supersede() }
        activeRequest?.supersede()
        activeRequest = null
        worker?.cancel()
        working = false
        pageProgress = TranslationPageProgress()
        if (ownsProvider && sourceStarted) {
            if (size > 0) knownSize = size
            // Stop pending source IO promptly; the registered worker still guards native
            // cleanup, and no replacement provider can start until that worker has returned.
            try { provider.stop() } finally { sourceStarted = false }
        }
        changed()
    }

    fun attach(observer: Reader) {
        reader = observer
        readerOpen = true
    }

    fun detach(observer: Reader, leavingGallery: Boolean = true) {
        if (reader !== observer) return
        reader = null
        if (leavingGallery) leaveReader() else setBrowsing(false)
    }

    fun leaveReader() {
        readerOpen = false
        reader = null
        if (fullGallery) setBrowsing(false) else {
            browsing = false
            disable()
        }
    }

    fun close() {
        disable()
        reader = null
        models.cancel()
        scope.cancel()
        completedFiles.clear()
        completedKeys.clear()
        completedNames.clear()
        retainedKeys.clear()
        if (ownsProvider && sourceStarted) provider.stop()
    }

    /** Restore current and one adjacent completed page without joining the inference queue. */
    private fun restoreReaderResults() {
        val observer = reader
        if (!enabled || !browsing || observer == null) {
            cancelResultLoader()
            return
        }
        val pages = listOf(current, current + readingDirection).filter {
            it >= 0 && it < size && it !in forced && activeRequests[it]?.force != true && completedFiles.containsKey(it) &&
                !observer.hasTranslatedPage(it)
        }
        if (resultLoader != null && pages == loadingPages) return
        cancelResultLoader()
        if (pages.isEmpty()) return
        val epoch = generation
        val files = pages.associateWith { completedFiles.getValue(it) }
        loadingPages = pages
        resultLoader = scope.launch(start = CoroutineStart.LAZY) {
            val loader = coroutineContext.job
            try {
                for ((page, file) in files) {
                    var bitmap: Bitmap? = null
                    var transferred = false
                    try {
                        withContext(Dispatchers.IO) {
                            // Assign inside IO: cancellation must not discard an owned bitmap
                            // while returning from withContext before the finally can recycle it.
                            bitmap = if (file.isFile) file.openInputStream().use { BitmapFactory.decodeStream(it) } else null
                            if (bitmap != null) TranslationResultStore.touch(file)
                        }
                        ensureActive()
                        if (generation != epoch || reader !== observer || !browsing ||
                            completedFiles[page] !== file) continue
                        val image = bitmap?.let { Image.create(it) }
                        if (image == null) forgetUnavailableResult(page, file)
                        else {
                            observer.display(page, image)
                            transferred = true
                            states[page] = imageState(file)
                            restorePages.remove(page)
                            changed()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        forgetUnavailableResult(page, file)
                    } finally {
                        if (!transferred) bitmap?.recycle()
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (resultLoader === loader) {
                        resultLoader = null
                        loadingPages = emptyList()
                        changed()
                        // A deleted/corrupt current result falls back to the normal source path.
                        enqueue(current, false)
                    }
                }
            }
        }
        resultLoader?.start()
    }

    private fun forgetUnavailableResult(page: Int, file: UniFile) {
        if (completedFiles[page] !== file) return
        completedFiles.remove(page)
        completedNames.remove(page)
        completedKeys.remove(page)?.let { retainedKeys.remove(it) }
        if (page == current) {
            states.remove(page)
            restorePages.remove(page)
        }
    }

    private fun cancelResultLoader() {
        resultLoader?.cancel()
        resultLoader = null
        loadingPages = emptyList()
    }

    fun invalidateResult(page: Int) {
        completedFiles.remove(page)
        completedNames.remove(page)
        completedKeys.remove(page)?.let { retainedKeys.remove(it) }
        restorePages.remove(page)
        cancelResultLoader()
    }

    fun clearRememberedResults() {
        completedFiles.clear()
        completedKeys.clear()
        completedNames.clear()
        retainedKeys.clear()
        restorePages.clear()
        cancelResultLoader()
    }

    private class YieldTranslationTurn : Exception()

    private fun changed() {
        reader?.changed()
        TranslationTasks.changed()
    }

    companion object {
        val imageStates = setOf(R.string.translation_done, R.string.translation_partial)
        private fun imageState(file: UniFile) = if (TranslationResultStore.isPartial(file)) R.string.translation_partial else R.string.translation_done
        val completedStates = setOf(R.string.translation_done, R.string.translation_failed,
            R.string.translation_partial, R.string.translation_models_required, R.string.translation_no_text, R.string.translation_animation)
        internal fun decodeBounded(file: File, backend: TranslationBackend = TranslationBackend.NATIVE_LLM): Bitmap {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = 1
            if (backend == TranslationBackend.ML_KIT) {
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            }
            return requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options))
        }
        private fun isAnimation(file: File): Boolean {
            val bytes = ByteArray(21)
            val n = file.inputStream().use { it.read(bytes) }
            return (n >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF") ||
                (n >= 21 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                    String(bytes, 12, 4, Charsets.US_ASCII) == "VP8X" && bytes[20].toInt() and 2 != 0)
        }
    }
}
