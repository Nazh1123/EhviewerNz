package com.hippo.ehviewer.translation

import android.content.Context
import com.hippo.ehviewer.translation.engine.*

/** Shared image stages with backend-specific request and model-memory policies. */
object TranslationEngineFactory {
    // Save each finished HY region immediately: page cancellation must not discard
    // seconds of completed local inference. Numbered/API models still batch a page.
    internal fun translationBatchSize(options: TranslationOptions): Int =
        if (options.backend == TranslationBackend.ML_KIT || options.backend == TranslationBackend.NATIVE_LLM &&
            NativeModelCatalog.usesPlainRequests(options.nativeModelId)) 1 else Int.MAX_VALUE

    fun create(context: Context, models: ModelSet, options: TranslationOptions,
               translator: Translator, beforeImageStage: () -> Unit = {},
               retainNativeModels: () -> Boolean = { false },
               onRecognized: suspend (List<TextLine>) -> Unit = {},
               resolvedOptions: () -> TranslationOptions = { options }): ResumablePipeline {
        val configured = options.engineConfig()
        // API pages may overlap network requests, but OCR must leave CPU for the reader.
        val config = configured.copy(ocr = configured.ocr.copy(concurrency = configured.ocr.concurrency.coerceIn(1, 4)))
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val batchSize = translationBatchSize(options)
        val sourceSeparator = TranslationLanguages.lineSeparator(options.source)
        suspend fun recognize(ocr: Ocr, page: android.graphics.Bitmap, lines: List<TextLine>) {
            recognizeTranslationBatches(lines, if (config.ocr.concurrent) config.ocr.concurrency else 1) {
                ocr.recognize(page, it)
            }
            lines.forEach { it.text = TranslationOcrText.clean(it.text) }
            onRecognized(lines)
        }
        if (options.backend == TranslationBackend.NATIVE_LLM) {
            val detector = TranslationStageModel { Detector(models.detectorNcnn, config.detector) }
            val ocr = TranslationStageModel { Ocr(models.ocr, alphabet, config.ocr) }
            val inpainter = TranslationStageModel { Inpainter(models.aotInpainterNcnn, config.inpainter) }
            fun releaseImages() {
                try { detector.close() } finally { try { ocr.close() } finally { inpainter.close() } }
            }
            val policy = NativePageModelPolicy(retainNativeModels, ::releaseImages, beforeImageStage)
            val guarded = object : DetailedTranslator {
                override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult {
                    policy.languageBoundary()
                    return try {
                        if (translator is DetailedTranslator) translator.translateDetailed(queries)
                        else LlmTranslator.TranslateResult(translator.translate(queries))
                    } finally { policy.languageBoundary() }
                }
            }
            return ResumablePipeline(
                detect = { page ->
                    policy.beforeImage()
                    try { detector.get().detect(page) } finally { policy.afterImage() }
                },
                recognize = { page, lines ->
                    policy.beforeImage()
                    try { recognize(ocr.get(), page, lines) } finally { policy.afterImage() }
                },
                inpaint = { page, regions, mask ->
                    policy.beforeImage()
                    try { inpainter.get().inpaint(page, regions, mask) } finally { policy.afterImage() }
                },
                translator = guarded, cfg = config, release = ::releaseImages, warm = {},
                translationIdentity = options.cacheIdentity(),
                translationBatchSize = batchSize,
                selectInpaintingOverlap = policy::beginPage, afterPreparedPage = policy::endPage,
                sourceSeparator = sourceSeparator,
                sourceSeparatorProvider = { TranslationLanguages.lineSeparator(resolvedOptions().source) },
                translationIdentityProvider = { resolvedOptions().cacheIdentity() },
            )
        }
        val detector = Detector(models.detectorNcnn, config.detector)
        var ocr: Ocr? = null
        try {
            ocr = Ocr(models.ocr, alphabet, config.ocr)
            val inpainter = Inpainter(models.aotInpainterNcnn, config.inpainter)
            val recognizer = ocr
            return ResumablePipeline(detector::detect, { page, lines -> recognize(recognizer, page, lines) },
                inpainter::inpaint, translator, config,
                release = { runCatching { detector.close() }; runCatching { recognizer.close() }; runCatching { inpainter.close() } },
                warm = { detector.warmUp(); recognizer.warmUp(); inpainter.warmUp() },
                translationIdentity = options.cacheIdentity(), translationBatchSize = batchSize,
                sourceSeparator = sourceSeparator,
                sourceSeparatorProvider = { TranslationLanguages.lineSeparator(resolvedOptions().source) },
                translationIdentityProvider = { resolvedOptions().cacheIdentity() })
        } catch (error: Throwable) {
            ocr?.close()
            detector.close()
            throw error
        }
    }
}
