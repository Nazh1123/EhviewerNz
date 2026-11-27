package com.hippo.ehviewer.translation

import com.hippo.ehviewer.R
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

    init {
        if (!options.validApiUrl())
            throw TranslationInputFailure(R.string.translation_api_invalid_url, "Invalid API URL")
    }

    override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult =
        translateDetailed(queries) { }

    override suspend fun translateDetailed(queries: List<String>, onCompleted: suspend (Map<Int, String>) -> Unit):
        LlmTranslator.TranslateResult = LlmBatching.translateIndexed(queries, onAccepted = { batch, offset, result ->
        if (result.error == null && result.missingIndices.isEmpty()) {
            val completed = batch.indices.filter { batch[it].isNotBlank() }
                .associate { index -> (offset + index) to result.translations[index] }
            if (completed.isNotEmpty()) onCompleted(completed)
        }
    }) { batch, _ -> delegate.translateDetailed(batch) }

    /** Settings diagnostics use the same single-sentence protocol as native GGUF. */
    internal suspend fun translateSample(source: String): LlmTranslator.TranslateResult =
        LlmBatching.translate(listOf(source)) {
            val (raw, usage) = request(LlmSampleTranslation.messages(options, source))
            LlmSampleTranslation.parse(source, raw, usage)
        }

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
                        TranslationRequestFailure(R.string.translation_error_network, "API connection failed"))
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
                                throw TranslationRequestFailure(R.string.translation_error_http, "API HTTP ${it.code()}", it.code())
                            }
                            val responseJson = JSONObject(body)
                            val choice = responseJson.getJSONArray("choices").getJSONObject(0)
                            val usage = responseJson.optJSONObject("usage")?.let { usage ->
                                Usage(usage.optInt("prompt_tokens", 0), usage.optInt("completion_tokens", 0))
                            }
                            if (choice.optString("finish_reason") == "length")
                                throw TranslationOutputLimitException("API output budget exhausted", usage)
                            if (choice.optString("finish_reason") == "content_filter")
                                throw TranslationRequestFailure(R.string.translation_error_incomplete, "API returned an incomplete translation")
                            val content = choice.getJSONObject("message").opt("content") as? String
                            if (content.isNullOrBlank())
                                throw TranslationRequestFailure(R.string.translation_error_incomplete, "API response has no text")
                            content to usage
                        }
                    }.recoverCatching { error ->
                        if (error is IOException || error is TranslationOutputLimitException) throw error
                        throw TranslationRequestFailure(R.string.translation_error_api_response, "Invalid API response")
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
