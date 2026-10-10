package com.hippo.ehviewer.translation

import android.content.Context
import androidx.core.content.edit
import com.hippo.ehviewer.translation.engine.EngineConfig
import com.hippo.ehviewer.translation.engine.OcrConfig
import com.hippo.ehviewer.translation.engine.TranslatorConfig
import com.hippo.ehviewer.translation.engine.InpainterConfig
import okhttp3.HttpUrl

enum class TranslationBackend { NATIVE_LLM, ML_KIT, LLM_API }

data class TranslationOptions(
    val target: String = "zh-CN", val inpaint: Boolean = true, val ahead: Int = 2,
    val backend: TranslationBackend = TranslationBackend.NATIVE_LLM,
    val apiUrl: String = "http://127.0.0.1:8080/v1/chat/completions",
    val apiModel: String = "", val apiKey: String = "",
    val persistDownloaded: Boolean = false, val cacheSizeMb: Int = 256,
    val nativeModelId: String = "", val nativeModelName: String = "",
    val source: String = TranslationLanguages.DEFAULT_SOURCE,
) {
    val cacheLimitBytes: Long get() = cacheSizeMb * 1024L * 1024L
    val inputImageIdentity: String get() =
        if (backend == TranslationBackend.ML_KIT) "max-edge-2048" else "original-size"
    val pageConcurrency: Int get() = if (backend == TranslationBackend.LLM_API)
        8
    else 1

    fun preparationIdentity(): String = engineConfig().let {
        listOf("ehnz-preparation-v9-legacy-grouping-tiles", "reader-image-v2", inputImageIdentity, source,
            // Line concurrency changes scheduling, not recognized text or coordinates.
            // Keep preprocessing reusable when switching to the native memory policy.
            it.detector.toString(), it.ocr.copy(concurrency = OcrConfig().concurrency).toString(),
            it.inpainter.toString()).joinToString("\n")
    }

    fun cacheIdentity(): String = listOf("ehnz-overlay-v9-legacy-grouping-tiles", "reader-image-v2",
        when (backend) {
            TranslationBackend.NATIVE_LLM -> "llama-jni-v16-thinking-checkpoints-prefix-kv\n$nativeModelId"
            TranslationBackend.ML_KIT -> "mlkit-17.0.3-multilingual"
            TranslationBackend.LLM_API -> "llm-api-v5-reader-regions\n${apiUrl.trim()}\n${apiModel.trim()}"
        },
        "$source\n$target" + if (backend != TranslationBackend.ML_KIT &&
            targetLanguageName().startsWith("Traditional Chinese")) "\ntraditional-script-v1" else "",
        inpaint.toString(), inputImageIdentity).joinToString("\n")

    /** Only the previous sparse Japanese overlays are compatible; older full-page PNGs are not. */
    internal fun legacyOverlayIdentity(): String? = if (source != "ja") null else
        listOf("ehnz-overlay-v1", "981ae85617bb3323949d57b7d6e3e10181435325",
            when (backend) {
                TranslationBackend.NATIVE_LLM -> "llama-jni-v11-japanese-source-strict-regions-prefix-kv\n$nativeModelId"
                TranslationBackend.ML_KIT -> "mlkit-17.0.3-ja"
                TranslationBackend.LLM_API -> "llm-api-v3-segments-981ae856\n${apiUrl.trim()}\n${apiModel.trim()}"
            },
            if (backend == TranslationBackend.ML_KIT) target else "ja\n$target",
            inpaint.toString(), inputImageIdentity).joinToString("\n")

    internal fun legacyFullPageIdentity(): String? = legacyOverlayIdentity()
        ?.replaceFirst("ehnz-overlay-v1", "ehnz-offline-v1")

    fun validApiUrl(): Boolean {
        val url = HttpUrl.parse(apiUrl.trim()) ?: return false
        return url.username().isEmpty() && url.password().isEmpty() && url.fragment() == null
    }

    fun targetLanguageName() = TranslationLanguages.promptName(target)

    fun sourceLanguageName() = if (source == "zh") "Chinese" else TranslationLanguages.promptName(source)

    val mlKitSource: String get() = TranslationLanguages.mlKitSource(source)

    fun sampleText() = TranslationLanguages.sampleText(source)

    /** An unsupported saved target must never reach ML Kit's client/model APIs. */
    fun withBackend(backend: TranslationBackend): TranslationOptions {
        val mappedTarget = if (backend == TranslationBackend.ML_KIT) {
            if (target == "zh-CN") "zh" else target
        } else TranslationLanguages.llmTarget(target)
        val updated = copy(backend = backend, target = mappedTarget,
            source = source.takeIf { it in TranslationLanguages.sources } ?: TranslationLanguages.DEFAULT_SOURCE)
        return if (TranslationLanguages.validTarget(updated)) updated
            else updated.copy(target = if (backend == TranslationBackend.ML_KIT) "zh" else "zh-CN")
    }

    fun engineConfig() = EngineConfig(
        ocr = OcrConfig(),
        inpainter = InpainterConfig(method = if (inpaint) "aot" else "boxfill"),
        translator = TranslatorConfig(fromLangName = sourceLanguageName(), toLangName = targetLanguageName()),
    )
}

