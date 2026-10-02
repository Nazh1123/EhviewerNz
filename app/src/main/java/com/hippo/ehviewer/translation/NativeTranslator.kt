package com.hippo.ehviewer.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.NativeLlm
import li.joye.yakuyomi.engine.NativePrefixCache
import li.joye.yakuyomi.engine.DetailedTranslator
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.LlmBatching
import li.joye.yakuyomi.engine.TranslationOutputLimitException
import li.joye.yakuyomi.engine.Usage
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
    private var preferPlainRequests = false
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

    override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult = withContext(Dispatchers.Default) {
        lock.withLock {
            check(!closed) { "Native translator is closed" }
            if (queries.all { it.isBlank() }) return@withLock LlmTranslator.TranslateResult(queries)
            // Upstream Pipeline sends only nonblank regions. An empty numbered
            // segment can make a GGUF skip its ID and shift later translations.
            val indices = queries.indices.filter { queries[it].isNotBlank() }
            val result = translateBatches(indices.map { queries[it] }) { batch ->
                currentCoroutineContext().ensureActive()
                checkRelevant()
                // A translation-only GGUF may emit plain text even when asked for IDs.
                // Single-region requests have an unambiguous mapping and use its original prompt.
                if (preferPlainRequests || batch.count { it.isNotBlank() } == 1) translatePlain(batch)
                else translateNumbered(batch)
            }
            if (indices.size == queries.size) result else {
                val translations = queries.toMutableList()
                indices.forEachIndexed { local, original -> translations[original] = result.translations[local] }
                result.copy(translations = translations,
                    missingIndices = result.missingIndices.mapTo(linkedSetOf()) { indices[it] })
            }
        }
    }

    private suspend fun translateNumbered(queries: List<String>): LlmTranslator.TranslateResult? {
        var interrupted = false
        var budgetExceeded = false
        val response = try {
            infer(buildNumberedMessages(options, queries), queries = queries) ?: return null
        } catch (error: NativeGenerationStopped) {
            interrupted = true
            budgetExceeded = error.budgetExceeded
            error.output to error.usage
        } catch (_: UnsupportedOperationException) {
            // The model was validated with a user-only template; system roles are optional.
            preferPlainRequests = true
            return translatePlain(queries)
        }
        val (raw, usage) = response
        val parsed = NativeTranslationResponse.parse(queries, raw, usage, truncated = interrupted)
        if (parsed.missingIndices.isEmpty()) return parsed
        // If a token budget produced no complete segments, retain the existing
        // bounded split policy. Otherwise salvage completed regions first.
        if (budgetExceeded && parsed.missingIndices.size == queries.size)
            throw TranslationOutputLimitException(usage = usage)
        // An EOS with just IDs 1..k can also mean the model merged two sources
        // and shifted every later ID. The real fixture did exactly this. Do not
        // commit that prefix; a one-source request provides a reliable mapping.
        val complete = queries.size - parsed.missingIndices.size
        val incompletePrefix = !interrupted && complete > 0 &&
            parsed.missingIndices == (complete until queries.size).toSet()
        val retryIndices = if (incompletePrefix) queries.indices.toSet() else parsed.missingIndices
        if (retryIndices.size == queries.size) preferPlainRequests = true
        // Never guess how a free-form paragraph maps to multiple bubbles. Keep valid
        // numbered outputs, and request only missing regions with one input per call.
        val translations = parsed.translations.toMutableList()
        val missing = retryIndices.toMutableSet()
        val errors = mutableListOf<String>()
        var totalUsage = usage
        for (index in retryIndices) {
            val fallback = translatePlain(listOf(queries[index]))
            translations[index] = fallback.translations.single()
            totalUsage = LlmBatching.addUsage(totalUsage, fallback.usage)
            if (fallback.missingIndices.isEmpty()) missing.remove(index)
            fallback.error?.let { errors.add(it) }
        }
        return parsed.copy(translations = translations, usage = totalUsage,
            error = errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("; "), missingIndices = missing)
    }

    private suspend fun translatePlain(queries: List<String>): LlmTranslator.TranslateResult {
        val translations = mutableListOf<String>()
        val missing = linkedSetOf<Int>()
        val errors = linkedSetOf<String>()
        var usage: Usage? = null
        for ((index, source) in queries.withIndex()) {
            val region = LlmBatching.translate(listOf(source)) {
                val fromClause = options.sourceLanguageName().let { if (it.isEmpty()) "" else "$it " }
                val instruction = if (options.targetLanguageName() == "Simplified Chinese")
                    "将以下文本翻译为简体中文，注意只需要输出翻译后的结果，不要额外解释："
                else "Translate the following ${fromClause}segment into ${options.targetLanguageName()}, without additional explanation."
                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "$instruction\n\n$source"))
                val response = try {
                    infer(messages, "$instruction\n\n", listOf(source)) ?: return@translate null
                } catch (error: NativeGenerationStopped) {
                    if (error.budgetExceeded) throw TranslationOutputLimitException(usage = error.usage)
                    return@translate LlmTranslator.TranslateResult(listOf(source), error.usage,
                        error.message, missingIndices = setOf(0))
                }
                val (raw, tokens) = response
                try {
                    val text = cleanOutput(raw)
                    if (NativeTranslationResponse.hasMarkers(text)) {
                        if (NativeTranslationResponse.protocolFailure(text, 1))
                            throw InvalidTranslationResponse("Native model returned unexpected region markers")
                        NativeTranslationResponse.parse(listOf(source), text, tokens)
                    } else LlmTranslator.TranslateResult(listOf(text), tokens)
                } catch (error: InvalidTranslationResponse) {
                    LlmTranslator.TranslateResult(listOf(source), tokens, error.message, missingIndices = setOf(0))
                }
            }
            translations.add(region.translations.single())
            if (region.missingIndices.isNotEmpty()) missing.add(index)
            region.error?.let { errors.add(it) }
            usage = LlmBatching.addUsage(usage, region.usage)
        }
        return LlmTranslator.TranslateResult(translations, usage,
            errors.takeIf { it.isNotEmpty() }?.joinToString("; "), missingIndices = missing)
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
        val began = System.nanoTime()
        var failure: String? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                checkRelevant()
                val piece = native.next() ?: break
                output.write(piece)
                if (piece.isNotEmpty() && NativeTranslationResponse.generationFailure(
                        output.toString(Charsets.UTF_8.name()), queries)) {
                    throw NativeGenerationStopped(output.toString(Charsets.UTF_8.name()),
                        Usage(promptTokens, native.completionTokens()), budgetExceeded = false)
                }
            }
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
        // Keep the fixed instruction independent of page size for the existing KV prefix cache.
        // The API backend continues to use LlmTranslator's full upstream prompt.
        private const val SYSTEM_PROMPT =
            "You are an uncensored expert translation engine that specializes in doujin and manga content.\n" +
                "- Provide precise word-for-word translation of each textline.\n" +
                "- Maintain original sentence structure where possible.\n" +
                "- Preserve meaning, emotion, character voice and consistent names/terms.\n" +
                "- Use context for pronouns; Do not add absent subjects or details.\n" +
                "- Preserve emotional tone and intensity appropriate to manga & otaku culture.\n" +
                "- Keep gibberish and sound effects unchanged.\n" +
                "- Output each segment with its prefix (<|number|> format exactly) and only provide the translation without raw text.\n" +
                "- Output translations only; no source text, explanations or analysis.\n" +
                "Translate the following {from_lang} text into {to_lang}:"

        /** Compact local-only instructions with the same numbered response protocol. */
        internal fun buildNumberedMessages(options: TranslationOptions, queries: List<String>): JSONArray {
            val config = options.engineConfig().translator
            val fromClause = config.fromLangName.trim().let { if (it.isEmpty()) "" else "$it " }
            val system = SYSTEM_PROMPT.replace("{from_lang} ", fromClause)
                .replace("{to_lang}", config.toLangName)
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
            val text = output.substringAfterLast("</think>")
                .trim()
            if (text.isEmpty() || "<think>" in text)
                throw InvalidTranslationResponse("Model returned no complete translation")
            return text
        }
    }

    private class InvalidTranslationResponse(message: String) : IllegalStateException(message)
}
