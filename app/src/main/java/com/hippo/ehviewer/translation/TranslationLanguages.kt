package com.hippo.ehviewer.translation

import android.content.Context
import com.hippo.ehviewer.R
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

    /** Use the same localized Chinese target labels as settings, independent of platform Locale data. */
    fun targetDisplayName(code: String, context: Context): String {
        val label = when (llmTarget(code)) {
            "zh-CN" -> R.string.translation_target_zh_cn
            "zh-HK" -> R.string.translation_target_zh_hk
            "zh-TW" -> R.string.translation_target_zh_tw
            else -> null
        }
        return label?.let(context::getString)
            ?: displayName(code, context.resources.configuration.locales[0])
    }

    internal fun sourceDetectedMessage(context: Context, source: String, target: String): String =
        context.getString(R.string.translation_source_detected,
            displayName(source, context.resources.configuration.locales[0]), targetDisplayName(target, context))

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

    private fun usesTraditionalChinese(locale: Locale): Boolean = when (locale.script) {
        "Hant" -> true
        "Hans" -> false
        else -> locale.country in setOf("TW", "HK", "MO")
    }

    /** Preserve Chinese script/region; getDisplayLanguage alone collapses both to Chinese. */
    fun promptName(code: String): String {
        if (code == AUTO_SOURCE) return ""
        val locale = Locale.forLanguageTag(code)
        if (locale.language != "zh") return locale.getDisplayLanguage(Locale.ENGLISH)
        if (!usesTraditionalChinese(locale)) return "Simplified Chinese"
        return when (locale.country) {
            "HK" -> "Traditional Chinese (Hong Kong)"
            "TW" -> "Traditional Chinese (Taiwan)"
            "MO" -> "Traditional Chinese (Macau)"
            else -> "Traditional Chinese"
        }
    }

    /** Restore script-qualified and differently cased tags into the supported LLM presets. */
    fun llmTarget(code: String): String {
        val locale = Locale.forLanguageTag(code)
        if (locale.language != "zh") return code
        if (!usesTraditionalChinese(locale)) return "zh-CN"
        return if (locale.country == "HK") "zh-HK" else "zh-TW"
    }

    fun validTarget(options: TranslationOptions): Boolean =
        options.target in targets(options.backend)

    fun validSource(options: TranslationOptions): Boolean = options.source in sources
}
