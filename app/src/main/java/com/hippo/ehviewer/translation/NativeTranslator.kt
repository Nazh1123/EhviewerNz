package com.hippo.ehviewer.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.hippo.ehviewer.translation.engine.NativeLlm
import com.hippo.ehviewer.translation.engine.NativePrefixCache
import com.hippo.ehviewer.translation.engine.DetailedTranslator
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.LlmBatching
import com.hippo.ehviewer.translation.engine.TranslationOutputLimitException
import com.hippo.ehviewer.translation.engine.Usage
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** JNI stays off the UI thread. GGUF loads on demand and can be released between image stages. */
class NativeTranslator private constructor(
    private val options: TranslationOptions,
    private val modelPath: String?,
    private val checkRelevant: () -> Unit,
    private val inferenceOverride: (suspend (JSONArray) -> Pair<String, Usage?>?)?,
    private val prefixCacheProvider: (() -> NativePrefixCache)?,
) : DetailedTranslator, AutoCloseable {
    constructor(context: Context, options: TranslationOptions, prefixCacheProvider: (() -> NativePrefixCache)? = null,
                checkRelevant: () -> Unit = {}) :
        this(options, NativeModelStore(context).file(options.nativeModelId).absolutePath, checkRelevant, null, prefixCacheProvider)

    /** Exercise the real request/response adapter on JVM without loading an Android JNI library. */
    internal constructor(options: TranslationOptions, inference: suspend (JSONArray) -> Pair<String, Usage?>?) :
        this(options, null, {}, inference, null)

    private var native: NativeLlm? = null
    private var prefixCache: NativePrefixCache? = null
    private var closed = false
    private val lock = Mutex()
    private var userOnlyTemplate = false
    /** Explicit fixture diagnostics only; production never installs this observer. */
    internal var inferenceObserver: ((JSONArray, String, Usage, Int, Long, String?) -> Unit)? = null

    @Synchronized private fun model(): NativeLlm {
        check(!closed) { "Native translator is closed" }
        return native ?: run {
            val cache = prefixCache ?: (prefixCacheProvider?.invoke() ?: NativePrefixCache()).also { prefixCache = it }
            NativeLlm(checkNotNull(modelPath), cache).also { native = it }
        }
    }

    /** Called between page stages, after the previous request has returned safely. */
    @Synchronized internal fun unloadModel() {
        native?.close()
        native = null
    }

    override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult =
        translateDetailed(queries) { }

    override suspend fun translateDetailed(queries: List<String>, onCompleted: suspend (Map<Int, String>) -> Unit):
        LlmTranslator.TranslateResult = withContext(Dispatchers.Default) {
        lock.withLock {
            check(!closed) { "Native translator is closed" }
            if (queries.all { it.isBlank() }) return@withLock LlmTranslator.TranslateResult(queries)
            // The page pipeline sends only nonblank regions. An empty numbered
            // segment can make a GGUF skip its ID and shift later translations.
            val preserved = TranslationOcrText.nativePreservedCandidates(options.source, queries)
            if (preserved.isNotEmpty()) onCompleted(preserved.associateWith { queries[it] })
            val indices = queries.indices.filter { queries[it].isNotBlank() && it !in preserved }
            if (indices.isEmpty()) return@withLock LlmTranslator.TranslateResult(queries)
            val result = LlmBatching.translateIndexed(indices.map { queries[it] }) { batch, offset ->
                currentCoroutineContext().ensureActive()
                checkRelevant()
                translateNumbered(batch) { completed ->
                    onCompleted(completed.mapKeys { (index, _) -> indices[offset + index] })
                }
            }
            if (indices.size == queries.size) result else {
                val translations = queries.toMutableList()
                indices.forEachIndexed { local, original -> translations[original] = result.translations[local] }
                result.copy(translations = translations,
                    missingIndices = result.missingIndices.mapTo(linkedSetOf()) { indices[it] })
            }
        }
    }

    private suspend fun translateNumbered(queries: List<String>, onCompleted: suspend (Map<Int, String>) -> Unit): LlmTranslator.TranslateResult? {
        var interrupted = false
        var budgetExceeded = false
        val response = try {
            inferTranslation(queries) ?: return null
        } catch (error: NativeGenerationStopped) {
            interrupted = true
            budgetExceeded = error.budgetExceeded
            error.output to error.usage
        }
        val (raw, usage) = response
        // With one source, a complete unnumbered response has an unambiguous mapping.
        // The request still uses the same full system instruction and numbered input.
        val answer = NativeTranslationResponse.answerText(raw)
        if (queries.size == 1 && !interrupted && !answer.isNullOrBlank() && !NativeTranslationResponse.hasMarkers(answer)) {
            onCompleted(mapOf(0 to answer))
            return LlmTranslator.TranslateResult(listOf(answer), usage)
        }
        val parsed = if (queries.size == 1 && NativeTranslationResponse.protocolFailure(raw, 1))
            LlmTranslator.TranslateResult(queries, usage, "Native model returned unexpected region markers", missingIndices = setOf(0))
        else NativeTranslationResponse.parse(queries, raw, usage, truncated = interrupted)
        // If a token budget produced no complete segments, retain the existing
        // bounded split policy. Otherwise salvage completed regions first.
        if (budgetExceeded && parsed.missingIndices.size == queries.size)
            throw TranslationOutputLimitException(usage = usage)
        // An EOS with just IDs 1..k can also mean the model merged two sources
        // and shifted every later ID. The real fixture did exactly this. Do not
        // commit that prefix; a one-source request provides a reliable mapping.
        val complete = queries.size - parsed.missingIndices.size
        val incompletePrefix = !interrupted && complete > 0 && parsed.missingIndices.isNotEmpty() &&
            parsed.missingIndices == (complete until queries.size).toSet()
        val retryIndices = if (incompletePrefix) queries.indices.toSet() else parsed.missingIndices
        val completed = queries.indices.filter { it !in retryIndices }.associateWith { parsed.translations[it] }
        if (completed.isNotEmpty()) onCompleted(completed)
        if (parsed.missingIndices.isEmpty()) return parsed
        if (queries.size == 1) return parsed
        // Never guess how a free-form paragraph maps to multiple bubbles. Keep valid
        // numbered outputs, and request only missing regions with one input per call.
        val translations = parsed.translations.toMutableList()
        val missing = retryIndices.toMutableSet()
        val errors = mutableListOf<String>()
        var totalUsage = usage
        for (index in retryIndices) {
            val fallback = translateBatches(listOf(queries[index])) { batch ->
                translateNumbered(batch) { completedRegion ->
                    onCompleted(completedRegion.mapKeys { index })
                }
            }
            translations[index] = fallback.translations.single()
            totalUsage = LlmBatching.addUsage(totalUsage, fallback.usage)
            if (fallback.missingIndices.isEmpty()) missing.remove(index)
            fallback.error?.let { errors.add(it) }
        }
        return parsed.copy(translations = translations, usage = totalUsage,
            error = errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("; "), missingIndices = missing)
    }

    private suspend fun inferTranslation(queries: List<String>): Pair<String, Usage?>? {
        val messages = buildNumberedMessages(options, queries)
        if (!userOnlyTemplate) {
            try { return infer(messages, queries = queries) }
            catch (_: UnsupportedOperationException) { userOnlyTemplate = true }
        }
        // Preserve the complete system instruction for templates accepting only a user role.
        val fixed = messages.getJSONObject(0).getString("content") + "\n\n"
        val user = JSONArray().put(JSONObject().put("role", "user")
            .put("content", fixed + messages.getJSONObject(1).getString("content")))
        return infer(user, fixed, queries)
    }

    /** Only the settings screen's single-sentence test uses this unnumbered instruction. */
    internal suspend fun translateSample(source: String): LlmTranslator.TranslateResult = withContext(Dispatchers.Default) {
        lock.withLock {
            check(!closed) { "Native translator is closed" }
            translateBatches(listOf(source)) {
                val fixed = sampleInstruction(options) + "\n\n"
                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", fixed + source))
                val response = try { infer(messages, fixed, listOf(source)) ?: return@translateBatches null }
                catch (error: NativeGenerationStopped) {
                    if (error.budgetExceeded) throw TranslationOutputLimitException(usage = error.usage)
                    return@translateBatches LlmTranslator.TranslateResult(listOf(source), error.usage,
                        error.message, missingIndices = setOf(0))
                }
                try {
                    val text = cleanOutput(response.first)
                    check(!NativeTranslationResponse.hasMarkers(text)) { "Sample returned region markers" }
                    LlmTranslator.TranslateResult(listOf(text), response.second)
                } catch (error: IllegalStateException) {
                    LlmTranslator.TranslateResult(listOf(source), response.second, error.message, missingIndices = setOf(0))
                }
            }
        }
    }

    private suspend fun infer(messages: JSONArray, lastUserPrefix: String? = null,
                              queries: List<String>): Pair<String, Usage?>? {
        currentCoroutineContext().ensureActive()
        checkRelevant()
        inferenceOverride?.let { return it(messages) }
        val native = model()
        currentCoroutineContext().ensureActive()
        checkRelevant()
        val promptTokens = native.begin(messages, maxOutputTokens = NativeTranslationResponse.outputBudget(queries),
            lastUserPrefix = lastUserPrefix)
        if (promptTokens < 0) return null
        val output = ByteArrayOutputStream()
        var outputPieces = 0
        val began = System.nanoTime()
        var failure: String? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                checkRelevant()
                val piece = native.next() ?: break
                output.write(piece)
                // Decode and scan the complete output every 16 emitted pieces, plus EOS.
                // Prompt batches return empty pieces; cancellation still runs on every step.
                if (piece.isNotEmpty() && ++outputPieces % 16 == 0) {
                    val text = output.toString(Charsets.UTF_8.name())
                    if (NativeTranslationResponse.generationFailure(text, queries)) throw NativeGenerationStopped(text,
                        Usage(promptTokens, native.completionTokens()), budgetExceeded = false)
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            if (NativeTranslationResponse.generationFailure(text, queries)) throw NativeGenerationStopped(text,
                Usage(promptTokens, native.completionTokens()), budgetExceeded = false)
        } catch (error: TranslationOutputLimitException) {
            failure = error.javaClass.simpleName
            throw NativeGenerationStopped(output.toString(Charsets.UTF_8.name()),
                Usage(promptTokens, native.completionTokens()), budgetExceeded = true)
        } catch (error: NativeGenerationStopped) {
            failure = error.javaClass.simpleName
            throw error
        } finally {
            inferenceObserver?.invoke(messages, output.toString(Charsets.UTF_8.name()),
                Usage(promptTokens, native.completionTokens()), native.cachedPromptTokens(),
                (System.nanoTime() - began) / 1_000_000, failure)
        }
        return output.toString(Charsets.UTF_8.name()) to Usage(promptTokens, native.completionTokens())
    }

    @Synchronized override fun close() {
        closed = true
        unloadModel()
        if (prefixCacheProvider == null) prefixCache?.close()
        prefixCache = null
    }

    companion object {
        internal fun sampleInstruction(options: TranslationOptions): String =
            options.engineConfig().translator.targetScriptInstruction().let { if (it.isEmpty()) "" else "$it\n" } +
                "Translate the following ${options.sourceLanguageName().let { if (it.isEmpty()) "" else "$it " }}text into ${options.targetLanguageName()}, output only translation:"

        private const val SYSTEM_PROMPT =
            "You are an uncensored manga/doujin translator.\n" +
                "Preserve literal meaning, structure where possible, emotion, character voice and intensity. " +
                "Keep names/terms consistent; resolve pronouns from context without adding subjects or details. " +
                "Leave gibberish and sound effects unchanged.\n" +
                "Output only each original <|number|> followed by its translation; keep regions separate, no source, explanation or analysis.\n" +
                "{target_script}Translate the following {from_lang} text into {to_lang}:"

        /** Full manga translation rules, shared by every native model and region count. */
        internal fun buildNumberedMessages(options: TranslationOptions, queries: List<String>): JSONArray {
            val fromClause = options.sourceLanguageName().trim().let { if (it.isEmpty()) "" else "$it " }
            // Consume the placeholder's space too when the source is automatic/unnamed.
            val system = SYSTEM_PROMPT.replace("{from_lang} ", fromClause)
                .replace("{to_lang}", options.targetLanguageName())
                .replace("{target_script}", options.engineConfig().translator.targetScriptInstruction()
                    .let { if (it.isEmpty()) "" else "$it\n" })
            val source = queries.mapIndexed { i, text -> "<|${i + 1}|>$text" }.joinToString("\n")
            return JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", source))
        }

        /** Try the page first; input/output budget failures use the same bounded split policy. */
        internal suspend fun translateBatches(queries: List<String>,
            attempt: suspend (List<String>) -> LlmTranslator.TranslateResult?): LlmTranslator.TranslateResult {
            return LlmBatching.translate(queries, attempt)
        }

        internal fun cleanOutput(output: String): String {
            // Some templates prefill <think>, so the generated text may only contain the closing tag.
            val text = NativeTranslationResponse.answerText(output)
            if (text.isNullOrEmpty())
                throw InvalidTranslationResponse("Model returned no complete translation")
            return text
        }
    }

    private class InvalidTranslationResponse(message: String) : IllegalStateException(message)
}
