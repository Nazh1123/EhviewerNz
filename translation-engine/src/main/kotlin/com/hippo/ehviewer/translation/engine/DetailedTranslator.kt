package com.hippo.ehviewer.translation.engine


interface DetailedTranslator : Translator {
    suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult

    /** Optional checkpoints, indexed into this request. Publish only final, unambiguous
     * results, serially, before starting another inference. The callback may suspend or cancel. */
    suspend fun translateDetailed(queries: List<String>, onCompleted: suspend (Map<Int, String>) -> Unit):
        LlmTranslator.TranslateResult = translateDetailed(queries)

    override suspend fun translate(queries: List<String>): List<String> = translateDetailed(queries).translations
}
