package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MangaModelDownloader(private val models: TranslationModels,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false).build()) : AutoCloseable {

    suspend fun downloadAndImport(progress: (String, Long, Long) -> Unit) {
        val call = client.newCall(Request.Builder().url(models.downloadUrl).build())
        try {
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitModelResponse().use { response ->
                        check(response.isSuccessful) { "Manga model download HTTP ${response.code()}" }
                        models.installArchive(requireNotNull(response.body()).byteStream(), progress)
                    }
                } finally { cancellation.cancel() }
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    fun cancel() = client.dispatcher().cancelAll()
    override fun close() {
        cancel()
        client.connectionPool().evictAll()
        client.dispatcher().executorService().shutdown()
    }
}
