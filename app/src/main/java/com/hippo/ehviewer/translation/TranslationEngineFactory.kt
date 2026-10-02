package com.hippo.ehviewer.translation

import android.content.Context
import li.joye.yakuyomi.engine.*

/** Shared image stages with backend-specific request and model-memory policies. */
object TranslationEngineFactory {
    fun create(context: Context, models: ModelSet, options: TranslationOptions,
               translator: Translator, beforeImageStage: () -> Unit = {},
               retainNativeModels: () -> Boolean = { false }): ResumablePipeline {
        val config = options.engineConfig()
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val batchSize = if (options.backend == TranslationBackend.ML_KIT) 1 else Int.MAX_VALUE
        if (options.backend == TranslationBackend.NATIVE_LLM) {
            val detector = TranslationStageModel { Detector(requireNotNull(models.detectorNcnn), config.detector) }
            val ocr = TranslationStageModel { Ocr(models.ocr, alphabet, config.ocr) }
            val inpainter = TranslationStageModel { Inpainter(requireNotNull(models.aotInpainterNcnn), config.inpainter) }
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
                    try { ocr.get().recognize(page, lines) } finally { policy.afterImage() }
                },
                inpaint = { page, regions, mask ->
                    policy.beforeImage()
                    try { inpainter.get().inpaint(page, regions, mask) } finally { policy.afterImage() }
                },
                translator = guarded, cfg = config, release = ::releaseImages, warm = {},
                translationIdentity = options.cacheIdentity(),
                translationBatchSize = batchSize, retainAnalysis = false,
                selectInpaintingOverlap = policy::beginPage, afterPreparedPage = policy::endPage,
            )
        }
        val detector = Detector(requireNotNull(models.detectorNcnn), config.detector)
        var ocr: Ocr? = null
        try {
            ocr = Ocr(models.ocr, alphabet, config.ocr)
            val inpainter = Inpainter(requireNotNull(models.aotInpainterNcnn), config.inpainter)
            val recognizer = ocr
            return ResumablePipeline(detector::detect, { page, lines -> recognizer.recognize(page, lines) },
                inpainter::inpaint, translator, config,
                release = { runCatching { detector.close() }; runCatching { recognizer.close() }; runCatching { inpainter.close() } },
                warm = { detector.warmUp(); recognizer.warmUp(); inpainter.warmUp() },
                translationIdentity = options.cacheIdentity(), translationBatchSize = batchSize, retainAnalysis = false)
        } catch (error: Throwable) {
            ocr?.close()
            detector.close()
            throw error
        }
    }
}
