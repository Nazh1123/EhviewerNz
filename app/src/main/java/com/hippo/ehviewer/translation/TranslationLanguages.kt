package com.hippo.ehviewer.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Source presets covered by the desktop OCR probe; targets depend on the translator. */
object TranslationLanguages {
    const val DEFAULT_SOURCE = "ja"
    const val AUTO_SOURCE = "auto"
    val manualSources = listOf("ja", "en", "zh-CN", "zh-TW", "ko")
    val sources = listOf(AUTO_SOURCE) + manualSources
    // Automatic identification reports Chinese without inventing a script/region.
    val detectedSources = setOf("ja", "en", "zh", "ko")
    // Translation presets are independent of the app's interface language preferences.
    val llmTargets = listOf("de", "en", "es", "fr", "ja", "ko", "th", "zh-CN", "zh-HK", "zh-TW")

    // Read the installed SDK's capabilities, rather than maintaining a second ML Kit list.
    val mlKitTargets: List<String> by lazy {
        val preferred = listOf("zh", "en", "ko", "ja")
        TranslateLanguage.getAllLanguages().sortedWith(compareBy<String> {
            preferred.indexOf(it).takeIf { index -> index >= 0 } ?: preferred.size
        }.thenBy { it })
    }

    /** These are prompt presets, not a guarantee about an arbitrary GGUF or API model. */
    fun targets(backend: TranslationBackend): List<String> =
        if (backend == TranslationBackend.ML_KIT) mlKitTargets
        else llmTargets

    fun displayName(code: String, locale: Locale): String = when (code) {
        "zh" -> Locale.forLanguageTag("zh-Hans").getDisplayName(locale)
        "zh-CN" -> Locale.forLanguageTag("zh-Hans").getDisplayName(locale)
        "zh-TW" -> Locale.forLanguageTag("zh-Hant").getDisplayName(locale)
        else -> Locale.forLanguageTag(code).getDisplayLanguage(locale)
    }

    /** ML Kit has one Chinese model; keep the selected script in app settings/prompts. */
    fun mlKitSource(source: String): String = when (source) {
        "zh-CN", "zh-TW" -> "zh"
        else -> source
    }

    fun lineSeparator(source: String): String = if (source in setOf("en", "ko", AUTO_SOURCE)) " " else ""

    fun sampleText(source: String): String = when (source) {
        AUTO_SOURCE -> "I will go to school tomorrow."
        "en" -> "I will go to school tomorrow."
        "zh-CN" -> "明天我要去学校。"
        "zh-TW" -> "明天我要去學校。"
        "ko" -> "내일 학교에 갈 거예요."
        else -> "明日は学校へ行きます。"
    }

    fun promptName(code: String): String = when (code) {
        AUTO_SOURCE -> ""
        "zh", "zh-CN", "zh-Hans" -> "Simplified Chinese"
        "zh-HK" -> "Traditional Chinese (Hong Kong)"
        "zh-TW" -> "Traditional Chinese (Taiwan)"
        "zh-Hant" -> "Traditional Chinese"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    }

    fun validTarget(options: TranslationOptions): Boolean =
        options.target in targets(options.backend)

    fun validSource(options: TranslationOptions): Boolean = options.source in sources
}
