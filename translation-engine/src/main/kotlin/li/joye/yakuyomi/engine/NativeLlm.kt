package li.joye.yakuyomi.engine

import org.json.JSONArray
import org.json.JSONObject

/** One CPU model/context. The caller serializes whole requests; close is idempotent. */
class NativeLlm(path: String, prefixCache: NativePrefixCache? = null) : AutoCloseable {
    private val cache = prefixCache ?: NativePrefixCache()
    private val ownsCache = prefixCache == null
    private var handle = try {
        cache.createModel(path, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    } catch (error: Throwable) {
        if (ownsCache) cache.close()
        throw error
    }

    @Synchronized fun begin(prompt: String) {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        check(begin(messages) >= 0) { "Translation input exceeds the model context" }
    }

    /** Returns prompt tokens, or -1 when this batch does not fit. No output is generated then. */
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

    /** Actual prefill tokens reused by the latest accepted request; usage still counts the full prompt. */
    @Synchronized fun cachedPromptTokens(): Int {
        check(handle != 0L) { "Native translator is closed" }
        return cachedPromptTokens(handle)
    }

    /** Selected CPU capabilities for device diagnostics; never includes model text. */
    @Synchronized fun systemInfo(): String {
        check(handle != 0L) { "Native translator is closed" }
        return systemInfo(handle)
    }

    /** Latest decode configuration: active compute threads and CPUs allowed by Android.
     * A CPU restriction is detected again before every prompt batch/output token. */
    @Synchronized fun cpuState(): IntArray {
        check(handle != 0L) { "Native translator is closed" }
        return cpuState(handle)
    }

    /** Decode at most one prompt batch or output token, allowing cancellation between calls.
     * Null means EOS; empty bytes mean prompt processing or a token with no text.
     * An exhausted generation budget throws [TranslationOutputLimitException], never a partial success. */
    @Synchronized fun next(): ByteArray? {
        check(handle != 0L) { "Native translator is closed" }
        return next(handle)
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
    private external fun destroy(handle: Long)

    companion object { init { System.loadLibrary("ehnz_llama") } }
}
