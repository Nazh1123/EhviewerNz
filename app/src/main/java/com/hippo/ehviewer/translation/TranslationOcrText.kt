package com.hippo.ehviewer.translation

/** Reserved dictionary tokens are not source text, even when OCR confidence is high. */
internal object TranslationOcrText {
    private val reserved = Regex("<(?:PAD|S|/S|SEP|UNK|UNUSED\\d+)>")

    fun clean(text: String): String = if (reserved.containsMatchIn(text)) ""
        else text.replace("<LF>", " ").trim()

    /** A lone foreign-script glyph among English dialogue is often artwork/SFX OCR. */
    fun nativePreservedCandidates(source: String, queries: List<String>): Set<Int> {
        val englishContext = source == "en" || source == TranslationLanguages.AUTO_SOURCE && run {
            val letters = queries.joinToString("").filter(Char::isLetter)
            letters.length >= 12 && letters.count(::latin) >= letters.length * .9f
        }
        if (!englishContext) return emptySet()
        return queries.indices.filterTo(linkedSetOf()) { index ->
            val letters = queries[index].filter(Char::isLetter)
            letters.length == 1 && !latin(letters.single()) && queries[index].none(Char::isDigit)
        }
    }

    private fun latin(char: Char) = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN
}
