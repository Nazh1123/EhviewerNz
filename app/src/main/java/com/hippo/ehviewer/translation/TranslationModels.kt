package com.hippo.ehviewer.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.hippo.ehviewer.R
import com.hippo.ehviewer.translation.engine.ModelChecksum
import com.hippo.ehviewer.translation.engine.ModelFile
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
class TranslationModels internal constructor(private val context: Context, manifestOverride: String? = null,
                                             directoryName: String = "translation-models",
                                             private val readOnly: Boolean = false,
                                             private val managesTranslationRuntime: Boolean = true) {
    private val dir = File(context.noBackupFilesDir, directoryName).apply { mkdirs() }
    private val manifest = JSONObject(manifestOverride ?: context.assets.open("translation-models.json")
        .bufferedReader().use { it.readText() })
    private data class Entry(val name: String, val size: Long, val sha256: String)
    private val files = manifest.getJSONArray("models").let { a -> (0 until a.length()).map {
        a.getJSONObject(it).let { item -> Entry(item.getString("name"), item.getLong("size"), item.getString("sha256")) }
    } }
    internal val downloadUrl: String get() = manifest.getJSONObject("bundle").getString("url")
    private data class Stamp(val size: Long, val modified: FileTime, val key: Any?)
    private var verifiedStamps: List<Stamp>? = null
    private val references = ImageModelReferences(context, directoryName)
    enum class ImportMode { COPY, REFERENCE }
    fun hasReferences(): Boolean = references.all().isNotEmpty()

    fun ready(): Boolean = runCatching {
        val external = references.all()
        files.all { entry ->
            external[entry.name]?.let { reference -> openReference(entry, reference).use { true } }
                ?: (File(dir, entry.name).length() == entry.size)
        }
    }.getOrDefault(false)
    val requiredNames: List<String> = files.map { it.name }
    val totalBytes: Long = files.sumOf { it.size }
    fun storedBytes(): Long = requiredNames.sumOf { File(dir, it).length() }

    /** Caller holds TranslationRuntime.lock; verify the whole set before publication. */
    suspend fun import(uris: List<Uri>, mode: ImportMode = ImportMode.COPY, progress: (String, Long, Long) -> Unit) {
        val resolver = context.contentResolver
        val sources = uris.associateBy { uri ->
            runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: if (uri.scheme == "file") File(uri.path.orEmpty()).name else uri.lastPathSegment.orEmpty()
        }
        require(sources.keys == requiredNames.toSet() && uris.size == requiredNames.size) {
            "Please select all required manga model files"
        }
        if (mode == ImportMode.REFERENCE) { importReferences(sources, progress); return }
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
    internal suspend fun installArchive(input: InputStream, progress: (String, Long, Long) -> Unit) =
        installMappedArchive(input, requiredNames.associateWith { it }, false, progress)

    /** Only use ignoreUnlisted after validating the complete source archive. */
    internal suspend fun installMappedArchive(input: InputStream, archiveNames: Map<String, String>,
                                             ignoreUnlisted: Boolean, progress: (String, Long, Long) -> Unit) {
        val remaining = files.associateBy { it.name }.toMutableMap()
        val staged = mutableListOf<Pair<File, File>>()
        var completed = 0L
        try {
            ZipInputStream(input.buffered(65536)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = zip.nextEntry ?: break
                    // Match an exact manifest name; never resolve a ZIP path on disk.
                    val name = archiveNames[item.name]
                    if (name == null && ignoreUnlisted) { zip.closeEntry(); continue }
                    val entry = requireNotNull(remaining.remove(name)) { "Unexpected or duplicate model: ${item.name}" }
                    copyVerified(zip, entry, staged) { copied -> progress(entry.name, completed + copied, totalBytes) }
                    zip.closeEntry()
                    completed += entry.size
                }
            }
            check(remaining.isEmpty()) { "Incomplete manga model bundle" }
            publishWithMaintenance(staged)
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
            // Open the descriptor before removing write permission for executable components.
            if (readOnly) check(temp.setReadOnly()) { "Cannot protect component: ${entry.name}" }
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
        val old = references.all()
        references.replace(old.filterKeys { name -> staged.none { it.second.name == name } })
        old.values.forEach { references.releaseUnused(it.uri) }
        // Incremental downloads publish one file at a time. Only a complete, freshly
        // verified set can seed the hash cache; companions may not exist yet.
        if (!hasReferences() && staged.map { it.second.name }.containsAll(requiredNames)) {
            verifiedStamps = stamps()
        }
    }

    /** Other image engines own their own inference lock and must not dispose translation models. */
    private suspend fun publishWithMaintenance(staged: List<Pair<File, File>>) {
        if (managesTranslationRuntime) TranslationRuntime.withModelMaintenance { publish(staged) }
        else publish(staged)
    }

    internal suspend fun installFile(name: String, input: InputStream, progress: (String, Long, Long) -> Unit) {
        val entry = files.single { it.name == name }
        val staged = mutableListOf<Pair<File, File>>()
        try {
            copyVerified(input, entry, staged) { progress(name, it, entry.size) }
            publishWithMaintenance(staged)
        } finally { staged.forEach { it.first.delete() } }
    }

    internal suspend fun importSingle(uri: Uri, mode: ImportMode = ImportMode.COPY, progress: (String, Long, Long) -> Unit) {
        if (mode == ImportMode.REFERENCE) { importReferences(mapOf(files.single().name to uri), progress); return }
        requireNotNull(context.contentResolver.openInputStream(uri)).use {
            // Import is already under the maintenance lock; do not acquire it twice.
            val staged = mutableListOf<Pair<File, File>>()
            try {
                val entry = files.single()
                copyVerified(it, entry, staged) { copied -> progress(entry.name, copied, entry.size) }
                publish(staged)
            } finally { staged.forEach { pair -> pair.first.delete() } }
        }
    }

    fun delete() {
        verifiedStamps = null
        for (name in requiredNames) {
            val file = File(dir, name)
            check(!file.exists() || file.delete()) { "Cannot delete manga model: $name" }
            val partial = File(dir, "$name.part")
            check(!partial.exists() || partial.delete()) { "Cannot delete partial model: $name" }
        }
        val old = references.all()
        references.replace(emptyMap())
        old.values.forEach { references.releaseUnused(it.uri) }
    }

    private fun externalStamp(descriptor: ParcelFileDescriptor, entry: Entry): String {
        val stat = Os.fstat(descriptor.fileDescriptor)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == entry.size) {
            context.getString(R.string.translation_image_external_unsupported)
        }
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        val nanos = if (Build.VERSION.SDK_INT >= 27) stat.st_mtim.tv_nsec else 0L
        return "${stat.st_size}:${stat.st_mtime}:$nanos"
    }

    private fun openReference(entry: Entry, reference: ImageModelReferences.Reference): ParcelFileDescriptor {
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(reference.uri, "r")) {
            context.getString(R.string.translation_image_external_changed)
        }
        try {
            check(externalStamp(descriptor, entry) == reference.stamp) { context.getString(R.string.translation_image_external_changed) }
            return descriptor
        } catch (error: Throwable) { descriptor.close(); throw error }
    }

    private suspend fun verifyExternal(descriptor: ParcelFileDescriptor, entry: Entry, progress: (Long) -> Unit) {
        val before = externalStamp(descriptor, entry)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var read = 0L
        var reported = 0L
        progress(0)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
            if (count == 0) break
            read += count
            check(read <= entry.size) { "Model larger than manifest: ${entry.name}" }
            digest.update(buffer, 0, count)
            if (read - reported >= 1048576) { progress(read); reported = read }
        }
        check(read == entry.size && digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256) {
            "Model checksum mismatch: ${entry.name}"
        }
        check(externalStamp(descriptor, entry) == before) { context.getString(R.string.translation_image_external_changed) }
        progress(read)
    }

    /** Verify all sources and grants before atomically publishing the entire set. Never stage a copy. */
    private suspend fun importReferences(sources: Map<String, Uri>, progress: (String, Long, Long) -> Unit) {
        require(!readOnly) { "Executable OCR components must be installed in private storage" }
        val resolver = context.contentResolver
        val old = references.all()
        val granted = mutableListOf<Uri>()
        val verified = linkedMapOf<String, ImageModelReferences.Reference>()
        try {
            for (entry in files) {
                currentCoroutineContext().ensureActive()
                val uri = sources.getValue(entry.name)
                require(uri.scheme != "file" || File(uri.path.orEmpty()).canonicalFile.parentFile != dir.canonicalFile) {
                    "Choose an external source, not a managed private model file"
                }
                if (uri.scheme == "content") {
                    val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (!held) granted.add(uri)
                }
                requireNotNull(resolver.openFileDescriptor(uri, "r")).use { descriptor ->
                    val stamp = externalStamp(descriptor, entry)
                    verifyExternal(descriptor, entry) { progress(entry.name, it, entry.size) }
                    // ONNX uses a read-only memory map when creating its session.
                    if (entry.name.endsWith(".onnx")) {
                        val address = Os.mmap(0, entry.size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, descriptor.fileDescriptor, 0)
                        Os.munmap(address, entry.size)
                    }
                    check(externalStamp(descriptor, entry) == stamp) { context.getString(R.string.translation_image_external_changed) }
                    verified[entry.name] = ImageModelReferences.Reference(uri, stamp)
                }
            }
            currentCoroutineContext().ensureActive()
            // Recheck every source after the last hash; an earlier file may have changed meanwhile.
            files.forEach { entry -> openReference(entry, verified.getValue(entry.name)).close() }
            references.replace(verified)
            verifiedStamps = null
            try {
                for (entry in files) {
                    val copy = File(dir, entry.name)
                    check(!copy.exists() || copy.delete()) { "Cannot remove private model copy: ${entry.name}" }
                }
            } finally { old.values.forEach { references.releaseUnused(it.uri) } }
        } finally { granted.forEach { references.releaseUnused(it) } }
    }

    private fun stamps() = files.map { entry ->
        val attrs = Files.readAttributes(File(dir, entry.name).toPath(), BasicFileAttributes::class.java)
        check(attrs.isRegularFile && attrs.size() == entry.size) { "Model missing or wrong size: ${entry.name}" }
        Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
    }

    /** Private immutable files are hashed once per session, again if replaced or modified. */
    suspend fun verified(): ModelSet {
        if (!hasReferences()) return requireNotNull(ModelSet.resolve(verifiedFiles().toList()))
        val sources = verifiedSources()
        val paths = requiredNames.map { it to File(dir, it).absolutePath }
        return requireNotNull(ModelSet.resolve(paths)).copy(sources = sources)
    }

    internal suspend fun verifiedSources(): Map<String, ModelFile> {
        val external = references.all()
        if (external.isEmpty()) return verifiedFiles().mapValues { ModelFile.local(it.value) }
        return files.associate { entry ->
            val reference = external.getValue(entry.name)
            openReference(entry, reference).use { verifyExternal(it, entry) { } }
            entry.name to ModelFile.external { openReference(entry, reference) }
        }
    }

    internal suspend fun verifiedFiles(): Map<String, String> {
        currentCoroutineContext().ensureActive()
        check(!hasReferences()) { "External models require descriptor-based loading" }
        val current = stamps()
        // Providers without file identities cannot distinguish same-size replacements.
        if (current.any { it.key == null } || current != verifiedStamps) {
            for (entry in files) {
                currentCoroutineContext().ensureActive()
                check(ModelChecksum.sha256(File(dir, entry.name)) == entry.sha256) { "Model checksum mismatch: ${entry.name}" }
            }
            verifiedStamps = current
        }
        return files.associate { it.name to File(dir, it.name).absolutePath }
    }
}
