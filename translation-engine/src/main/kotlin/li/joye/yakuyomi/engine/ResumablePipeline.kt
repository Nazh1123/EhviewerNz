package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import kotlinx.coroutines.*

enum class TranslationStage(val weight: Int) {
    DETECT(10), OCR(25), TRANSLATE(45), INPAINT(15), RENDER(5),
}

/** Text-only checkpoint. Region signatures prevent reuse after OCR geometry/order changes. */
data class TranslationResume(val regions: List<String>, val translations: Map<Int, String>) {
    val missingCount: Int get() = regions.size - translations.size
}

/** Owns completed OCR/mask and optional clean background. Never owns the source or final page. */
class PreparedPage(
    val mask: Bitmap,
    val regions: List<TextRegion>,
    val lines: Int,
    val detectMs: Long,
    val ocrMs: Long,
) : AutoCloseable {
    var cleaned: Bitmap? = null
        internal set
    internal var translationIdentity: String? = null
    internal val translations = mutableMapOf<Int, String>()
    private fun regionSignatures() = regions.map { region ->
        region.lines.joinToString("\n") { "${it.quad}|${it.text}" }
    }

    fun translationResume(identity: String): TranslationResume? =
        if (translationIdentity == identity && regions.isNotEmpty())
            TranslationResume(regionSignatures(), translations.toMap()) else null

    fun restoreTranslations(identity: String, resume: TranslationResume): Boolean {
        if (resume.regions != regionSignatures() || resume.translations.any {
                it.key !in regions.indices || it.value.isBlank()
            }) return false
        if (translationIdentity != identity) translations.clear()
        translationIdentity = identity
        resume.translations.forEach { (index, text) -> translations.putIfAbsent(index, text) }
        return true
    }
    val byteCount: Long get() = mask.allocationByteCount.toLong() +
        (cleaned?.allocationByteCount ?: 0) + regions.sumOf { it.sourceText.length.toLong() * 2 + 1024 } +
        translations.values.sumOf { it.length.toLong() * 2 }

    override fun close() {
        cleaned?.takeIf { it !== mask }?.recycle()
        mask.recycle()
        cleaned = null
        translations.clear()
        translationIdentity = null
    }
}

