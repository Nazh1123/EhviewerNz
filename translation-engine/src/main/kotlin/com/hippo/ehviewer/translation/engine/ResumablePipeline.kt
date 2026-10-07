package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import kotlinx.coroutines.*

enum class TranslationStage(val weight: Int) { DETECT(10), OCR(25), TRANSLATE(45), INPAINT(15), RENDER(5) }

data class TranslationResume(val regions: List<String>, val translations: Map<Int, String>) {
    val missingCount: Int get() = regions.size - translations.size
}

class PreparedPage(val mask: Bitmap, val regions: List<TextRegion>, val lines: Int,
                   val detectMs: Long, val ocrMs: Long) : AutoCloseable {
    var cleaned: Bitmap? = null
        internal set
    internal var identity: String? = null
    internal val completed = mutableMapOf<Int, String>()
    private fun signatures() = regions.map { region ->
        "${region.sourceSeparator}|" + region.lines.joinToString("\n") { "${it.quad}|${it.text}" }
    }
    fun translationResume(identity: String): TranslationResume? =
        if (this.identity == identity && regions.isNotEmpty()) TranslationResume(signatures(), completed.toMap()) else null

    fun restoreTranslations(identity: String, resume: TranslationResume): Boolean {
        if (resume.regions != signatures() || resume.translations.any { it.key !in regions.indices || it.value.isBlank() }) return false
        if (this.identity != identity) completed.clear()
        this.identity = identity
        for ((index, text) in resume.translations) completed.putIfAbsent(index, text)
        return true
    }
    val byteCount: Long get() = mask.allocationByteCount.toLong() + (cleaned?.allocationByteCount ?: 0) +
        regions.sumOf { 1024 + it.sourceText.length.toLong() * 2 } + completed.values.sumOf { it.length.toLong() * 2 }

    override fun close() {
        cleaned?.takeUnless { it === mask }?.recycle()
        cleaned = null
        mask.recycle()
        completed.clear()
        identity = null
    }
}

