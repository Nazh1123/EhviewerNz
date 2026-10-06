package com.hippo.ehviewer.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

internal data class SourceLanguageGuess(val language: String, val confidence: Float)

/** Bundled language ID runs locally; text is never sent to a translator just to classify it. */
internal object OcrSourceLanguageIdentifier {
    suspend fun identify(text: String): SourceLanguageGuess? {
        if (text.count(Char::isLetter) < 12) return null
        return LanguageIdentification.getClient().use { identifier ->
            select(text, identifier.identifyPossibleLanguages(text).await().map {
                SourceLanguageGuess(it.languageTag, it.confidence)
            })
        }
    }

    internal fun select(text: String, candidates: List<SourceLanguageGuess>): SourceLanguageGuess? {
        val letters = text.filter(Char::isLetter)
        if (letters.length < 12) return null
        val ranked = candidates.sortedByDescending { it.confidence }
        val best = ranked.firstOrNull() ?: return null
        if (best.language !in TranslationLanguages.detectedSources || !best.confidence.isFinite() ||
            best.confidence < .8f || best.confidence - (ranked.getOrNull(1)?.confidence ?: 0f) < .2f) return null
        fun kana(c: Char) = c in '\u3041'..'\u3096' || c in '\u30a1'..'\u30fa'
        fun hangul(c: Char) = c in '\uac00'..'\ud7a3' || c in '\u1100'..'\u11ff' || c in '\u3131'..'\u318e'
        fun han(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        val kanaCount = letters.count(::kana)
        val hangulCount = letters.count(::hangul)
        val hanCount = letters.count(::han)
        val latinCount = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        // Guard against Latin SFX dominating the model, and against mixed Japanese/Korean pages.
        val consistent = when (best.language) {
            "ja" -> kanaCount >= 2 && hangulCount == 0 && (kanaCount + hanCount) >= letters.length * .6
            "ko" -> hangulCount >= 6 && kanaCount == 0 && hangulCount >= letters.length * .6
            "zh" -> hanCount >= 12 && kanaCount == 0 && hangulCount == 0 && hanCount >= letters.length * .8
            "en" -> letters.length >= 24 && kanaCount == 0 && hangulCount == 0 && hanCount == 0 &&
                latinCount >= letters.length * .9
            else -> false
        }
        return best.takeIf { consistent }
    }
}

/** A decision for the current gallery task only; never persisted or restored. */
internal class GallerySourceLanguage(
    private val identify: suspend (String) -> SourceLanguageGuess? = OcrSourceLanguageIdentifier::identify,
) {
    @Volatile var language: String? = null
        private set
    private val lock = Mutex()

    fun options(configured: TranslationOptions): TranslationOptions =
        if (configured.source == TranslationLanguages.AUTO_SOURCE && language != null) configured.copy(source = language!!)
        else configured

    suspend fun observe(configured: TranslationOptions, texts: List<String>, checkRelevant: () -> Unit = {}): String? {
        // Manual selection is authoritative: do not classify OCR or emit a detection decision.
        if (configured.source != TranslationLanguages.AUTO_SOURCE) return null
        return lock.withLock {
            if (language != null) return@withLock null
            val text = texts.map(TranslationOcrText::clean).filter(String::isNotBlank).joinToString("\n").take(2048)
            if (text.isBlank()) return@withLock null
            checkRelevant()
            val guess = try { identify(text) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            checkRelevant()
            if (guess == null || guess.language !in TranslationLanguages.detectedSources || !guess.confidence.isFinite() ||
                guess.confidence !in .8f..1f)
                return@withLock null
            language = guess.language
            guess.language
        }
    }
}
