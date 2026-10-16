package com.hippo.ehviewer.superresolution

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hippo.ehviewer.gallery.GalleryProcessingProgress
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Content-addressed PNGs. All calls hold SuperResolutionModels.lock on IO. */
internal class SuperResolutionStore(context: Context, private val identity: String,
                                    private val options: SuperResolutionOptions) {
    private val app = context.applicationContext
    private val cacheRoot = File(context.cacheDir, "super-resolution-pages")
    private val persistentRoot = File(context.noBackupFilesDir, "super-resolution-pages")
    private val gallery = digest(identity.toByteArray())
    private val cache get() = File(cacheRoot, gallery)
    private val persistent get() = File(persistentRoot, gallery)

    fun scaleFor(width: Int, height: Int) = options.scale.resolve(width, height)
    val acceptsLegacy get() = options.scale == SuperResolutionScale.X2

    suspend fun key(source: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(options.modelIdentity.toByteArray())
        // Preserve existing 2x results; isolate other scales and the automatic policy.
        if (!acceptsLegacy) hash.update(":scale-${options.scale.value}-v1".toByteArray())
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
            if (!file.isFile) false else if (!completePng(file)) { deleteResult(file); false } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) { file.setLastModified(System.currentTimeMillis()); true }
                else { deleteResult(file); false }
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

    private fun deleteResult(file: File) {
        if (file.delete()) GalleryProcessingProgress.forget(app, listOf(Uri.fromFile(file)))
    }

    fun invalidate(key: String) { deleteResult(File(cache, "$key.png")); deleteResult(File(persistent, "$key.png")) }

    fun findTiles(key: String): EnhancedTilePage? = listOf(persistent, cache).firstNotNullOfOrNull { root ->
        val directory = File(root, "$key.tiles")
        readPage(directory, completed = true)?.also { directory.setLastModified(System.currentTimeMillis()) }
    }

    fun checkpoint(key: String, width: Int, height: Int): EnhancedTilePage {
        val scale = scaleFor(width, height)
        val directory = File(if (options.persist) persistent else cache, "$key.tiles")
        readPage(directory, completed = false)?.takeIf {
            it.sourceWidth == width && it.sourceHeight == height && it.scale == scale
        }?.let { return it }
        if (directory.exists()) {
            check(directory.deleteRecursively())
            GalleryProcessingProgress.forgetUnder(app, Uri.fromFile(directory))
        }
        check(directory.mkdirs())
        val page = EnhancedTilePage(directory, width, height, scale)
        writePage(page, false)
        return page
    }

    suspend fun saveTile(page: EnhancedTilePage, index: Int, bitmap: Bitmap) {
        val temporary = File.createTempFile("tile-", ".tmp", page.directory)
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            currentCoroutineContext().ensureActive()
            atomicPublish(temporary, page.file(index))
            page.saved(index)
        } finally { temporary.delete() }
    }

    fun finish(page: EnhancedTilePage) {
        check((0 until page.count).all(page::complete)) { "Incomplete SR checkpoint" }
        writePage(page, true)
        prune(page.directory)
    }

    fun retainTiles(page: EnhancedTilePage): EnhancedTilePage {
        if (!options.persist || page.directory.parentFile == persistent) return page
        val key = page.directory.name.removeSuffix(".tiles")
        val target = checkpoint(key, page.sourceWidth, page.sourceHeight)
        for (index in 0 until page.count) if (!target.complete(index)) {
            val temporary = File.createTempFile("retain-", ".tmp", target.directory)
            try { page.file(index).copyTo(temporary, overwrite = true); atomicPublish(temporary, target.file(index)) }
            finally { temporary.delete() }
        }
        finish(target)
        return target
    }

    fun invalidateTiles(key: String) {
        listOf(cache, persistent).forEach { root ->
            val directory = File(root, "$key.tiles")
            check(!directory.exists() || directory.deleteRecursively())
            GalleryProcessingProgress.forgetUnder(app, Uri.fromFile(directory))
        }
    }

    private fun readPage(directory: File, completed: Boolean): EnhancedTilePage? = runCatching {
        val json = JSONObject(File(directory, "page.json").readText())
        val version = json.getInt("version")
        if (version !in 1..2 || (completed && !json.getBoolean("complete"))) return@runCatching null
        val w = json.getInt("width"); val h = json.getInt("height")
        if (w !in 1..32768 || h !in 1..32768) return@runCatching null
        val scale = if (version == 1) 2 else json.getInt("scale")
        if (scale != scaleFor(w, h)) return@runCatching null
        EnhancedTilePage(directory, w, h, scale)
    }.getOrNull()

    private fun writePage(page: EnhancedTilePage, complete: Boolean) {
        val temporary = File.createTempFile("page-", ".tmp", page.directory)
        try {
            temporary.writeText(JSONObject().put("version", 2).put("width", page.sourceWidth)
                .put("height", page.sourceHeight).put("scale", page.scale).put("complete", complete).toString())
            atomicPublish(temporary, File(page.directory, "page.json"))
        } finally { temporary.delete() }
    }

    private fun prune(keep: File) {
        val files = cacheRoot.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
            .sortedBy { it.lastModified() }
        fun bytes(file: File) = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
        var size = files.sumOf(::bytes)
        val limit = options.cacheMiB * 1024L * 1024
        for (file in files) if (size > limit && file != keep) {
            val bytes = bytes(file)
            if (file.deleteRecursively()) {
                size -= bytes
                GalleryProcessingProgress.forgetUnder(app, Uri.fromFile(file))
            }
        }
    }

    companion object {
        internal fun galleryRoots(context: Context, galleryId: Long): List<File> =
            listOf(context.cacheDir, context.noBackupFilesDir).map {
                File(File(it, "super-resolution-pages"), digest("gallery:$galleryId".toByteArray()))
            }
        // Decoder bounds alone can accept a truncated file. Require the PNG envelope as well.
        internal fun completePng(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use {
                if (it.length() < 45) return@use false
                val signature = ByteArray(8).also(it::readFully)
                it.seek(it.length() - 12)
                val ending = ByteArray(12).also(it::readFully)
                signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
                    ending.contentEquals(byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126))
            }
        }.getOrDefault(false)
        private fun atomicPublish(temporary: File, target: File) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun clear(context: Context, persistent: Boolean = false) {
            val root = File(if (persistent) context.noBackupFilesDir else context.cacheDir, "super-resolution-pages")
            check(!root.exists() || root.deleteRecursively()) { "Cannot remove super-resolution results" }
            GalleryProcessingProgress.forgetUnder(context, Uri.fromFile(root))
        }
    }
}
