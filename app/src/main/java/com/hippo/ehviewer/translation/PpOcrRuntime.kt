package com.hippo.ehviewer.translation

import android.content.Context
import android.net.Uri
import com.hippo.ehviewer.translation.engine.ModelChecksum
import com.hippo.ehviewer.translation.engine.PpOcr
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Only the two pinned arm64 libraries are installed; Java bindings stay in the APK. */
class PpOcrRuntime internal constructor(private val context: Context, manifestOverride: String? = null) {
    private val manifest = JSONObject(manifestOverride ?: context.assets.open("ppocr-runtime.json")
        .bufferedReader().use { it.readText() })
    val store = TranslationModels(context, manifest.toString(), "ppocr-runtime-${manifest.getString("version")}", readOnly = true)
    private val bundle = manifest.getJSONObject("bundle")
    val downloadBytes: Long = bundle.getLong("size")

    fun ready(): Boolean = store.ready()

    suspend fun prepare() {
        if (!ready()) throw MissingPpOcrModel()
        val paths = store.verifiedFiles()
        PpOcr.configureRuntime(File(paths.getValue("libonnxruntime.so")).parentFile!!.absolutePath)
    }

    /** Local imports use the compact component ZIP, with exact manifest filenames. */
    suspend fun import(uri: Uri, progress: (String, Long, Long) -> Unit) {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { store.installArchive(it, progress) }
    }

    suspend fun download(progress: (String, Long, Long) -> Unit) {
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build()
        val call = client.newCall(Request.Builder().url(bundle.getString("url")).build())
        try {
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitModelResponse().use { response ->
                        check(response.isSuccessful) { "OCR component download HTTP ${response.code()}" }
                        installOfficialArchive(requireNotNull(response.body()).byteStream(), progress)
                    }
                } finally { cancellation.cancel() }
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown() }
    }

    /** Verify the complete AAR before skipping its other ABIs and metadata. Never install Java code. */
    internal suspend fun installOfficialArchive(input: InputStream, progress: (String, Long, Long) -> Unit) {
        val archive = File.createTempFile("ppocr-runtime-", ".part", context.cacheDir)
        try {
            archive.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var copied = 0L
                var reported = 0L
                progress("PP-OCRv5", 0, downloadBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    check(copied <= downloadBytes) { "OCR component larger than manifest" }
                    output.write(buffer, 0, count)
                    if (copied - reported >= 1048576) { progress("PP-OCRv5", copied, downloadBytes); reported = copied }
                }
                check(copied == downloadBytes) { "Incomplete OCR component" }
                progress("PP-OCRv5", copied, downloadBytes)
            }
            currentCoroutineContext().ensureActive()
            check(ModelChecksum.sha256(archive) == bundle.getString("sha256")) { "OCR component checksum mismatch" }
            currentCoroutineContext().ensureActive()
            archive.inputStream().use {
                store.installMappedArchive(it, store.requiredNames.associate { name -> "jni/arm64-v8a/$name" to name },
                    ignoreUnlisted = true, progress = progress)
            }
        } finally { archive.delete() }
    }
}
