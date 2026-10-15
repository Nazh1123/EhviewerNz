package com.hippo.ehviewer.superresolution

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Content-addressed PNGs. All calls hold SuperResolutionModels.lock on IO. */
internal class SuperResolutionStore(context: Context, private val identity: String,
                                    private val options: SuperResolutionOptions) {
    private val cacheRoot = File(context.cacheDir, "super-resolution-pages")
    private val persistentRoot = File(context.noBackupFilesDir, "super-resolution-pages")
    private val gallery = digest(identity.toByteArray())
    private val cache get() = File(cacheRoot, gallery)
    private val persistent get() = File(persistentRoot, gallery)

    suspend fun key(source: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(options.modelIdentity.toByteArray())
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun find(key: String): File? = listOf(File(persistent, "$key.png"), File(cache, "$key.png"))
        .firstOrNull { file ->
            if (!file.isFile) false else if (!completePng(file)) { file.delete(); false } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) { file.setLastModified(System.currentTimeMillis()); true }
                else { file.delete(); false }
            }
        }

    suspend fun save(key: String, bitmap: Bitmap): File {
        val directory = if (options.persist) persistent else cache
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create super-resolution directory" }
        val target = File(directory, "$key.png")
        val temporary = File.createTempFile("result-", ".tmp", directory)
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(target)) { "Cannot publish super-resolution result" }
        } finally { temporary.delete() }
        prune(target)
        return target
    }

    fun retain(file: File): File {
        if (!options.persist || file.parentFile == persistent) return file
        check(persistent.isDirectory || persistent.mkdirs())
        val target = File(persistent, file.name)
        val temp = File.createTempFile("retain-", ".tmp", persistent)
        try { file.copyTo(temp, overwrite = true); check(temp.renameTo(target)) }
        finally { temp.delete() }
        return target
    }

    fun invalidate(key: String) { File(cache, "$key.png").delete(); File(persistent, "$key.png").delete() }

    private fun prune(keep: File) {
        val files = cacheRoot.walkTopDown().filter { it.isFile }.toList().sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        val limit = options.cacheMiB * 1024L * 1024
        for (file in files) if (size > limit && file != keep) {
            val bytes = file.length()
            if (file.delete()) size -= bytes
        }
    }

    companion object {
        // Decoder bounds alone can accept a truncated file. Require the PNG envelope as well.
        private fun completePng(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use {
                if (it.length() < 45) return@use false
                val signature = ByteArray(8).also(it::readFully)
                it.seek(it.length() - 12)
                val ending = ByteArray(12).also(it::readFully)
                signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
                    ending.contentEquals(byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126))
            }
        }.getOrDefault(false)
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun clear(context: Context, persistent: Boolean = false) {
            val root = File(if (persistent) context.noBackupFilesDir else context.cacheDir, "super-resolution-pages")
            check(!root.exists() || root.deleteRecursively()) { "Cannot remove super-resolution results" }
        }
    }
}
