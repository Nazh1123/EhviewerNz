package li.joye.yakuyomi.engine

/** Keep the upstream page request; reduce it only after an actual input/output budget rejection. */
object LlmBatching {
    suspend fun translate(
        queries: List<String>,
        attempt: suspend (List<String>) -> LlmTranslator.TranslateResult?,
    ): LlmTranslator.TranslateResult {
        if (queries.all { it.isBlank() }) return LlmTranslator.TranslateResult(queries)
        var exhausted: TranslationOutputLimitException? = null
        try {
            attempt(queries)?.let { return it }
        } catch (error: TranslationOutputLimitException) {
            exhausted = error
        }
        val result = if (queries.size == 1) {
            // Like upstream missing IDs, an untranslatable block keeps its source.
            // Its failure must not discard other blocks in a split page.
            LlmTranslator.TranslateResult(queries,
                error = if (exhausted == null) "One region exceeds the model context" else "One region exceeds the output budget",
                missingIndices = setOf(0))
        } else {
            val middle = queries.size / 2
            val first = translate(queries.take(middle), attempt)
            val second = translate(queries.drop(middle), attempt)
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
