package com.hippo.ehviewer.superresolution

import com.hippo.lib.yorozuya.FileUtils
import com.hippo.unifile.UniFile
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object SuperResolutionExport {
    const val DIRECTORY_NAME = "_suepr_resolution"

    suspend fun save(page: EnhancedTilePage, gallery: UniFile, sourceName: String): UniFile =
        publish(gallery, sourceName) { SuperResolutionPng.write(page, it) }

    /** UniFile keeps both filesystem and opaque SAF destinations on the same path. */
    internal suspend fun publish(gallery: UniFile, sourceName: String, writer: suspend (OutputStream) -> Unit): UniFile {
        val parts = sourceName.replace('\\', '/').split('/').filter { it.isNotEmpty() }.map {
            require(it != "." && it != "..") { "Invalid source name" }
            var name = FileUtils.sanitizeFilename(it).trimEnd('.')
            while (name.toByteArray(Charsets.UTF_8).size > 220)
                name = name.substring(0, name.offsetByCodePoints(name.length, -1))
            name.ifBlank { "page" }
        }.ifEmpty { listOf("page") }
        var directory = checkNotNull(gallery.createDirectory(DIRECTORY_NAME)) { "Cannot create SR export directory" }
        for (part in parts.dropLast(1))
            directory = checkNotNull(directory.createDirectory(part)) { "Cannot create SR export directory" }
        val name = "${parts.last()}_sr.png"
        val temporary = checkNotNull(directory.createFile("$name.part")) { "Cannot create SR export" }
        var published = false
        try {
            temporary.openOutputStream(false).use { writer(it) }
            currentCoroutineContext().ensureActive()
            directory.findFile(name)?.let { check(it.isFile && it.delete()) { "Cannot replace SR export" } }
            check(temporary.renameTo(name)) { "Cannot publish SR export" }
            published = true
            return temporary
        } finally { if (!published) temporary.delete() }
    }
}
