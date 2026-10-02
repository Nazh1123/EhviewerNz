package com.hippo.ehviewer.translation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import li.joye.yakuyomi.engine.NativeLlm
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-private, content-addressed GGUF files. Import runs under TranslationRuntime.lock. */
class NativeModelStore internal constructor(private val context: Context,
    private val validateModel: (File) -> Unit = { NativeLlm(it.absolutePath).use { } }) {
    private val directory get() = File(context.noBackupFilesDir, "translation-llm")
    private val names get() = context.getSharedPreferences("translation_model_names", Context.MODE_PRIVATE)

    data class Installed(val id: String, val name: String, val size: Long)

    fun installed(): List<Installed> {
        val selected = TranslationSettings(context).read()
        return directory.listFiles().orEmpty().filter {
            it.isFile && it.extension == "gguf" && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}"))
        }.map {
            val id = it.nameWithoutExtension
            val fallback = NativeModelCatalog.models.firstOrNull { model -> model.sha256 == id }?.name
                ?: selected.nativeModelName.takeIf { _ -> selected.nativeModelId == id && selected.nativeModelName.isNotBlank() }
                ?: it.name
            Installed(id, names.getString(id, fallback) ?: fallback, it.length())
        }.sortedBy { it.name }
    }

    /** Caller holds TranslationRuntime.lock. Selecting does not remove other installed models. */
    suspend fun select(id: String) {
        currentCoroutineContext().ensureActive()
        val model = installed().firstOrNull { it.id == id } ?: error("Model missing")
        validateModel(file(id))
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
        val settings = TranslationSettings(context)
        val previous = settings.read()
        if (previous.nativeModelId == id) settings.clearNativeModel()
        if (model.exists() && !model.delete()) {
            if (previous.nativeModelId == id) settings.selectNativeModel(id, previous.nativeModelName)
            error("Cannot delete model")
        }
        check(names.edit().remove(id).commit()) { "Cannot clear model name" }
    }

    fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Import a GGUF model first" }
        return File(directory, "$id.gguf")
    }

    fun ready(options: TranslationOptions): Boolean = runCatching {
        val model = file(options.nativeModelId)
        model.isFile && model.length() > 24 && model.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))
        }
    }.getOrDefault(false)

    suspend fun import(uri: Uri) {
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
        val alreadyInstalled = destination.isFile
        if (temp != destination) Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        try {
            rememberCurrentModel()
            check(names.edit().putString(id, name).commit()) { "Cannot save model name" }
            TranslationSettings(context).selectNativeModel(id, name)
        } catch (error: Throwable) {
            // A failed selection must never delete a model that was already installed.
            if (!alreadyInstalled && TranslationSettings(context).read().nativeModelId != id) destination.delete()
            throw error
        }
    }
}
