package com.hippo.ehviewer.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.hippo.ehviewer.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.hippo.ehviewer.translation.engine.NativeLlm
import com.hippo.ehviewer.translation.engine.NativePrefixCache
import com.hippo.ehviewer.translation.engine.NativeSampling
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Content-addressed private GGUF files and persisted external references. Maintenance holds TranslationRuntime.lock. */
class NativeModelStore internal constructor(private val context: Context,
    private val validateExternalModel: (Int) -> Unit = { NativeLlm("", fileDescriptor = it).use { } },
    private val validateModel: (File) -> Unit = { NativeLlm(it.absolutePath).use { } }) {
    private val directory get() = File(context.noBackupFilesDir, "translation-llm")
    private val names get() = context.getSharedPreferences("translation_model_names", Context.MODE_PRIVATE)

    enum class ImportMode { COPY, REFERENCE }

    data class Installed(val id: String, val name: String, val size: Long,
                         val external: Boolean = false, val privateBytes: Long = size)

    private fun externalUri(id: String): Uri? = names.getString("uri_$id", null)?.let(Uri::parse)

    fun installed(): List<Installed> {
        val selected = TranslationSettings(context).read()
        val privateFiles = directory.listFiles().orEmpty().filter {
            it.isFile && it.extension == "gguf" && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}"))
        }.associateBy { it.nameWithoutExtension }
        val references = names.all.keys.filter { it.startsWith("uri_") }.map { it.removePrefix("uri_") }
            .filter { it.matches(Regex("[a-f0-9]{64}")) }
        return (privateFiles.keys + references).map { id ->
            val external = externalUri(id) != null
            val privateBytes = privateFiles[id]?.length() ?: 0L
            val fallback = NativeModelCatalog.models.firstOrNull { model -> model.sha256 == id }?.name
                ?: selected.nativeModelName.takeIf { _ -> selected.nativeModelId == id && selected.nativeModelName.isNotBlank() }
                ?: "$id.gguf"
            Installed(id, names.getString(id, fallback) ?: fallback,
                if (external) names.getLong("size_$id", 0L) else privateBytes, external, privateBytes)
        }.sortedBy { it.name }
    }

    /** Caller holds TranslationRuntime.lock. Selecting does not remove other installed models. */
    suspend fun select(id: String) {
        currentCoroutineContext().ensureActive()
        val model = installed().firstOrNull { it.id == id } ?: error("Model missing")
        val uri = externalUri(id)
        if (uri == null) validateModel(file(id)) else withExternalFile(id, uri) { validateExternalModel(it.fd) }
        currentCoroutineContext().ensureActive()
        rememberCurrentModel()
        TranslationSettings(context).selectNativeModel(id, model.name)
    }

    private fun rememberCurrentModel() {
        val previous = TranslationSettings(context).read()
        if (previous.nativeModelId.isNotEmpty() && previous.nativeModelName.isNotBlank()) {
            check(names.edit().putString(previous.nativeModelId, previous.nativeModelName).commit()) {
                "Cannot save model name"
            }
        }
    }

    /** Only deletes this store's content-addressed model, never the imported source file. */
    fun delete(id: String) {
        val model = file(id)
        val uri = externalUri(id)
        val settings = TranslationSettings(context)
        val previous = settings.read()
        if (previous.nativeModelId == id) settings.clearNativeModel()
        if (model.exists() && !model.delete()) {
            if (previous.nativeModelId == id) settings.selectNativeModel(id, previous.nativeModelName)
            error("Cannot delete model")
        }
        check(names.edit().remove(id).remove("uri_$id").remove("size_$id").remove("stamp_$id").commit()) {
            "Cannot clear model name"
        }
        releaseUnusedPermission(uri)
    }

    fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Import a GGUF model first" }
        return File(directory, "$id.gguf")
    }

    fun ready(options: TranslationOptions): Boolean = runCatching {
        val uri = externalUri(options.nativeModelId)
        if (uri != null) return@runCatching withExternalFile(options.nativeModelId, uri) { descriptor ->
            hasGgufHeader(descriptor)
        }
        val model = file(options.nativeModelId)
        model.isFile && model.length() > 24 && model.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))
        }
    }.getOrDefault(false)

    fun load(id: String, cache: NativePrefixCache? = null, sampling: NativeSampling = NativeSampling.GREEDY): NativeLlm {
        val uri = externalUri(id) ?: return NativeLlm(file(id).absolutePath, cache, sampling)
        return withExternalFile(id, uri) { NativeLlm("external:$id", cache, sampling, it.fd) }
    }

    private fun stamp(descriptor: ParcelFileDescriptor): String {
        val stat = Os.fstat(descriptor.fileDescriptor)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 24) {
            context.getString(R.string.translation_model_external_unsupported)
        }
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        // Remounts can change device/inode numbers; shared-storage permission/xattr updates change ctime.
        // Neither means the GGUF contents changed. Use size and data modification time instead.
        return "${stat.st_size}:${stat.st_mtime}"
    }

    private fun hasGgufHeader(descriptor: ParcelFileDescriptor): Boolean {
        val magic = ByteArray(4)
        val count = Os.read(descriptor.fileDescriptor, magic, 0, magic.size)
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        return count == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))
    }

    private fun <T> withExternalFile(id: String, uri: Uri, block: (ParcelFileDescriptor) -> T): T {
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "Cannot open model" }
        return descriptor.use {
            check(stamp(it) == names.getString("stamp_$id", null)) {
                context.getString(R.string.translation_model_external_changed)
            }
            block(it)
        }
    }

    private fun releaseUnusedPermission(uri: Uri?) {
        if (uri?.scheme != "content" || names.all.values.any { it == uri.toString() } || ImageModelReferences.uses(context, uri)) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    suspend fun import(uri: Uri, mode: ImportMode = ImportMode.COPY) {
        if (mode == ImportMode.REFERENCE) { importReference(uri); return }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model directory" }
        val resolver = context.contentResolver
        val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: if (uri.scheme == "file") File(uri.path.orEmpty()).name else "model.gguf"
        val temp = File.createTempFile("import-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(resolver.openInputStream(uri)) { "Cannot open model" }.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            publish(temp, name, digest.digest().joinToString("") { "%02x".format(it) })
        } finally { temp.delete() }
    }

    private suspend fun importReference(uri: Uri) {
        val resolver = context.contentResolver
        val permissionAlreadyHeld = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        val oldUriIds = names.all.filterValues { it == uri.toString() }.keys
            .filter { it.startsWith("uri_") }.map { it.removePrefix("uri_") }
        if (uri.scheme == "content") resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: uri.lastPathSegment ?: "model.gguf"
            val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) { "Cannot open model" }
            val (id, size, signature) = descriptor.use {
                val before = stamp(it)
                require(hasGgufHeader(it)) { "Please choose a GGUF model" }
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = Os.read(it.fileDescriptor, buffer, 0, buffer.size)
                    if (count == 0) break
                    digest.update(buffer, 0, count)
                    size += count
                }
                check(before == stamp(it) && size == it.statSize) { context.getString(R.string.translation_model_external_changed) }
                validateExternalModel(it.fd) // Also verifies mmap support; never silently copies an external import.
                check(before == stamp(it)) { context.getString(R.string.translation_model_external_changed) }
                Triple(digest.digest().joinToString("") { byte -> "%02x".format(byte) }, size, before)
            }
            currentCoroutineContext().ensureActive()
            val previous = TranslationSettings(context).read()
            val previousUri = externalUri(id)
            val previousName = names.getString(id, null)
            val previousSize = names.getLong("size_$id", 0L)
            val previousStamp = names.getString("stamp_$id", null)
            rememberCurrentModel()
            check(names.edit().putString(id, name).putString("uri_$id", uri.toString())
                .putLong("size_$id", size).putString("stamp_$id", signature).commit()) { "Cannot save model reference" }
            try {
                TranslationSettings(context).selectNativeModel(id, name)
                // Switching the same model to reference mode also frees its existing private copy.
                val copy = file(id)
                check(!copy.exists() || copy.delete()) { "Cannot remove private model copy" }
            } catch (error: Throwable) {
                names.edit().putString(id, previousName).putString("uri_$id", previousUri?.toString())
                    .putLong("size_$id", previousSize).putString("stamp_$id", previousStamp).commit()
                if (previous.nativeModelId.isEmpty()) TranslationSettings(context).clearNativeModel()
                else TranslationSettings(context).selectNativeModel(previous.nativeModelId, previous.nativeModelName)
                throw error
            }
            // Reimporting a modified document replaces its old content identity, preserving unrelated copies.
            for (oldId in oldUriIds.filter { it != id }) {
                check(names.edit().remove("uri_$oldId").remove("size_$oldId").remove("stamp_$oldId").commit()) {
                    "Cannot clear old model reference"
                }
            }
            releaseUnusedPermission(previousUri)
        } catch (error: Throwable) {
            if (!permissionAlreadyHeld) releaseUnusedPermission(uri)
            throw error
        }
    }

    /** Downloads are staged here so importing only requires a rename, not a second GB-sized copy. */
    internal fun createDownloadFile(): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model directory" }
        return File.createTempFile("download-", ".part", directory)
    }

    /** Caller holds TranslationRuntime.lock and has checked the download's size and SHA-256. */
    internal suspend fun publish(temp: File, name: String, id: String) {
        currentCoroutineContext().ensureActive()
        require(temp.canonicalFile.parentFile == directory.canonicalFile)
        require(temp.length() > 24 && temp.inputStream().use {
            val magic = ByteArray(4)
            it.read(magic) == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))
        }) { "Please choose a GGUF model" }
        // Validate architecture, tensors, chat template and context allocation before replacing anything.
        validateModel(temp)
        currentCoroutineContext().ensureActive()
        val destination = file(id)
        val previousUri = externalUri(id)
        val alreadyInstalled = destination.isFile
        if (temp != destination) Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        try {
            rememberCurrentModel()
            check(names.edit().putString(id, name).commit()) { "Cannot save model name" }
            TranslationSettings(context).selectNativeModel(id, name)
            check(names.edit().remove("uri_$id").remove("size_$id").remove("stamp_$id").commit()) {
                "Cannot clear model reference"
            }
            releaseUnusedPermission(previousUri)
        } catch (error: Throwable) {
            // A failed selection must never delete a model that was already installed.
            if (!alreadyInstalled && TranslationSettings(context).read().nativeModelId != id) destination.delete()
            throw error
        }
    }
}
