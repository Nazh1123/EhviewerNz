package com.hippo.ehviewer.translation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hippo.ehviewer.translation.engine.ModelChecksum
import com.hippo.ehviewer.translation.engine.ModelSet
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** The embedded manifest is the authority for both imported and downloaded weights. */
class TranslationModels internal constructor(private val context: Context, manifestOverride: String? = null) {
    private val dir = File(context.noBackupFilesDir, "translation-models").apply { mkdirs() }
    private val manifest = JSONObject(manifestOverride ?: context.assets.open("translation-models.json")
        .bufferedReader().use { it.readText() })
    private data class Entry(val name: String, val size: Long, val sha256: String)
    private val files = manifest.getJSONArray("models").let { a -> (0 until a.length()).map {
        a.getJSONObject(it).let { item -> Entry(item.getString("name"), item.getLong("size"), item.getString("sha256")) }
    } }
    internal val downloadUrl: String get() = manifest.getJSONObject("bundle").getString("url")
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private var verifiedStamps: List<Stamp>? = null

    fun ready(): Boolean = files.all { File(dir, it.name).length() == it.size }
    val requiredNames: List<String> = files.map { it.name }
    val totalBytes: Long = files.sumOf { it.size }
    fun storedBytes(): Long = requiredNames.sumOf { File(dir, it).length() }

    /** Caller holds TranslationRuntime.lock; verify the whole set before publication. */
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
                requireNotNull(resolver.openInputStream(sources.getValue(entry.name))).use { input ->
                    copyVerified(input, entry, staged) { copied -> progress(entry.name, copied, entry.size) }
                }
            }
            publish(staged)
        } finally { staged.forEach { it.first.delete() } }
    }

    /** Stream into verified staging files without retaining another full ZIP on disk.
     * Network and decompression stay outside the inference lock. */
    internal suspend fun installArchive(input: InputStream, progress: (String, Long, Long) -> Unit) {
        val remaining = files.associateBy { it.name }.toMutableMap()
        val staged = mutableListOf<Pair<File, File>>()
        var completed = 0L
        try {
            ZipInputStream(input.buffered(65536)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = zip.nextEntry ?: break
                    // Match an exact manifest name; never resolve a ZIP path on disk.
                    val entry = requireNotNull(remaining.remove(item.name)) { "Unexpected or duplicate model: ${item.name}" }
                    copyVerified(zip, entry, staged) { copied -> progress(entry.name, completed + copied, totalBytes) }
                    zip.closeEntry()
                    completed += entry.size
                }
            }
            check(remaining.isEmpty()) { "Incomplete manga model bundle" }
            TranslationRuntime.withModelMaintenance { publish(staged) }
        } finally { staged.forEach { it.first.delete() } }
    }

    private suspend fun copyVerified(input: InputStream, entry: Entry, staged: MutableList<Pair<File, File>>,
                                     progress: (Long) -> Unit) {
        currentCoroutineContext().ensureActive()
        val temp = File.createTempFile("import-", ".part", dir)
        staged.add(temp to File(dir, entry.name))
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var reported = 0L
        progress(0)
        temp.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                check(copied <= entry.size) { "Model larger than manifest: ${entry.name}" }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                if (copied - reported >= 1048576) { progress(copied); reported = copied }
            }
        }
        check(copied == entry.size && digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256) {
            "Model checksum mismatch: ${entry.name}"
        }
        progress(copied)
    }

    private suspend fun publish(staged: List<Pair<File, File>>) {
        currentCoroutineContext().ensureActive()
        verifiedStamps = null
        // Every replacement has the same pinned hash, so partial publication is retryable.
        for ((temp, target) in staged) Files.move(temp.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        verifiedStamps = stamps()
    }

    fun delete() {
        verifiedStamps = null
        for (name in requiredNames) {
            val file = File(dir, name)
            check(!file.exists() || file.delete()) { "Cannot delete manga model: $name" }
            val partial = File(dir, "$name.part")
            check(!partial.exists() || partial.delete()) { "Cannot delete partial model: $name" }
        }
    }

    private fun stamps() = files.map { entry ->
        val attrs = Files.readAttributes(File(dir, entry.name).toPath(), BasicFileAttributes::class.java)
        check(attrs.isRegularFile && attrs.size() == entry.size) { "Model missing or wrong size: ${entry.name}" }
        Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
    }

    /** Private immutable files are hashed once per session, again if replaced or modified. */
    suspend fun verified(): ModelSet {
        currentCoroutineContext().ensureActive()
        val current = stamps()
        // Providers without file identities cannot distinguish same-size replacements.
        if (current.any { it.key == null } || current != verifiedStamps) {
            for (entry in files) {
                currentCoroutineContext().ensureActive()
                check(ModelChecksum.sha256(File(dir, entry.name)) == entry.sha256) { "Model checksum mismatch: ${entry.name}" }
            }
            verifiedStamps = current
        }
        return requireNotNull(ModelSet.resolve(files.map { it.name to File(dir, it.name).absolutePath }))
    }
}
