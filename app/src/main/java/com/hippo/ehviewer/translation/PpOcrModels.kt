package com.hippo.ehviewer.translation

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Pinned official recognition weights; dictionaries are bundled, weights are installed explicitly. */
class PpOcrModels(context: Context) {
    data class Entry(val language: String, val name: String, val size: Long, val url: String)
    private val manifest = JSONObject(context.assets.open("ppocr-models.json").bufferedReader().use { it.readText() })
    private val specs = manifest.getJSONArray("models").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val entries = specs.map { Entry(it.getString("language"), it.getString("name"), it.getLong("size"), it.getString("url")) }
    private val stores = specs.associate { spec ->
        spec.getString("language") to TranslationModels(context,
            JSONObject().put("models", org.json.JSONArray().put(spec)).toString(), "ppocr-${spec.getString("language")}")
    }
    fun store(language: String): TranslationModels = stores.getValue(language)
    fun ready(source: String): Boolean = key(source)?.let { store(it).ready() } ?: true
    suspend fun path(language: String): String = store(language).verifiedFiles().getValue(entries.single { it.language == language }.name)
    suspend fun import(language: String, uri: Uri, progress: (String, Long, Long) -> Unit) = store(language).importSingle(uri, progress)

    suspend fun download(language: String, progress: (String, Long, Long) -> Unit) {
        val entry = entries.single { it.language == language }
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url(entry.url).build())
        try {
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitModelResponse().use {
                        check(it.isSuccessful) { "PP-OCR download HTTP ${it.code()}" }
                        store(language).installFile(entry.name, requireNotNull(it.body()).byteStream(), progress)
                    }
                } finally { cancellation.cancel() }
            }
        } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown() }
    }

    companion object {
        fun key(source: String): String? = when (source) {
            "en" -> "en"
            "ko" -> "ko"
            "zh", "zh-CN", "zh-TW" -> "zh"
            else -> null
        }
    }
}

internal class MissingPpOcrModel : IllegalStateException("Required PP-OCR model is not installed")
