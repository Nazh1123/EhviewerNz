package com.hippo.ehviewer.translation.engine

import org.json.JSONArray
import org.json.JSONObject


class NativeLlm(path: String, prefixCache: NativePrefixCache? = null,
                sampling: NativeSampling = NativeSampling.GREEDY) : AutoCloseable {
    private val cache = prefixCache ?: NativePrefixCache()
    private val ownsCache = prefixCache == null
    private val chunkState = IntArray(1)
    private var handle = try {
        cache.createModel(path, Runtime.getRuntime().availableProcessors().coerceIn(1, 4)).also { model ->
            try { configureSampling(model, sampling.id) }
            catch (error: Throwable) { destroy(model); throw error }
        }
    } catch (error: Throwable) {
        if (ownsCache) cache.close()
        throw error
    }

    @Synchronized fun begin(prompt: String) {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        check(begin(messages) >= 0) { "Translation input exceeds the model context" }
    }


    @Synchronized fun begin(messages: JSONArray, maxOutputTokens: Int = 1024,
                            lastUserPrefix: String? = null, cacheEnabled: Boolean = true): Int {
        check(handle != 0L) { "Native translator is closed" }
        val roles = Array(messages.length()) { messages.getJSONObject(it).getString("role").toByteArray(Charsets.UTF_8) }
        val contents = Array(messages.length()) { messages.getJSONObject(it).getString("content").toByteArray(Charsets.UTF_8) }
        val tokens = beginMessages(handle, roles, contents, maxOutputTokens,
            lastUserPrefix?.toByteArray(Charsets.UTF_8), cacheEnabled)
        if (tokens == -2) throw UnsupportedOperationException("GGUF chat template does not support these message roles")
        return tokens
    }

    @Synchronized fun completionTokens(): Int {
        check(handle != 0L) { "Native translator is closed" }
        return completionTokens(handle)
    }


    @Synchronized fun cachedPromptTokens(): Int {
        check(handle != 0L) { "Native translator is closed" }
        return cachedPromptTokens(handle)
    }


    @Synchronized fun systemInfo(): String {
        check(handle != 0L) { "Native translator is closed" }
        return systemInfo(handle)
    }


    @Synchronized fun cpuState(): IntArray {
        check(handle != 0L) { "Native translator is closed" }
        return cpuState(handle)
    }


    @Synchronized fun next(): ByteArray? {
        check(handle != 0L) { "Native translator is closed" }
        return next(handle)
    }

    /** At most four tokens / 32 ms between Kotlin cancellation checks (plus one decode). */
    @Synchronized fun nextChunk(): ByteArray? {
        check(handle != 0L) { "Native translator is closed" }
        return nextChunk(handle, 4, 32, chunkState)
    }

    /** Count returned with the last chunk; no extra JNI call in the generation loop. */
    @Synchronized fun chunkCompletionTokens(): Int = chunkState[0]

    @Synchronized fun countTokens(text: String): Int {
        check(handle != 0L) { "Native translator is closed" }
        return countTokens(handle, text.toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun close() {
        if (handle != 0L) { destroy(handle); handle = 0L }
        if (ownsCache) cache.close()
    }

    private external fun beginMessages(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>,
                                      maxOutputTokens: Int, lastUserPrefix: ByteArray?, cacheEnabled: Boolean): Int
    private external fun completionTokens(handle: Long): Int
    private external fun cachedPromptTokens(handle: Long): Int
    private external fun systemInfo(handle: Long): String
    private external fun cpuState(handle: Long): IntArray
    private external fun next(handle: Long): ByteArray?
    private external fun nextChunk(handle: Long, maxTokens: Int, maxMillis: Int, state: IntArray): ByteArray?
    private external fun countTokens(handle: Long, text: ByteArray): Int
    private external fun configureSampling(handle: Long, profile: Int)
    private external fun destroy(handle: Long)

    companion object { init { System.loadLibrary("ehnz_llama") } }
}