class TranslationSettings(context: Context) {
    companion object {
        val CACHE_SIZES_MB = listOf(128, 256, 512, 768, 1024)
    }
    private val prefs = context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE)

    /** Called under the inference lock after validating a model. */
    internal fun selectNativeModel(id: String, name: String) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        check(prefs.edit().putString("native_model_id", id).putString("native_model_name", name).commit()) {
            "Cannot save model selection"
        }
    }

    internal fun clearNativeModel() {
        val previous = read()
        check(prefs.edit().putString("result_model_id", previous.nativeModelId)
            .putString("result_model_name", previous.nativeModelName)
            .remove("native_model_id").remove("native_model_name").commit()) {
            "Cannot clear model selection"
        }
    }

    /** Keep the deleted selection's cache identity without presenting it as an installed model. */
    internal fun readForResults(): TranslationOptions = read().let { options ->
        val id = prefs.getString("result_model_id", "").orEmpty()
        if (options.backend == TranslationBackend.NATIVE_LLM && options.nativeModelId.isEmpty() &&
            id.matches(Regex("[a-f0-9]{64}"))) options.copy(nativeModelId = id,
                nativeModelName = prefs.getString("result_model_name", "").orEmpty())
        else options
    }

    fun read() = TranslationOptions(prefs.getString("target", "zh") ?: "zh", prefs.getBoolean("inpaint", true),
        prefs.getInt("ahead", 2).coerceIn(0, 10),
        TranslationBackend.entries.firstOrNull { it.name == prefs.getString("backend", null) }
            ?: TranslationBackend.NATIVE_LLM,
        prefs.getString("api_url", null) ?: TranslationOptions().apiUrl,
        prefs.getString("api_model", "") ?: "", prefs.getString("api_key", "") ?: "",
        prefs.getBoolean("persist_downloaded", false),
        prefs.getInt("cache_size_mb", 256).takeIf { it in CACHE_SIZES_MB } ?: 256,
        prefs.getString("native_model_id", "") ?: "", prefs.getString("native_model_name", "") ?: "",
        // Ignore legacy prompt-only source values; opt in through the new source selector.
        prefs.getString("source_language", null) ?: TranslationLanguages.DEFAULT_SOURCE).let { it.withBackend(it.backend) }
    fun save(options: TranslationOptions) {
        require(TranslationLanguages.validTarget(options))
        require(TranslationLanguages.validSource(options))
        require(options.ahead in 0..10)
        require(options.validApiUrl())
        require(options.cacheSizeMb in CACHE_SIZES_MB)
        require(options.nativeModelId.isEmpty() || options.nativeModelId.matches(Regex("[a-f0-9]{64}")))
        prefs.edit {
            putString("target", options.target); putBoolean("inpaint", options.inpaint); putInt("ahead", options.ahead)
            putString("backend", options.backend.name); putString("api_url", options.apiUrl.trim())
            putString("api_model", options.apiModel.trim()); putString("api_key", options.apiKey.trim())
            putBoolean("persist_downloaded", options.persistDownloaded); putInt("cache_size_mb", options.cacheSizeMb)
            putString("native_model_id", options.nativeModelId); putString("native_model_name", options.nativeModelName)
            putString("source_language", options.source)
            // Remove the obsolete prompt-only preference instead of reviving its values.
            remove("source")
        }
    }
}