/** App adapter over the pinned upstream stages; completed preprocessing survives a page change. */
class ResumablePipeline(
    private val detect: (Bitmap) -> Detection,
    private val recognize: suspend (Bitmap, List<TextLine>) -> Unit,
    private val inpaint: suspend (Bitmap, List<TextRegion>, Bitmap) -> Bitmap,
    private val translator: Translator,
    private val cfg: EngineConfig,
    private val release: () -> Unit,
    private val warm: () -> Unit,
    private val translationIdentity: String? = null,
    private val overlapInpainting: Boolean = true,
    // ML Kit submits one region; LLMs keep the upstream whole-page request boundary.
    private val translationBatchSize: Int = Int.MAX_VALUE,
    private val retainAnalysis: Boolean = true,
    // Sequential local mode overlaps inpainting with vector text layout.
    private val overlapLayout: Boolean = false,
    // Native production chooses once per page; a visibility change cannot alter
    // the ownership of work already running. Other backends use the fixed flags.
    private val selectInpaintingOverlap: (() -> Boolean)? = null,
    private val afterPreparedPage: () -> Unit = {},
) : TranslationEngine {
    suspend fun prepare(page: Bitmap,
                        onProgress: suspend (TranslationStage, Float) -> Unit = { _, _ -> },
                        checkRelevant: () -> Unit = {}): PreparedPage {
        // JNI stages must return before their buffers can be released, but that
        // does not authorize starting the next model after the owner was cancelled.
        checkRelevant()
        val start = System.currentTimeMillis()
        EngineTrace.log("resume.detect.enter")
        val detection = detect(page)
        EngineTrace.log("resume.detect.exit lines=${detection.lines.size}")
        val detectMs = System.currentTimeMillis() - start
        try {
            onProgress(TranslationStage.DETECT, 1f)
            checkRelevant()
            val ocrStart = System.currentTimeMillis()
            EngineTrace.log("resume.ocr.enter")
            if (detection.lines.isNotEmpty()) recognize(page, detection.lines)
            EngineTrace.log("resume.ocr.exit")
            val regions = Grouping.group(detection.lines).filter { it.sourceText.isNotBlank() }
            val ocrMs = System.currentTimeMillis() - ocrStart
            onProgress(TranslationStage.OCR, 1f)
            return PreparedPage(detection.textMask, regions, detection.lines.size, detectMs, ocrMs)
        } catch (error: Throwable) {
            detection.textMask.recycle()
            throw error
        }
    }

    /** Caller retains [prepared]; even failed/interrupted translation keeps completed inpainting. */
    suspend fun translatePrepared(page: Bitmap, prepared: PreparedPage, reused: Boolean,
                                  onProgress: suspend (TranslationStage, Float) -> Unit = { _, _ -> },
                                  isRelevant: () -> Boolean = { true }): PageResult = coroutineScope {
        val start = System.currentTimeMillis()
        val detectMs = if (reused) 0L else prepared.detectMs
        val ocrMs = if (reused) 0L else prepared.ocrMs
        var translateMs = 0L
        var inpaintMs = 0L
        var promptTokens = 0
        var completionTokens = 0
        var translationError: String? = null
        fun stats(kept: Int = 0, renderMs: Long = 0) = PageStats(prepared.lines, prepared.regions.size, kept,
            detectMs, ocrMs, translateMs, inpaintMs, renderMs,
            System.currentTimeMillis() - start + detectMs + ocrMs, promptTokens, completionTokens)
        if (reused) {
            onProgress(TranslationStage.DETECT, 1f)
            onProgress(TranslationStage.OCR, 1f)
        }
        if (prepared.regions.isEmpty()) return@coroutineScope PageResult.Skipped("No OCR text", stats())
        if (!isRelevant()) return@coroutineScope PageResult.Failed("Page superseded")
        prepared.regions.forEach { it.translatedText = "" }
        val overlapInpainting = selectInpaintingOverlap?.invoke() ?: this@ResumablePipeline.overlapInpainting
        val overlapLayout = if (selectInpaintingOverlap != null) !overlapInpainting else this@ResumablePipeline.overlapLayout

        // Retain the output before leaving the native stage. Never cancel away a completed bitmap.
        val background = async(
            // Image JNI is blocking. Give it a worker even when the translator
            // also blocks its caller, so overlap means actual concurrent work.
            context = Dispatchers.Default,
            start = if (overlapLayout) CoroutineStart.LAZY else CoroutineStart.DEFAULT,
        ) {
            runCatching {
                if (prepared.cleaned == null && isRelevant()) withContext(NonCancellable) {
                    val t = System.currentTimeMillis()
                    EngineTrace.log("resume.inpaint.enter")
                    prepared.cleaned = inpaint(page, prepared.regions, prepared.mask)
                    EngineTrace.log("resume.inpaint.exit")
                    inpaintMs = System.currentTimeMillis() - t
                }
                if (prepared.cleaned != null) onProgress(TranslationStage.INPAINT, 1f)
            }
        }
        var rendered: Bitmap? = null
        var analysisMask: Bitmap? = null
        var resultReady = false
        try {
            // Serial image work finishes before inference. Layout overlap instead
            // keeps the image job lazy until inference has completed; the caller
            // independently controls model residency at each stage boundary.
            if (!overlapInpainting && !overlapLayout) background.await().getOrThrow()
            val t = System.currentTimeMillis()
            EngineTrace.log("resume.translate.enter reused=$reused")
            if (prepared.translationIdentity != translationIdentity || translationIdentity == null) {
                prepared.translations.clear()
                prepared.translationIdentity = translationIdentity
            }
            val translations = prepared.regions.indices.map { prepared.translations[it] ?: "" }.toMutableList()
            val pending = prepared.regions.indices.filter { it !in prepared.translations }
            var completed = prepared.regions.size - pending.size
            if (completed > 0) onProgress(TranslationStage.TRANSLATE, completed.toFloat() / prepared.regions.size)
            require(translationBatchSize > 0) { "Translation batch size must be positive" }
            for (batch in pending.chunked(translationBatchSize.coerceAtMost(pending.size.coerceAtLeast(1)))) {
                if (!isRelevant()) throw IllegalStateException("Page superseded")
                val queries = batch.map { prepared.regions[it].sourceText }
                val result = when (val selected = translator) {
                    is DetailedTranslator -> selected.translateDetailed(queries)
                    is LlmTranslator -> selected.translateDetailed(queries)
                    else -> LlmTranslator.TranslateResult(selected.translate(queries))
                }
                require(result.translations.size == batch.size) { "Incomplete translation" }
                promptTokens += result.usage?.promptTokens ?: 0
                completionTokens += result.usage?.completionTokens ?: 0
                translationError = result.error ?: translationError
                // Commit the entire completed request before allowing a scheduler yield.
                // Missing LLM output falls back to source, but must remain retryable.
                batch.forEachIndexed { offset, index ->
                    val text = result.translations[offset]
                    translations[index] = text
                    if (translationIdentity != null && text.isNotBlank() && offset !in result.missingIndices &&
                        (result.error == null || result.missingIndices.isNotEmpty() || text != queries[offset]))
                        prepared.translations[index] = text
                }
                completed += batch.size
                onProgress(TranslationStage.TRANSLATE, completed.toFloat() / prepared.regions.size)
            }
            EngineTrace.log("resume.translate.exit")
            translateMs = System.currentTimeMillis() - t
            onProgress(TranslationStage.TRANSLATE, 1f)
            if (!overlapLayout) background.await().getOrThrow()
            if (!isRelevant()) return@coroutineScope PageResult.Failed("Page superseded")
            require(translations.size == prepared.regions.size) { "Incomplete translation" }
            prepared.regions.forEachIndexed { index, region -> region.translatedText = translations[index] }
            val kept = TextFilter.apply(prepared.regions, cfg.translator.filterText).toSet()
            EngineTrace.log("resume.translate.result kept=${kept.size} error=$translationError")
            if (kept.isEmpty()) return@coroutineScope if (translationError != null)
                PageResult.Failed("translate: $translationError")
            else PageResult.Skipped("No translated text", stats())
            prepared.regions.forEach { if (it !in kept) it.translatedText = it.sourceText }
            var layoutMs = 0L
            val layout = if (overlapLayout) {
                background.start()
                val pendingLayout = async(Dispatchers.Default) {
                    runCatching {
                        val layoutStart = System.currentTimeMillis()
                        EngineTrace.log("resume.layout.enter")
                        Renderer.prepareLayout(prepared.regions, cfg.render).also {
                            layoutMs = System.currentTimeMillis() - layoutStart
                            EngineTrace.log("resume.layout.exit")
                        }
                    }
                }
                val ready = pendingLayout.await().getOrThrow()
                background.await().getOrThrow()
                if (!isRelevant()) return@coroutineScope PageResult.Failed("Page superseded")
                ready
            } else null
            val renderStart = System.currentTimeMillis()
            EngineTrace.log("resume.render.enter")
            val output = if (layout != null) Renderer.compose(checkNotNull(prepared.cleaned), layout)
                else Renderer.render(checkNotNull(prepared.cleaned), prepared.regions, cfg.render)
            rendered = output
            EngineTrace.log("resume.render.exit")
            val renderMs = layoutMs + System.currentTimeMillis() - renderStart
            onProgress(TranslationStage.RENDER, 1f)
            // A result owns its analysis independently of the preparation cache. Readers
            // which only consume the final bitmap can opt out of this allocation.
            val analysis = if (retainAnalysis) PageAnalysis(
                prepared.mask.copy(prepared.mask.config ?: Bitmap.Config.ARGB_8888, false).also { analysisMask = it },
                prepared.regions.map { region ->
                    val lines = region.lines.map { line ->
                        TextLine(line.quad.toList(), line.score).also {
                            it.direction = line.direction
                            it.text = line.text
                            it.translatedText = line.translatedText
                        }
                    }
                    TextRegion(lines, region.direction, region.angle, region.cx, region.cy, region.boxW, region.boxH).also {
                        it.translatedText = region.translatedText
                        it.onArt = region.onArt
                        it.dbgStd = region.dbgStd
                        it.dbgWhite = region.dbgWhite
                    }
                },
            ) else null
            PageResult.Translated(output, stats(kept.size, renderMs), analysis).also {
                resultReady = true
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // Transport errors are sanitized by the app; do not include prompts or raw responses.
            EngineTrace.log("resume.page.failed ${error.javaClass.simpleName}: ${error.message}")
            PageResult.Failed("${error.javaClass.simpleName}: ${error.message ?: "Translation failed"}")
        } finally {
            var cleanupCompleted = false
            try {
                // join() starts a LAZY job: cancel unstarted inpainting on failed/empty
                // translation instead of doing work for a page that cannot be rendered.
                if (overlapLayout && !background.isActive && !background.isCompleted) background.cancel()
                withContext(NonCancellable) {
                    try { background.join() } finally { afterPreparedPage() }
                }
                // Cancellation during native disposal must not abandon an output
                // that the cancelled page can no longer deliver to its caller.
                currentCoroutineContext().ensureActive()
                cleanupCompleted = true
            } finally {
                // Ownership transfers only if model cleanup also succeeds. Otherwise
                // the caller never receives the result and cannot recycle its bitmaps.
                if (!resultReady || !cleanupCompleted) {
                    rendered?.recycle()
                    analysisMask?.recycle()
                }
            }
        }
    }

    override suspend fun translatePage(page: Bitmap): PageResult = prepare(page).use {
        translatePrepared(page, it, reused = false)
    }
    override fun warmUp() = warm()
    override fun close() = release()
}
