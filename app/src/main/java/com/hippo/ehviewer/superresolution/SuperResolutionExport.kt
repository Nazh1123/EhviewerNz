package com.hippo.ehviewer.superresolution

import com.hippo.lib.yorozuya.FileUtils
import com.hippo.unifile.UniFile
import com.hippo.ehviewer.image.WebpEncoder
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object SuperResolutionExport {
    const val DIRECTORY_NAME = "_suepr_resolution"

    suspend fun save(page: EnhancedTilePage, gallery: UniFile, sourceName: String, workspace: Long = 0,
        quality: SuperResolutionQuality = SuperResolutionQuality.Q95): UniFile {
        val final = page.finalImage
        if (final != null) {
            val extension = final.name?.substringAfterLast('.') ?: "png"
            val destination = directory(gallery, sourceName)
            val name = "${parts(sourceName).last()}_sr.$extension"
            if (destination.findFile(name)?.uri == final.uri) return final
            return publish(gallery, sourceName, extension) { output -> final.openInputStream().use { it.copyTo(output) } }
        }
        return encode(page, workspace, quality) { extension, writer -> publish(gallery, sourceName, extension, writer) }
    }

    internal suspend fun encode(page: EnhancedTilePage, workspace: Long,
        quality: SuperResolutionQuality = SuperResolutionQuality.Q95,
        publish: suspend (String, suspend (OutputStream) -> Unit) -> UniFile): UniFile {
        val webpQuality = quality.webpQuality
        if (SuperResolutionWebp.accepts(page.width, page.height, workspace, lossless = webpQuality == null) && WebpEncoder.nativeAvailable) {
            try { return publish("webp") { SuperResolutionWebp.write(page, it, webpQuality) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: IllegalStateException) { /* Workspace/encoder failure: retain the streaming PNG path. */ }
            catch (_: OutOfMemoryError) { /* No whole-page RGBA allocation is needed for PNG. */ }
        }
        return publish("png") { SuperResolutionPng.write(page, it) }
    }

    private fun parts(sourceName: String) = sourceName.replace('\\', '/').split('/').filter { it.isNotEmpty() }.map {
        require(it != "." && it != "..") { "Invalid source name" }
        var name = FileUtils.sanitizeFilename(it).trimEnd('.')
        while (name.toByteArray(Charsets.UTF_8).size > 220)
            name = name.substring(0, name.offsetByCodePoints(name.length, -1))
        name.ifBlank { "page" }
    }.ifEmpty { listOf("page") }

    private fun directory(gallery: UniFile, sourceName: String): UniFile {
        var directory = checkNotNull(gallery.createDirectory(DIRECTORY_NAME)) { "Cannot create SR export directory" }
        for (part in parts(sourceName).dropLast(1))
            directory = checkNotNull(directory.createDirectory(part)) { "Cannot create SR export directory" }
        return directory
    }

    /** UniFile keeps both filesystem and opaque SAF destinations on the same path. */
    internal suspend fun publish(gallery: UniFile, sourceName: String, extension: String = "png",
        writer: suspend (OutputStream) -> Unit): UniFile {
        require(extension in listOf("png", "webp"))
        val directory = directory(gallery, sourceName)
        val stem = "${parts(sourceName).last()}_sr"
        val name = "$stem.$extension"
        val temporary = checkNotNull(directory.createFile("$name.part")) { "Cannot create SR export" }
        var published = false
        try {
            temporary.openOutputStream(false).use { writer(it) }
            currentCoroutineContext().ensureActive()
            directory.findFile(name)?.let { check(it.isFile && it.delete()) { "Cannot replace SR export" } }
            check(temporary.renameTo(name)) { "Cannot publish SR export" }
            published = true
            // The newly published image replaces the previous format only after successful encoding.
            for (old in listOf("png", "webp").filter { it != extension })
                directory.findFile("$stem.$old")?.let { check(it.delete()) }
            return temporary
        } finally { if (!published) temporary.delete() }
    }
}
