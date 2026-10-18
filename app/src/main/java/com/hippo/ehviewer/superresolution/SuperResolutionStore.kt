package com.hippo.ehviewer.superresolution

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hippo.ehviewer.gallery.GalleryProcessingProgress
import com.hippo.ehviewer.image.WebpEncoder
import com.hippo.unifile.UniFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Lossless resumable checkpoints compacted into one final image. Calls hold the model lock on IO. */
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
            val extension = WebpEncoder.losslessExtension(bitmap)
            temporary.outputStream().use { WebpEncoder.writeLossless(bitmap, it) }
            currentCoroutineContext().ensureActive()
            atomicPublish(temporary, File(page.directory, "$index.$extension"))
            File(page.directory, "$index.${if (extension == "webp") "png" else "webp"}").delete()
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
        page.finalImage?.let { image ->
            val retained = if (localFile(image)?.parentFile == page.directory) {
                val file = File(target.directory, checkNotNull(image.name))
                val temporary = File.createTempFile("retain-", ".tmp", target.directory)
                try {
                    image.openInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    atomicPublish(temporary, file)
                } finally { temporary.delete() }
                checkNotNull(UniFile.fromFile(file))
            } else image
            target.useFinalImage(retained)
            writePage(target, true)
            page.useFinalImage(retained)
            page.directory.deleteRecursively()
            return target
        }
        for (index in 0 until page.count) if (!target.complete(index)) {
            val temporary = File.createTempFile("retain-", ".tmp", target.directory)
            try {
                val file = page.file(index)
                file.copyTo(temporary, overwrite = true)
                atomicPublish(temporary, File(target.directory, file.name))
            }
            finally { temporary.delete() }
        }
        finish(target)
        return target
    }

    /** Persist a final image/locator before removing any pixels required for recovery. */
    suspend fun compact(key: String, page: EnhancedTilePage, gallery: UniFile?, sourceName: String): UniFile {
        val previous = page.finalImage
        val workspace = SuperResolutionWebp.budget(app)
        val image = if (gallery != null) SuperResolutionExport.save(page, gallery, sourceName, workspace)
        else previous ?: SuperResolutionExport.encode(page, workspace) { extension, writer ->
            val target = File(page.directory, "result.$extension")
            val temporary = File.createTempFile("final-", ".tmp", page.directory)
            try {
                temporary.outputStream().use { writer(it) }
                currentCoroutineContext().ensureActive()
                atomicPublish(temporary, target)
                checkNotNull(UniFile.fromFile(target))
            } finally { temporary.delete() }
        }
        // A corrupt/unreadable export must never replace usable checkpoints.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        image.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth == page.width && bounds.outHeight == page.height) { "Invalid SR final image" }
        // Commit metadata first; on failure the current page and checkpoints remain usable.
        writePage(page, true, image)
        page.useFinalImage(image)
        page.removeTileImages()
        for (extension in listOf("png", "webp")) {
            val obsolete = File(page.directory, "result.$extension")
            if (obsolete.isFile && localFile(image) != obsolete) check(obsolete.delete())
        }
        if (previous != null && previous.uri != image.uri && localFile(previous)?.parentFile == page.directory) previous.delete()
        // Old monolithic 2x images are superseded as well.
        invalidate(key)
        for (root in listOf(cache, persistent)) {
            val obsolete = File(root, "$key.tiles")
            if (obsolete != page.directory && obsolete.exists()) obsolete.deleteRecursively()
        }
        prune(page.directory)
        return image
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
        if (version !in 1..3 || (completed && !json.getBoolean("complete"))) return@runCatching null
        val w = json.getInt("width"); val h = json.getInt("height")
        if (w !in 1..32768 || h !in 1..32768) return@runCatching null
        val scale = if (version == 1) 2 else json.getInt("scale")
        if (scale != scaleFor(w, h)) return@runCatching null
        EnhancedTilePage(directory, w, h, scale).also { page ->
            if (version == 3 && json.has("result")) {
                val uri = Uri.parse(json.getString("result"))
                val image = checkNotNull(if (uri.scheme == "file") UniFile.fromFile(localFile(uri))
                    else UniFile.fromUri(app, uri))
                check(image.isFile)
                if (json.has("resultSize")) check(image.length() == json.getLong("resultSize"))
                if (json.optLong("resultModified") > 0) check(image.lastModified() == json.getLong("resultModified"))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                image.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                check(bounds.outWidth == page.width && bounds.outHeight == page.height)
                localFile(image)?.let { check(completeImage(it)) }
                page.useFinalImage(image)
            }
        }
    }.getOrNull()

    private fun writePage(page: EnhancedTilePage, complete: Boolean, result: UniFile? = page.finalImage) {
        val temporary = File.createTempFile("page-", ".tmp", page.directory)
        try {
            temporary.writeText(JSONObject().put("version", if (result == null) 2 else 3).put("width", page.sourceWidth)
                .put("height", page.sourceHeight).put("scale", page.scale).put("complete", complete)
                .apply { if (result != null) {
                    put("result", result.uri.toString())
                    put("resultSize", result.length())
                    put("resultModified", result.lastModified())
                } }.toString())
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
        private fun localFile(image: UniFile): File? = if (image.uri.scheme == "file") localFile(image.uri) else null
        private fun localFile(uri: Uri): File {
            // Android file URIs use absolute paths; host-side Android tests can also use drive letters.
            val path = checkNotNull(uri.schemeSpecificPart).removePrefix("//")
            return File(if (path.matches(Regex("^/[A-Za-z]:.*"))) path.drop(1) else path)
        }
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
        internal fun completeImage(file: File): Boolean {
            if (completePng(file)) return true
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    if (input.length() < 20) return@use false
                    val header = ByteArray(12).also(input::readFully)
                    val size = (0..3).fold(0L) { result, i -> result or ((header[4 + i].toLong() and 255) shl (8 * i)) }
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WEBP" && size + 8 == input.length()
                }
            }.getOrDefault(false)
        }
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
