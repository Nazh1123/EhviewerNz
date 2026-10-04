package com.hippo.ehviewer.translation.engine


object LlmBatching {
    suspend fun translate(
        queries: List<String>,
        attempt: suspend (List<String>) -> LlmTranslator.TranslateResult?,
    ): LlmTranslator.TranslateResult = translateIndexed(queries) { batch, _ -> attempt(batch) }

    /** Offset stays relative to the original request through every context/budget split. */
    suspend fun translateIndexed(queries: List<String>,
        attempt: suspend (List<String>, Int) -> LlmTranslator.TranslateResult?): LlmTranslator.TranslateResult =
        translateAt(queries, 0, attempt)

    private suspend fun translateAt(queries: List<String>, offset: Int,
        attempt: suspend (List<String>, Int) -> LlmTranslator.TranslateResult?): LlmTranslator.TranslateResult {
        if (queries.all { it.isBlank() }) return LlmTranslator.TranslateResult(queries)
        var exhausted: TranslationOutputLimitException? = null
        try {
            attempt(queries, offset)?.let { return it }
        } catch (error: TranslationOutputLimitException) {
            exhausted = error
        }
        val result = if (queries.size == 1) {
            LlmTranslator.TranslateResult(queries,
                error = if (exhausted == null) "One region exceeds the model context" else "One region exceeds the output budget",
                missingIndices = setOf(0))
        } else {
            val middle = queries.size / 2
            val first = translateAt(queries.take(middle), offset, attempt)
            val second = translateAt(queries.drop(middle), offset + middle, attempt)
            merge(first, second)
        }
        return result.copy(usage = addUsage(exhausted?.usage, result.usage))
    }

    fun merge(first: LlmTranslator.TranslateResult, second: LlmTranslator.TranslateResult) =
        LlmTranslator.TranslateResult(
            first.translations + second.translations,
            addUsage(first.usage, second.usage),
            listOfNotNull(first.error, second.error).distinct().takeIf { it.isNotEmpty() }?.joinToString("; "),
            missingIndices = first.missingIndices + second.missingIndices.map { it + first.translations.size },
        )

    fun addUsage(first: Usage?, second: Usage?): Usage? = when {
        first == null -> second
        second == null -> first
        else -> Usage(first.promptTokens + second.promptTokens, first.completionTokens + second.completionTokens)
    }
}
