package com.hippo.ehviewer.translation

import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Usage

/** Strict local protocol. Never assign an ambiguous segment to a bubble. */
internal object NativeTranslationResponse {
    private val marker = Regex("""<\|[^>\r\n]*>|<\d+\s*[|>]+|[/／]\s*\d+\s*[>＞]|＜[|｜][^＞>\r\n]*[＞>]""")
    private val numeric = Regex("""<\|?(\d+)\s*[|>]+""")
    private val repetition = Regex("""(\S)\1{23,}|(\S{2,32}?)\2{11,}""")

    fun hasMarkers(text: String) = marker.containsMatchIn(text) || "<|" in text

    fun protocolFailure(text: String, count: Int): Boolean {
        val seen = hashSetOf<Int>()
        return marker.findAll(text).any {
            val id = numeric.matchEntire(it.value)?.groupValues?.get(1)?.toIntOrNull()
            id == null || id !in 1..count || !seen.add(id)
        }
    }

    fun generationFailure(text: String, queries: List<String>): Boolean =
        protocolFailure(text, queries.size) || repetition.findAll(text).any { repeated ->
            queries.none { repeated.value in it }
        }

    fun parse(queries: List<String>, raw: String, usage: Usage?, truncated: Boolean = false): LlmTranslator.TranslateResult {
        val text = raw.substringAfterLast("</think>").trim()
        val matches = if ("<think>" in text) emptyList() else marker.findAll(text).toList()
        val valid = mutableMapOf<Int, String>()
        val seen = hashSetOf<Int>()
        val ambiguous = hashSetOf<Int>()
        for ((position, match) in matches.withIndex()) {
            val id = numeric.matchEntire(match.value)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (id !in 1..queries.size) continue
            if (!seen.add(id)) ambiguous.add(id)
            val next = matches.getOrNull(position + 1)
            val segment = text.substring(match.range.last + 1, next?.range?.first ?: text.length).trim()
            // A malformed delimiter contaminates the preceding region. The final
            // segment of interrupted generation has no completion boundary.
            val malformedNext = next != null && numeric.matchEntire(next.value) == null
            if (segment.isNotBlank() && !hasMarkers(segment) && !malformedNext && !(truncated && next == null))
                valid[id] = segment
        }
        ambiguous.forEach { valid.remove(it) }
        val missing = queries.indices.filterTo(linkedSetOf()) { queries[it].isNotBlank() && it + 1 !in valid }
        return LlmTranslator.TranslateResult(queries.mapIndexed { index, source -> valid[index + 1] ?: source }, usage,
            error = if (missing.isEmpty()) null else "Native model returned invalid or missing region translations",
            missingIndices = missing)
    }

    /** Reserve proportionally; tiny regions must not generate 1024 tokens of drift. */
    fun outputBudget(queries: List<String>): Int =
        (64L + queries.size * 32L + queries.sumOf { it.length.toLong() } * 4L).coerceIn(192L, 1024L).toInt()
}

/** The final segment is incomplete; earlier unambiguous delimited segments may survive. */
internal class NativeGenerationStopped(
    val output: String,
    val usage: Usage,
    val budgetExceeded: Boolean,
) : IllegalStateException(if (budgetExceeded) "Native output budget exceeded" else "Native output protocol drifted")
