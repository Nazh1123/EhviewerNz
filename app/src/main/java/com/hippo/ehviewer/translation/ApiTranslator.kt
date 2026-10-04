package com.hippo.ehviewer.translation

import kotlinx.coroutines.suspendCancellableCoroutine
import com.hippo.ehviewer.translation.engine.DetailedTranslator
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.LlmBatching
import com.hippo.ehviewer.translation.engine.Usage
import com.hippo.ehviewer.translation.engine.TranslationOutputLimitException
import okhttp3.*
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** The transport owns request cancellation, service compatibility and response validation. */
class ApiTranslator(private val options: TranslationOptions) : DetailedTranslator, AutoCloseable {
    private val config = options.engineConfig().translator
    private val delegate = LlmTranslator(config, transport = ::request)
    @Volatile private var temperatureRejected = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    init { require(options.validApiUrl()) }

    override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult =
        if (queries.all { it.isBlank() }) LlmTranslator.TranslateResult(queries)
        else LlmBatching.translate(queries, delegate::translateDetailed)

    private suspend fun request(messages: JSONArray): Pair<String, Usage?> = request(messages, !temperatureRejected)

    private suspend fun request(messages: JSONArray, includeTemperature: Boolean): Pair<String, Usage?> {
        val json = JSONObject().put("messages", messages).put("stream", false)
        if (options.apiModel.isNotBlank()) json.put("model", options.apiModel.trim())
        if (includeTemperature) json.put("temperature", 0.3)
        val request = Request.Builder().url(options.apiUrl.trim())
            .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), json.toString()))
            .apply { if (options.apiKey.isNotBlank()) header("Authorization", "Bearer ${options.apiKey.trim()}") }
            .build()
        return try { suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException("API connection failed (check address, service and timeout)"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            // Do not expose provider response bodies, prompts, URLs or credentials in logs.
                            val body = requireNotNull(it.body()).string()
                            if (!it.isSuccessful) {
                                if (includeTemperature && it.code() == 400 && body.contains("temperature", true) &&
                                    listOf("unsupported", "not supported", "not allowed", "invalid").any { word -> body.contains(word, true) })
                                    throw UnsupportedTemperature()
                                throw IOException("API HTTP ${it.code()}")
                            }
                            val responseJson = JSONObject(body)
                            val choice = responseJson.getJSONArray("choices").getJSONObject(0)
                            val usage = responseJson.optJSONObject("usage")?.let { usage ->
                                Usage(usage.optInt("prompt_tokens", 0), usage.optInt("completion_tokens", 0))
                            }
                            if (choice.optString("finish_reason") == "length")
                                throw TranslationOutputLimitException("API output budget exhausted", usage)
                            if (choice.optString("finish_reason") == "content_filter")
                                throw IOException("API returned an incomplete translation")
                            val content = choice.getJSONObject("message").opt("content") as? String
                                ?: throw IOException("API response has no text")
                            if (content.isBlank()) throw IOException("API returned no translation")
                            content to usage
                        }
                    }.recoverCatching { error ->
                        if (error is IOException || error is TranslationOutputLimitException) throw error
                        throw IOException("Invalid API response")
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        } } catch (_: UnsupportedTemperature) {
            temperatureRejected = true
            request(messages, false)
        }
    }

    override fun close() {
        client.dispatcher().cancelAll()
        client.connectionPool().evictAll()
        client.dispatcher().executorService().shutdown()
    }

    private class UnsupportedTemperature : IOException("API does not accept temperature")
}
