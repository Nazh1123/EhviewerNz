package com.hippo.ehviewer.translation

import android.content.Context
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared image stages with backend-specific request and model-memory policies. */
object TranslationEngineFactory {
    // Every local AI model receives the same numbered page request and system instruction.
    internal fun translationBatchSize(options: TranslationOptions): Int =
        if (options.backend == TranslationBackend.ML_KIT) 1 else Int.MAX_VALUE

    fun create(context: Context, models: ModelSet, options: TranslationOptions,
               translator: Translator, beforeImageStage: () -> Unit = {},
               retainNativeModels: () -> Boolean = { false },
               onRecognized: suspend (List<TextLine>) -> Unit = {},
               resolvedOptions: () -> TranslationOptions = { options }): ResumablePipeline {
        val configured = options.engineConfig()
        // API pages may overlap network requests, but OCR must leave CPU for the reader.
        val config = configured.copy(ocr = configured.ocr.copy(concurrency = configured.ocr.concurrency.coerceIn(1, 4)))
        val ppModels = PpOcrModels(context)
        val alphabet by lazy { context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() } }
        val batchSize = translationBatchSize(options)
        val sourceSeparator = TranslationLanguages.lineSeparator(options.source)
        suspend fun detectPage(detector: Detector, page: android.graphics.Bitmap): Detection {
            val coroutine = currentCoroutineContext()
            return detector.detect(page) {
                coroutine.ensureActive()
                coroutine[TranslationPageRequest]?.ensureRelevant()
            }
        }
        fun recognizer(): PageOcr = LanguageOcr({ resolvedOptions().source }, onRecognized) { key ->
            if (key == "ja") Ocr(models.ocr, alphabet, config.ocr) else {
                if (!ppModels.ready(key)) throw MissingPpOcrModel()
                val dictionary = org.json.JSONArray(context.assets.open("ppocr-$key.json").bufferedReader().use { it.readText() })
                PpOcr(ppModels.path(key), (0 until dictionary.length()).map { dictionary.getString(it) }, config.ocr)
            }
        }
        if (options.backend == TranslationBackend.NATIVE_LLM) {
            val detector = TranslationStageModel { Detector(models.detectorNcnn, config.detector) }
            val ocr = TranslationStageModel(::recognizer)
            val inpainter = TranslationStageModel { Inpainter(models.aotInpainterNcnn, config.inpainter) }
            fun releaseImages() {
                try { detector.close() } finally { try { ocr.close() } finally { inpainter.close() } }
            }
            val policy = NativePageModelPolicy(retainNativeModels, ::releaseImages, beforeImageStage)
            val guarded = object : DetailedTranslator {
                override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult {
                    return translateDetailed(queries) { }
                }
                override suspend fun translateDetailed(queries: List<String>, onCompleted: suspend (Map<Int, String>) -> Unit): LlmTranslator.TranslateResult {
                    policy.languageBoundary()
                    return try {
                        if (translator is DetailedTranslator) translator.translateDetailed(queries, onCompleted)
                        else LlmTranslator.TranslateResult(translator.translate(queries))
                    } finally { policy.languageBoundary() }
                }
            }
            return ResumablePipeline(
                detect = { page ->
                    policy.beforeImage()
                    try { detectPage(detector.get(), page) } finally { policy.afterImage() }
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
                translationBatchSize = batchSize,
                selectInpaintingOverlap = policy::beginPage, afterPreparedPage = policy::endPage,
                sourceSeparator = sourceSeparator,
                sourceSeparatorProvider = { TranslationLanguages.lineSeparator(resolvedOptions().source) },
                translationIdentityProvider = { resolvedOptions().cacheIdentity() },
            )
        }
        val detector = Detector(models.detectorNcnn, config.detector)
        var ocr: PageOcr? = null
        try {
            ocr = recognizer()
            val inpainter = Inpainter(models.aotInpainterNcnn, config.inpainter)
            val recognizer = ocr
            return ResumablePipeline({ page -> detectPage(detector, page) }, recognizer::recognize,
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
