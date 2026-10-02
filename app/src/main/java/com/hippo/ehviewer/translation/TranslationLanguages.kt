package com.hippo.ehviewer.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** OCR is shared by every backend; selecting a translator does not replace its model. */
object TranslationLanguages {
    const val SOURCE = "ja"
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
        else -> Locale.forLanguageTag(code).getDisplayLanguage(locale)
    }

    fun promptName(code: String): String = when (code) {
        "zh", "zh-CN", "zh-Hans" -> "Simplified Chinese"
        "zh-HK" -> "Traditional Chinese (Hong Kong)"
        "zh-TW" -> "Traditional Chinese (Taiwan)"
        "zh-Hant" -> "Traditional Chinese"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    }

    fun validTarget(options: TranslationOptions): Boolean =
        options.target in targets(options.backend)
}