/** A prepared page survives navigation; only the final output is transferred to the reader. */
class ResumablePipeline(
    private val detect: suspend (Bitmap) -> Detection,
    private val recognize: suspend (Bitmap, List<TextLine>) -> Unit,
    private val inpaint: suspend (Bitmap, List<TextRegion>, Bitmap) -> Bitmap,
    private val translator: Translator,
    private val cfg: EngineConfig,
    private val release: () -> Unit,
    private val warm: () -> Unit,
    private val translationIdentity: String? = null,
    private val overlapInpainting: Boolean = true,
    private val translationBatchSize: Int = Int.MAX_VALUE,
    private val overlapLayout: Boolean = false,
    private val selectInpaintingOverlap: (() -> Boolean)? = null,
    private val afterPreparedPage: () -> Unit = {},
    private val sourceSeparator: String = "",
    private val sourceSeparatorProvider: () -> String = { sourceSeparator },
    private val translationIdentityProvider: () -> String? = { translationIdentity },
) : TranslationEngine {
    private class Metrics(val prepared: PreparedPage, reused: Boolean) {
        val start = System.nanoTime()
        val detect = if (reused) 0L else prepared.detectMs
        val ocr = if (reused) 0L else prepared.ocrMs
        var translate = 0L; var inpaint = 0L; var usage: Usage? = null; var error: String? = null
        fun result(kept: Int = 0, render: Long = 0) = PageStats(prepared.lines, prepared.regions.size, kept,
            detect, ocr, translate, inpaint, render, elapsed(start) + detect + ocr,
            usage?.promptTokens ?: 0, usage?.completionTokens ?: 0)
    }

    suspend fun prepare(page: Bitmap, onProgress: suspend (TranslationStage, Float) -> Unit = { _, _ -> },
                        checkRelevant: () -> Unit = {}): PreparedPage {
        checkRelevant()
        val start = System.nanoTime()
        EngineTrace.log("resume.detect.enter")
        val detected = detect(page)
        val detectMs = elapsed(start)
        try {
            onProgress(TranslationStage.DETECT, 1f)
            checkRelevant()
            val ocrStart = System.nanoTime()
            if (detected.lines.isNotEmpty()) recognize(page, detected.lines)
            val regions = Grouping.group(detected.lines, sourceSeparatorProvider())
                .filter { region -> region.lines.all { it.text.isNotBlank() } }
            val ocrMs = elapsed(ocrStart)
            onProgress(TranslationStage.OCR, 1f)
            return PreparedPage(detected.textMask, regions, detected.lines.size, detectMs, ocrMs)
        } catch (error: Throwable) { detected.textMask.recycle(); throw error }
    }

    suspend fun translatePrepared(page: Bitmap, prepared: PreparedPage, reused: Boolean,
        onProgress: suspend (TranslationStage, Float) -> Unit = { _, _ -> },
        isRelevant: () -> Boolean = { true }): PageResult = coroutineScope {
        val metrics = Metrics(prepared, reused)
        if (reused) {
            onProgress(TranslationStage.DETECT, 1f); onProgress(TranslationStage.OCR, 1f)
        }
        if (prepared.regions.isEmpty()) return@coroutineScope PageResult.Skipped("No OCR text", metrics.result())
        if (!isRelevant()) return@coroutineScope PageResult.Failed("Page superseded")
        prepared.regions.forEach { it.translatedText = "" }
        val parallelImage = selectInpaintingOverlap?.invoke() ?: overlapInpainting
        val parallelLayout = if (selectInpaintingOverlap == null) overlapLayout else !parallelImage
        val image = async(Dispatchers.Default, start = if (parallelLayout) CoroutineStart.LAZY else CoroutineStart.DEFAULT) {
            runCatching {
                if (prepared.cleaned == null && isRelevant()) withContext(NonCancellable) {
                    val started = System.nanoTime()
                    prepared.cleaned = inpaint(page, prepared.regions, prepared.mask)
                    metrics.inpaint = elapsed(started)
                }
                if (prepared.cleaned != null) onProgress(TranslationStage.INPAINT, 1f)
            }
        }
        var output: Bitmap? = null
        var delivered = false
        var cleanupAttempted = false
        try {
            if (!parallelImage && !parallelLayout) image.await().getOrThrow()
            val translatedAt = System.nanoTime()
            val texts = translateMissing(prepared, metrics, onProgress, isRelevant)
            metrics.translate = elapsed(translatedAt)
            onProgress(TranslationStage.TRANSLATE, 1f)
            if (!parallelLayout) image.await().getOrThrow()
            if (!isRelevant()) return@coroutineScope PageResult.Failed("Page superseded")
            val preservedRegions = ArrayList<TextRegion>()
            val translatedRegions = ArrayList<TextRegion>()
            for ((index, region) in prepared.regions.withIndex()) {
                val target = texts[index].trim()
                if (target.isEmpty() || target.all(Char::isDigit) || target.equals(region.sourceText.trim(), true))
                    preservedRegions.add(region)
                else translatedRegions.add(region)
            }
            val count = translatedRegions.size
            if (count == 0) return@coroutineScope if (metrics.error == null)
                PageResult.Skipped("No translated text", metrics.result()) else PageResult.Failed("translate: ${metrics.error}")
            for (index in prepared.regions.indices) prepared.regions[index].translatedText =
                texts[index].takeIf(String::isNotBlank) ?: prepared.regions[index].sourceText
            var layoutTime = 0L
            val layout = if (parallelLayout) {
                image.start()
                val textLayout = async(Dispatchers.Default) {
                    runCatching {
                        val start = System.nanoTime()
                        Renderer.prepareLayout(translatedRegions, cfg.render).also {
                            layoutTime = elapsed(start)
                            EngineTrace.log("resume.layout.exit")
                        }
                    }
                }
                val ready = textLayout.await().getOrThrow()
                image.await().getOrThrow()
                if (!isRelevant()) return@coroutineScope PageResult.Failed("Page superseded")
                ready
            } else null
            val renderAt = System.nanoTime()
            val preservationPadding = cfg.inpainter.regionPad + cfg.inpainter.maskRadius
            val rendered = if (layout == null) Renderer.render(checkNotNull(prepared.cleaned), translatedRegions, cfg.render,
                original = page, preservedRegions = preservedRegions, padding = preservationPadding)
                else Renderer.compose(checkNotNull(prepared.cleaned), layout, page, preservedRegions, preservationPadding)
            output = rendered
            val renderMs = elapsed(renderAt) + layoutTime
            onProgress(TranslationStage.RENDER, 1f)
            val result = PageResult.Translated(rendered, metrics.result(count, renderMs))
            cleanupAttempted = true
            withContext(NonCancellable) { image.join(); afterPreparedPage() }
            currentCoroutineContext().ensureActive()
            delivered = true
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PageResult.Failed("${error.javaClass.simpleName}: ${error.message ?: "Translation failed"}")
        } finally {
            if (!delivered) {
                if (!image.isActive && !image.isCompleted) image.cancel()
                try { withContext(NonCancellable) { image.join(); if (!cleanupAttempted) afterPreparedPage() } }
                finally { output?.recycle() }
            }
        }
    }

    private suspend fun translateMissing(prepared: PreparedPage, metrics: Metrics,
        report: suspend (TranslationStage, Float) -> Unit, relevant: () -> Boolean): List<String> {
        val identity = translationIdentityProvider()
        if (identity == null || identity != prepared.identity) prepared.completed.clear()
        prepared.identity = identity
        val targets = prepared.regions.indices.map { prepared.completed[it].orEmpty() }.toMutableList()
        val missing = prepared.regions.indices.filter { it !in prepared.completed }
        var completed = targets.size - missing.size
        if (completed > 0) report(TranslationStage.TRANSLATE, completed.toFloat() / targets.size)
        require(translationBatchSize > 0)
        for (indices in missing.chunked(minOf(translationBatchSize, missing.size.coerceAtLeast(1)))) {
            check(relevant()) { "Page superseded" }
            val input = indices.map { prepared.regions[it].sourceText }
            val checkpointed = hashSetOf<Int>()
            val result = if (translator is DetailedTranslator) translator.translateDetailed(input) { checkpoint ->
                require(checkpoint.all { (offset, text) -> offset in input.indices && text.isNotBlank() })
                for ((offset, text) in checkpoint) {
                    val index = indices[offset]
                    targets[index] = text
                    if (identity != null) prepared.completed[index] = text
                }
                if (checkpointed.addAll(checkpoint.keys))
                    report(TranslationStage.TRANSLATE, (completed + checkpointed.size).toFloat() / targets.size)
            }
                else LlmTranslator.TranslateResult(translator.translate(input))
            require(result.translations.size == input.size) { "Incomplete translation" }
            metrics.usage = LlmBatching.addUsage(metrics.usage, result.usage)
            metrics.error = result.error ?: metrics.error
            for ((offset, index) in indices.withIndex()) {
                val text = result.translations[offset]
                targets[index] = text
                if (identity != null && text.isNotBlank() && offset !in result.missingIndices &&
                    (result.error == null || result.missingIndices.isNotEmpty() || text != input[offset])) prepared.completed[index] = text
            }
            completed += indices.size
            report(TranslationStage.TRANSLATE, completed.toFloat() / targets.size)
        }
        return targets
    }

    override suspend fun translatePage(page: Bitmap): PageResult = prepare(page).use { translatePrepared(page, it, false) }
    override fun warmUp() = warm()
    override fun close() = release()
    companion object { private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000 }
}
