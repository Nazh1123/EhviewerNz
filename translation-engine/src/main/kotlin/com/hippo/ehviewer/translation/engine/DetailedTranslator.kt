package com.hippo.ehviewer.translation.engine


interface DetailedTranslator : Translator {
    suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult

    override suspend fun translate(queries: List<String>): List<String> = translateDetailed(queries).translations
}
