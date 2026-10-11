package com.hippo.ehviewer.translation

import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.Usage
import org.json.JSONArray
import org.json.JSONObject

/** One settings sample has an unambiguous destination, unlike a page of regions. */
internal object LlmSampleTranslation {
    private val fence = Regex("""\A```[^\r\n]*\r?\n([\s\S]*?)\r?\n?```\z""")

    fun instruction(options: TranslationOptions): String =
        options.engineConfig().translator.targetScriptInstruction().let { if (it.isEmpty()) "" else "$it\n" } +
            "Translate the following ${options.sourceLanguageName().let { if (it.isEmpty()) "" else "$it " }}text into ${options.targetLanguageName()}, output only translation:"

    fun messages(options: TranslationOptions, source: String): JSONArray = JSONArray().put(JSONObject()
        .put("role", "user").put("content", instruction(options) + "\n\n" + source))

    fun parse(source: String, raw: String, usage: Usage?): LlmTranslator.TranslateResult {
        fun failure(message: String) = LlmTranslator.TranslateResult(listOf(source), usage, message, setOf(0))
        var answer = NativeTranslationResponse.answerText(raw)
        if (answer.isNullOrBlank()) return failure("Model returned no complete translation")
        if (answer.startsWith("```")) {
            answer = fence.matchEntire(answer)?.groupValues?.get(1)?.trim()
            if (answer.isNullOrBlank()) return failure("Model returned no complete translation")
        }
        if (!NativeTranslationResponse.hasMarkers(answer))
            return LlmTranslator.TranslateResult(listOf(answer), usage)
        // Some services retain a single region marker despite the plain sample instruction.
        // Never turn wrong, duplicated or incomplete IDs into a successful sample.
        val parsed = NativeTranslationResponse.parse(listOf(source), answer, usage)
        return if (NativeTranslationResponse.protocolFailure(answer, 1) || parsed.error != null)
            failure("Model returned invalid sample region markers") else parsed
    }
}
