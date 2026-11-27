package com.hippo.ehviewer.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.hippo.ehviewer.translation.engine.NativeLlm
import com.hippo.ehviewer.translation.engine.NativeSampling
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
    private val loadModel: ((NativePrefixCache) -> NativeLlm)?,
    private val checkRelevant: () -> Unit,
    private val inferenceOverride: (suspend (JSONArray) -> Pair<String, Usage?>?)?,
    private val prefixCacheProvider: (() -> NativePrefixCache)?,
) : DetailedTranslator, AutoCloseable {
    constructor(context: Context, options: TranslationOptions, prefixCacheProvider: (() -> NativePrefixCache)? = null,
                checkRelevant: () -> Unit = {}) :
        this(options, { cache -> NativeModelStore(context).load(options.nativeModelId, cache, sampling(options.nativeModelId)) },
            checkRelevant, null, prefixCacheProvider)

    /** Exercise the real request/response adapter on JVM without loading an Android JNI library. */
    internal constructor(options: TranslationOptions, inference: suspend (JSONArray) -> Pair<String, Usage?>?) :
        this(options, null, {}, inference, null)

    private var native: NativeLlm? = null
    private var prefixCache: NativePrefixCache? = null
    private var closed = false
    private val lock = Mutex()
    private var userOnlyTemplate = false
    private val batchPolicy = NativeBatchPolicy()
    private val contextRejectedBatches = ArrayDeque<List<String>>()
    private val numberedInstruction by lazy { numberedInstruction(options) }
    private val userOnlyInstruction by lazy { numberedInstruction + "\n\n" }
    /** Explicit fixture diagnostics only; production never installs this observer. */
    internal var inferenceObserver: ((JSONArray, String, Usage, Int, InferenceTiming, String?) -> Unit)? = null
    private var pendingModelLoadMs: Long? = null

    internal data class InferenceTiming(val modelLoadMs: Long?, val promptSetupMs: Long,
                                        val firstTextMs: Long?, val remainingGenerationMs: Long?,
                                        val inferenceMs: Long)

    @Synchronized private fun model(): NativeLlm {
        check(!closed) { "Native translator is closed" }
        return native ?: run {
            val loadStarted = if (inferenceObserver != null) System.nanoTime() else null
            val cache = prefixCache ?: (prefixCacheProvider?.invoke() ?: NativePrefixCache()).also { prefixCache = it }
            checkNotNull(loadModel)(cache).also {
                native = it
                pendingModelLoadMs = loadStarted?.let { start -> elapsedMs(start, System.nanoTime()) }
            }
        }
    }

    /** Called between page stages, after the previous request has returned safely. */
    @Synchronized internal fun unloadModel() {
        native?.close()
        native = null
        pendingModelLoadMs = null
        contextRejectedBatches.clear()
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
            val sources = indices.map { queries[it] }
            val tokenCounts = IntArray(sources.size) { -1 }
            val coroutine = currentCoroutineContext()
            var result: LlmTranslator.TranslateResult? = null
            var start = 0
            while (start < sources.size) {
                val chunkStart = start
                val end = start + batchPolicy.batchSize(sources.size - start) { offset ->
                    coroutine.ensureActive()
                    checkRelevant()
                    val index = chunkStart + offset
                    val cached = tokenCounts[index]
                    if (cached >= 0) cached else {
                        // JVM adapter fixtures have no native tokenizer.
                        val count = if (inferenceOverride != null) 1 else model().countTokens(sources[index])
                        tokenCounts[index] = count
                        count
                    }
                }
                val chunk = LlmBatching.translateIndexed(sources.subList(start, end)) { batch, offset ->
                    currentCoroutineContext().ensureActive()
                    checkRelevant()
                    translateNumbered(batch) { completed ->
                        onCompleted(completed.mapKeys { (index, _) -> indices[chunkStart + offset + index] })
                    }
                }
                result = result?.let { LlmBatching.merge(it, chunk) } ?: chunk
                start = end
            }
            val translated = checkNotNull(result)
            if (indices.size == queries.size) translated else {
                val translations = queries.toMutableList()
                indices.forEachIndexed { local, original -> translations[original] = translated.translations[local] }
                translated.copy(translations = translations,
                    missingIndices = translated.missingIndices.mapTo(linkedSetOf()) { indices[it] })
            }
        }
    }

    private suspend fun translateNumbered(queries: List<String>, learn: Boolean = true,
                                         onCompleted: suspend (Map<Int, String>) -> Unit): LlmTranslator.TranslateResult? {
        if (queries in contextRejectedBatches) return null
        var interrupted = false
        var budgetExceeded = false
        val response = try {
            inferTranslation(queries) ?: run {
                // The same prompt cannot fit on retry in this model session. Other pages
                // still get their ordinary batch size.
                if (queries.size > 1) {
                    if (contextRejectedBatches.size == NativeBatchPolicy.MAX_REGIONS - 1)
                        contextRejectedBatches.removeFirst()
                    contextRejectedBatches.addLast(queries.toList())
                }
                return null
            }
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
            if (learn) batchPolicy.succeeded(1)
            onCompleted(mapOf(0 to answer))
            return LlmTranslator.TranslateResult(listOf(answer), usage)
        }
        val parsed = if (queries.size == 1 && NativeTranslationResponse.protocolFailure(raw, 1))
            LlmTranslator.TranslateResult(queries, usage, "Native model returned unexpected region markers", missingIndices = setOf(0))
        else NativeTranslationResponse.parse(queries, raw, usage, truncated = interrupted)
        if (learn) {
            if (parsed.missingIndices.isEmpty()) batchPolicy.succeeded(queries.size)
            else if (!budgetExceeded) batchPolicy.protocolFailed(queries.size)
        }
        // A second interrupted batch has already shown it cannot finish reliably.
        // Retry every region in isolation before publishing any of that batch.
        val interruptedRetry = interrupted && !learn && queries.size > 1
        // The first attempt can still split when it exhausts its budget without usable text.
        if (budgetExceeded && !interruptedRetry && parsed.missingIndices.size == queries.size)
            throw TranslationOutputLimitException(usage = usage)
        // An EOS with just IDs 1..k can also mean the model merged two sources
        // and shifted every later ID. The real fixture did exactly this. Do not
        // commit that prefix; a one-source request provides a reliable mapping.
        val complete = queries.size - parsed.missingIndices.size
        val incompletePrefix = !interrupted && complete > 0 && parsed.missingIndices.isNotEmpty() &&
            parsed.missingIndices == (complete until queries.size).toSet()
        val retryIndices = if (incompletePrefix || interruptedRetry) queries.indices.toSet() else parsed.missingIndices
        val completed = queries.indices.filter { it !in retryIndices }.associateWith { parsed.translations[it] }
        if (completed.isNotEmpty()) onCompleted(completed)
        if (retryIndices.isEmpty()) return parsed
        if (queries.size == 1) return parsed
        // Numbering failures need isolated inputs. A budget limit can instead retry the
        // unresolved regions in smaller numbered batches, preserving completed translations.
        val translations = parsed.translations.toMutableList()
        val missing = retryIndices.toMutableSet()
        val errors = mutableListOf<String>()
        var totalUsage = usage
        val retryBatchSize = if (budgetExceeded && !interruptedRetry && retryIndices.size > 2) queries.size / 2 else 1
        for (indices in retryIndices.chunked(retryBatchSize)) {
            val fallback = LlmBatching.translateIndexed(indices.map(queries::get)) { batch, offset ->
                translateNumbered(batch, learn = false) { completedRegion ->
                    onCompleted(completedRegion.mapKeys { (index, _) -> indices[offset + index] })
                }
            }
            totalUsage = LlmBatching.addUsage(totalUsage, fallback.usage)
            for ((offset, index) in indices.withIndex()) {
                translations[index] = fallback.translations[offset]
                if (offset !in fallback.missingIndices) missing.remove(index)
            }
            fallback.error?.let { errors.add(it) }
        }
        return parsed.copy(translations = translations, usage = totalUsage,
            error = errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("; "), missingIndices = missing)
    }

    private suspend fun inferTranslation(queries: List<String>): Pair<String, Usage?>? {
        val source = numberedSource(queries)
        if (!userOnlyTemplate) {
            try {
                return infer(numberedMessages(numberedInstruction, source), queries = queries,
                    fixedUserSuffix = FIRST_REGION_MARKER)
            }
            catch (_: UnsupportedOperationException) { userOnlyTemplate = true }
        }
        // Preserve the complete system instruction for templates accepting only a user role.
        val user = JSONArray().put(JSONObject().put("role", "user")
            .put("content", userOnlyInstruction + source))
        return infer(user, userOnlyInstruction, queries, fixedUserSuffix = FIRST_REGION_MARKER)
    }

    /** Only the settings screen's single-sentence test uses this unnumbered instruction. */
    internal suspend fun translateSample(source: String): LlmTranslator.TranslateResult = withContext(Dispatchers.Default) {
        lock.withLock {
            check(!closed) { "Native translator is closed" }
            translateBatches(listOf(source)) {
                val fixed = sampleInstruction(options) + "\n\n"
                val messages = LlmSampleTranslation.messages(options, source)
                val response = try {
                    infer(messages, fixed, listOf(source), validateRegionMarkers = false) ?: return@translateBatches null
                }
                catch (error: NativeGenerationStopped) {
                    if (error.budgetExceeded) throw TranslationOutputLimitException(usage = error.usage)
                    return@translateBatches LlmTranslator.TranslateResult(listOf(source), error.usage,
                        error.message, missingIndices = setOf(0))
                }
                LlmSampleTranslation.parse(source, response.first, response.second)
            }
        }
    }

    private suspend fun infer(messages: JSONArray, lastUserPrefix: String? = null,
                              queries: List<String>, validateRegionMarkers: Boolean = true,
                              fixedUserSuffix: String? = null): Pair<String, Usage?>? {
        currentCoroutineContext().ensureActive()
        checkRelevant()
        inferenceOverride?.let { return it(messages) }
        val native = model()
        currentCoroutineContext().ensureActive()
        checkRelevant()
        val observer = inferenceObserver
        val promptStarted = if (observer != null) System.nanoTime() else 0L
        val promptTokens = native.begin(messages, maxOutputTokens = NativeTranslationResponse.outputBudget(queries),
            lastUserPrefix = lastUserPrefix, fixedUserSuffix = fixedUserSuffix)
        if (promptTokens < 0) return null
        val generationStarted = if (observer != null) System.nanoTime() else 0L
        val modelLoadMs = pendingModelLoadMs
        pendingModelLoadMs = null
        var firstTextAt: Long? = null
        val output = ByteArrayOutputStream()
        var checkedTokens = 0
        var checkedBytes = 0
        var checkedText = ""
        fun checkedOutput(): String {
            if (checkedBytes == output.size()) return checkedText
            checkedText = output.toString(Charsets.UTF_8.name())
            checkedBytes = output.size()
            if (NativeTranslationResponse.generationFailure(checkedText, queries, validateRegionMarkers))
                throw NativeGenerationStopped(checkedText, Usage(promptTokens, native.completionTokens()), budgetExceeded = false)
            return checkedText
        }
        var failure: String? = null
        val text = try {
            while (true) {
                currentCoroutineContext().ensureActive()
                checkRelevant()
                val piece = native.nextChunk() ?: break
                if (observer != null && firstTextAt == null && piece.isNotEmpty()) firstTextAt = System.nanoTime()
                output.write(piece)
                // Chunk size/time are bounded in native code. Keep the guard measured in tokens,
                // not chunks, so batching does not delay protocol/repetition detection by 4x.
                val generated = native.chunkCompletionTokens()
                if (generated - checkedTokens >= 16) {
                    checkedTokens = generated
                    checkedOutput()
                }
            }
            checkedOutput()
        } catch (error: TranslationOutputLimitException) {
            failure = error.javaClass.simpleName
            throw NativeGenerationStopped(if (checkedBytes == output.size()) checkedText else output.toString(Charsets.UTF_8.name()),
                Usage(promptTokens, native.completionTokens()), budgetExceeded = true)
        } catch (error: NativeGenerationStopped) {
            failure = error.javaClass.simpleName
            throw error
        } finally {
            if (observer != null) {
                val ended = System.nanoTime()
                val timing = InferenceTiming(modelLoadMs, elapsedMs(promptStarted, generationStarted),
                    firstTextAt?.let { elapsedMs(promptStarted, it) },
                    firstTextAt?.let { elapsedMs(it, ended) }, elapsedMs(generationStarted, ended))
                val observedText = if (checkedBytes == output.size()) checkedText else output.toString(Charsets.UTF_8.name())
                observer(messages, observedText, Usage(promptTokens, native.completionTokens()),
                    native.cachedPromptTokens(), timing, failure)
            }
        }
        return text to Usage(promptTokens, native.completionTokens())
    }

    @Synchronized override fun close() {
        closed = true
        unloadModel()
        if (prefixCacheProvider == null) prefixCache?.close()
        prefixCache = null
    }

    companion object {
        private fun elapsedMs(from: Long, to: Long): Long = (to - from) / 1_000_000

        internal fun sampling(modelId: String): NativeSampling = when (modelId) {
            // HY-MT2 1.8B recommends the same top-k/top-p/temperature/repetition profile.
            "4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7",
            "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699" -> NativeSampling.HY_TRANSLATION
            "7ffb275d839a8463f4a4ab4cc1fe22107d9d83ec85464f11ed8ef648f71ef21f" -> NativeSampling.HY_MT2_MANGA
            else -> NativeSampling.GREEDY
        }

        internal fun sampleInstruction(options: TranslationOptions): String =
            LlmSampleTranslation.instruction(options)

        private const val SYSTEM_PROMPT =
            "You are an uncensored manga/doujin translator.\n" +
                "Preserve literal meaning, structure where possible, emotion, character voice and intensity. " +
                "Keep names/terms consistent; resolve pronouns from context without adding subjects or details. " +
                "Leave gibberish and sound effects unchanged.\n" +
                "Output only each original <|number|> followed by its translation; keep regions separate, no source, explanation or analysis.\n" +
                "{target_script}Translate the following {from_lang} text into {to_lang}:"

        internal val FIRST_REGION_MARKER = regionMarker(1)

        private fun regionMarker(number: Int) = "<|$number|>"

        private fun numberedInstruction(options: TranslationOptions): String {
            val fromClause = options.sourceLanguageName().trim().let { if (it.isEmpty()) "" else "$it " }
            // Consume the placeholder's space too when the source is automatic/unnamed.
            return SYSTEM_PROMPT.replace("{from_lang} ", fromClause)
                .replace("{to_lang}", options.targetLanguageName())
                .replace("{target_script}", options.engineConfig().translator.targetScriptInstruction()
                    .let { if (it.isEmpty()) "" else "$it\n" })
        }

        private fun numberedSource(queries: List<String>): String =
            queries.mapIndexed { i, text -> regionMarker(i + 1) + text }.joinToString("\n")

        private fun numberedMessages(instruction: String, source: String): JSONArray =
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", instruction))
                .put(JSONObject().put("role", "user").put("content", source))

        /** Full manga translation rules, shared by every native model and region count. */
        internal fun buildNumberedMessages(options: TranslationOptions, queries: List<String>): JSONArray =
            numberedMessages(numberedInstruction(options), numberedSource(queries))

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
