package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import com.hippo.ehviewer.translation.engine.ModelChecksum
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class NativeDownloadModel(val name: String, val url: String, val size: Long, val sha256: String)

object NativeModelCatalog {
    /** These pinned HY models use their translation-only, single-user prompt. */
    fun usesPlainRequests(id: String): Boolean = models.any { it.sha256 == id }

    // Pin revisions and LFS hashes so the downloaded file always matches the reviewed artifact.
    val models = listOf(
        NativeDownloadModel("HY-MT1.5-1.8B-Q4_K_M.gguf",
            "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/265b2e615a7dc9b06c435dc878829ad99a512ba2/HY-MT1.5-1.8B-Q4_K_M.gguf?download=true",
            1133080512L, "4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7"),
        NativeDownloadModel("manga-zh-Hans-v1-Q4_K_M.gguf",
            "https://huggingface.co/fumetodev/Hy-MT2-1.8B-JP-Manga-Finetune-chinese-zh-Hans-v1-GGUF/resolve/e5c4591e678ad7a0e46a305a02468d6da4e90fb9/manga-zh-Hans-v1-Q4_K_M.gguf?download=true",
            1133080416L, "7ffb275d839a8463f4a4ab4cc1fe22107d9d83ec85464f11ed8ef648f71ef21f"),
    )
}

/** Download outside the inference lock; serialize only model validation and selection. */
class NativeModelDownloader(private val store: NativeModelStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false).build()) : AutoCloseable {

    suspend fun downloadAndImport(model: NativeDownloadModel, progress: (Long, Long) -> Unit,
                                  importing: () -> Unit = {}) {
        val temp = store.createDownloadFile()
        try {
            // Reuse a previously imported identical model without another network download.
            val existing = store.file(model.sha256)
            if (existing.length() == model.size && ModelChecksum.sha256(existing) == model.sha256) {
                importing()
                TranslationRuntime.withModelMaintenance { store.publish(existing, model.name, model.sha256) }
                return
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val call = client.newCall(Request.Builder().url(model.url).build())
            // Cancellation also interrupts a blocking body read, rather than waiting for timeout.
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitModelResponse().use { response ->
                        check(response.isSuccessful) { "Model download HTTP ${response.code()}" }
                        requireNotNull(response.body()).byteStream().use { input ->
                            temp.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                var reported = -1L
                                progress(0, model.size)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= model.size) { "Model larger than expected" }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    if (total / 1048576 != reported) {
                                        reported = total / 1048576
                                        progress(total, model.size)
                                    }
                                }
                                check(total == model.size) { "Incomplete model download" }
                            }
                        }
                    }
                } finally { cancellation.cancel() }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == model.sha256) {
                "Model checksum mismatch"
            }
            currentCoroutineContext().ensureActive()
            progress(model.size, model.size)
            importing()
            TranslationRuntime.withModelMaintenance { store.publish(temp, model.name, model.sha256) }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally { temp.delete() }
    }

    fun cancel() = client.dispatcher().cancelAll()
    override fun close() {
        cancel()
        client.connectionPool().evictAll()
        client.dispatcher().executorService().shutdown()
    }
}
