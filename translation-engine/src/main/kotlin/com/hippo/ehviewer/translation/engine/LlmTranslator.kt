package com.hippo.ehviewer.translation.engine

import org.json.JSONArray
import org.json.JSONObject

data class Usage(val promptTokens: Int, val completionTokens: Int)

/** The reader owns networking and cancellation; this component owns region identities. */
class LlmTranslator(
    private val cfg: TranslatorConfig = TranslatorConfig(),
    private val transport: (suspend (JSONArray) -> Pair<String, Usage?>)? = null,
) : DetailedTranslator {
    data class TranslateResult(val translations: List<String>, val usage: Usage? = null,
        val error: String? = null, val missingIndices: Set<Int> = emptySet())

    override suspend fun translateDetailed(queries: List<String>): TranslateResult {
        if (queries.isEmpty()) return TranslateResult(emptyList())
        val response = checkNotNull(transport) { "A translation transport is required" }(buildMessages(queries))
        return parseResponse(queries, response.first, response.second)
    }

    fun buildMessages(queries: List<String>): JSONArray {
        val source = cfg.fromLangName.trim().let { if (it.isEmpty()) "" else "$it " }
        val instruction = "Translate the following ${source}text into ${cfg.toLangName}. " +
            "These are dialogue and captions from a comic. Preserve meaning, tone, names and sound effects. " +
            "Use context to resolve pronouns and keep terminology consistent. " +
            "Each input region starts with <|number|>. Return every region with the same marker, " +
            "followed only by its translation. Do not merge regions, repeat the source, add explanations or use code fences."
        return JSONArray().put(message("system", instruction))
            .put(message("user", queries.mapIndexed { i, text -> "<|${i + 1}|>$text" }.joinToString("\n")))
    }

    fun parseResponse(queries: List<String>, raw: String, usage: Usage? = null): TranslateResult {
        val content = raw.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .trim().removeSuffix("```").trimEnd()
        val headers = marker.findAll(content).toList()
        val responses = arrayOfNulls<String>(queries.size)
        val occurrences = IntArray(queries.size)
        for (i in headers.indices) {
            val index = headers[i].groupValues[1].toIntOrNull()?.minus(1) ?: continue
            if (index !in responses.indices) continue
            occurrences[index]++
            val value = content.substring(headers[i].range.last + 1,
                headers.getOrNull(i + 1)?.range?.first ?: content.length).trim()
            if (value.isNotEmpty() && responses[index] == null) responses[index] = value
        }
        for (index in responses.indices) if (occurrences[index] > 1) responses[index] = null
        val missing = queries.indices.filterTo(linkedSetOf()) { queries[it].isNotBlank() && responses[it] == null }
        return TranslateResult(queries.mapIndexed { index, original ->
            if (original.isBlank()) original else responses[index] ?: original
        }, usage, if (missing.isEmpty()) null else "Parsed ${queries.size - missing.size}/${queries.size} regions", missing)
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
    private val marker = Regex("<\\|?(\\d+)\\s*\\|?>\\|?[\\t ]*")
}
