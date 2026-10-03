package com.hippo.ehviewer.translation

/** Reserved dictionary tokens are not source text, even when OCR confidence is high. */
internal object TranslationOcrText {
    private val reserved = Regex("<(?:PAD|S|/S|SEP|UNK|UNUSED\\d+)>")

    fun clean(text: String): String = if (reserved.containsMatchIn(text)) ""
        else text.replace("<LF>", " ").trim()
}
