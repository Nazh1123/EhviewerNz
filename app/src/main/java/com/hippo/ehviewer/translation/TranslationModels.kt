package com.hippo.ehviewer.translation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import li.joye.yakuyomi.engine.ModelDownloader
import li.joye.yakuyomi.engine.ModelSet
import okhttp3.*
import org.json.JSONObject

/** Fixed manifest from the pinned engine, verified before any native model is loaded. */
class TranslationModels internal constructor(private val context: Context, manifestOverride: String? = null) {
    private val dir = File(context.noBackupFilesDir, "translation-models").apply { mkdirs() }
    private val manifest = manifestOverride ?: context.assets.open("translation-models.json").bufferedReader().use { it.readText() }
    private val files = JSONObject(manifest).getJSONArray("models").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null

    fun ready(): Boolean = files.all { File(dir, it.getString("name")).length() == it.getLong("size") }

    val requiredNames: List<String> get() = files.map { it.getString("name") }
    val totalBytes: Long get() = files.sumOf { it.getLong("size") }
    fun storedBytes(): Long = requiredNames.sumOf { File(dir, it).length() }

    /** Caller holds TranslationRuntime.lock; verify the complete bundle before replacing any file. */
    suspend fun import(uris: List<Uri>, progress: (String, Long, Long) -> Unit) {
        val resolver = context.contentResolver
        val sources = uris.associateBy { uri ->
            runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: if (uri.scheme == "file") File(uri.path.orEmpty()).name else uri.lastPathSegment.orEmpty()
        }
        require(sources.keys == requiredNames.toSet() && uris.size == requiredNames.size) {
            "Please select all required manga model files"
        }
        val staged = mutableListOf<Pair<File, File>>()
        try {
            for (entry in files) {
                currentCoroutineContext().ensureActive()
                val name = entry.getString("name")
                val size = entry.getLong("size")
                val temp = File.createTempFile("import-", ".part", dir)
                staged.add(temp to File(dir, name))
                requireNotNull(resolver.openInputStream(sources.getValue(name))).use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= size) { "Model larger than manifest" }
                            output.write(buffer, 0, count)
                            progress(name, copied, size)
                        }
                    }
                }
                check(temp.length() == size && ModelDownloader.sha256(temp) == entry.getString("sha256")) {
                    "Model checksum mismatch: $name"
                }
            }
            currentCoroutineContext().ensureActive()
            // Each replacement has the exact same pinned checksum; partial publication is safe to retry.
            for ((temp, target) in staged) Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.forEach { it.first.delete() } }
    }

    fun delete() {
        for (name in requiredNames) {
            val file = File(dir, name)
            check(!file.exists() || file.delete()) { "Cannot delete manga model: $name" }
            val partial = File(dir, "$name.part")
            check(!partial.exists() || partial.delete()) { "Cannot delete partial model: $name" }
        }
    }

    suspend fun verified(): ModelSet {
        for (entry in files) {
            currentCoroutineContext().ensureActive()
            val file = File(dir, entry.getString("name"))
            check(file.length() == entry.getLong("size") && ModelDownloader.sha256(file) == entry.getString("sha256"))
                { "Model missing or checksum mismatch" }
        }
        return requireNotNull(ModelSet.resolve(files.map { it.getString("name").let { name -> name to File(dir, name).absolutePath } }))
    }

    suspend fun download(progress: (String, Long, Long) -> Unit) {
        for (entry in files) {
            currentCoroutineContext().ensureActive()
            val name = entry.getString("name")
            val size = entry.getLong("size")
            val file = File(dir, name)
            if (file.length() == size && ModelDownloader.sha256(file) == entry.getString("sha256")) continue
            val tmp = File(dir, "$name.part")
            try {
                val requestCall = client.newCall(Request.Builder().url(entry.getString("url")).build())
                call = requestCall
                requestCall.withModelResponse { response ->
                    check(response.isSuccessful) { "Model download HTTP ${response.code()}" }
                    requireNotNull(response.body()).byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            var reported = -1L
                            progress(name, 0, size)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                check(total <= size) { "Model larger than manifest" }
                                out.write(buffer, 0, n)
                                if (total / 1048576 != reported) {
                                    reported = total / 1048576
                                    progress(name, total, size)
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                check(tmp.length() == size && ModelDownloader.sha256(tmp) == entry.getString("sha256")) { "Model checksum mismatch" }
                currentCoroutineContext().ensureActive()
                check(tmp.renameTo(file)) { "Cannot publish model" }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                call = null
                tmp.delete()
            }
        }
    }

    fun cancel() { call?.cancel() }
}

/** Keep cancellation attached until the blocking response body has also been consumed. */
internal suspend fun <T> Call.withModelResponse(block: suspend (Response) -> T): T = coroutineScope {
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@withModelResponse.cancel() }
    }
    try { awaitModelResponse().use { block(it) } }
    finally { cancellation.cancel() }
}

internal suspend fun Call.awaitModelResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
