package com.hippo.ehviewer.translation

import com.hippo.unifile.UniFile
import java.io.File
import java.io.OutputStream
import li.joye.yakuyomi.engine.TranslationResume
import org.json.JSONArray
import org.json.JSONObject

/** Rendered results and text checkpoints. Gallery files are independent of the evictable cache. */
internal class TranslationResultStore(
    cacheDir: File,
    private val galleryDir: UniFile?,
    private val options: TranslationOptions,
    private val galleryId: Long?,
    downloaded: Boolean,
    private val legacyDir: File? = null,
) {
    private val cache = TranslationCache(cacheDir, options.cacheLimitBytes)
    private val cacheDirectory = requireNotNull(UniFile.fromFile(cacheDir))
    // Imported archives have no writable gallery directory; they use the ordinary cache.
    val persists = options.persistDownloaded && downloaded && galleryId != null && galleryDir != null

    fun key(source: File): String = (galleryId?.let { "g${it}_" } ?: "") + cache.key(source, options)
    fun preparationKey(source: File): String = cache.preparationKey(source, options)
    private fun legacy(key: String, extension: String) = legacyDir?.let { File(it, "$key.$extension") }
    private fun saved(key: String, extension: String): UniFile? =
        galleryDir?.findFile(DIRECTORY_NAME)?.takeIf { it.isDirectory }
            ?.findFile("$key.$extension")?.takeIf { it.isFile }

    private fun existing(key: String, extension: String): UniFile? =
        (if (galleryId != null) saved(key, extension)
            ?: UniFile.fromFile(legacy(key, extension)?.takeIf { it.isFile }) else null)
            ?: cacheDirectory.findFile("$key.$extension")?.takeIf { it.isFile }

    fun existingImage(key: String): UniFile? = existing(key, "png") ?: existing(key, "partial")

    fun readResume(key: String): TranslationResume? {
        val image = existingImage(key)?.takeIf { isPartial(it) } ?: return null
        val checkpoint = image.parentFile?.findFile("$key.regions") ?: return null
        return runCatching {
            // Bound damaged/untrusted SAF metadata before decoding JSON.
            val bytes = checkpoint.openInputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("version") == 1 && json.getString("key") == key)
            val regions = json.getJSONArray("regions").let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            val entries = json.getJSONObject("translations")
            val translations = entries.keys().asSequence().associate { it.toInt() to entries.getString(it) }
            require(translations.all { it.key in regions.indices && it.value.isNotBlank() })
            TranslationResume(regions, translations)
        }.getOrNull()
    }

    fun writePartial(key: String, resume: TranslationResume, writer: (OutputStream) -> Unit): UniFile {
        require(resume.missingCount > 0)
        val json = JSONObject().put("version", 1).put("key", key)
            .put("regions", JSONArray(resume.regions))
            .put("translations", JSONObject().apply { resume.translations.forEach { (index, text) -> put(index.toString(), text) } })
        write(key, "regions") { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        // A distinct image extension preserves partial status even if metadata is damaged or lost.
        return write(key, "partial", writer)
    }

    private fun outputDirectory(): UniFile = if (persists) {
        checkNotNull(galleryDir).let { gallery ->
            check(gallery.isDirectory) { "Gallery download directory unavailable" }
            checkNotNull(gallery.createDirectory(DIRECTORY_NAME)) { "Cannot create _translated directory" }
        }
    } else cacheDirectory

    /** Publish only fully written files, keeping temporary output out of cache lookups. */
    private fun write(key: String, extension: String, writer: (OutputStream) -> Unit): UniFile {
        val dir = outputDirectory()
        val name = "$key.$extension"
        val temp = checkNotNull(dir.createFile("$name.part")) { "Cannot create translated page" }
        var published = false
        try {
            temp.openOutputStream(false).use(writer)
            dir.findFile(name)?.let { check(it.isFile && it.delete()) { "Cannot replace translated page" } }
            check(temp.renameTo(name)) { "Cannot publish translated page" }
            published = true
            return temp
        } finally {
            if (!published) temp.delete()
        }
    }

    fun writeImage(key: String, writer: (OutputStream) -> Unit): UniFile = write(key, "png", writer).also {
        for (extension in listOf("partial", "regions")) {
            cacheDirectory.findFile("$key.$extension")?.delete()
            if (galleryId != null) { saved(key, extension)?.delete(); legacy(key, extension)?.delete() }
        }
    }

    /** Move legacy results and adopt cache hits when persistent saving is enabled. */
    fun retain(key: String, file: UniFile): UniFile {
        val extension = if (isPartial(file)) "partial" else "png"
        val retained = if (persists && file.uri != saved(key, extension)?.uri) {
            if (isPartial(file)) {
                val checkpoint = file.parentFile?.findFile("$key.regions")
                if (checkpoint != null) write(key, "regions") { output -> checkpoint.openInputStream().use { it.copyTo(output) } }
            }
            write(key, extension) { output -> file.openInputStream().use { it.copyTo(output) } }
        } else file
        if (persists) {
            legacy(key, extension)?.delete()
            if (isPartial(file)) legacy(key, "regions")?.delete()
        }
        touch(retained)
        return retained
    }

    fun isSkipped(key: String): Boolean {
        if (galleryId != null && saved(key, "skip") != null) return true
        val old = legacy(key, "skip")?.takeIf { galleryId != null && it.isFile }
        if (old == null && !cache.skipped(key).isFile) return false
        if (persists) {
            recordSkipped(key)
            old?.delete()
        } else cache.touch(old ?: cache.skipped(key))
        return true
    }

    fun recordSkipped(key: String) {
        write(key, "skip") { it.write("skipped".toByteArray(Charsets.UTF_8)) }
        // Adopting one persistent marker must not evict other results awaiting adoption.
        if (!persists) cache.prune()
    }

    fun invalidate(key: String) {
        cache.invalidate(key)
        if (galleryId != null) {
            for (extension in listOf("png", "skip", "partial", "regions")) {
                saved(key, extension)?.let { check(it.delete()) { "Cannot delete translated result" } }
                legacy(key, extension)?.let { check(!it.exists() || it.delete()) }
            }
        }
    }

    fun prune() = cache.prune()

    companion object {
        const val DIRECTORY_NAME = "_translated"
        fun isPartial(file: UniFile) = file.name?.endsWith(".partial") == true

        fun touch(file: UniFile) {
            if (UniFile.isFileUri(file.uri)) file.uri.path?.let { File(it).setLastModified(System.currentTimeMillis()) }
        }

        /** Exact gallery prefixes also protect unrelated files in shared/imported directories. */
        fun deleteGalleries(cacheDir: File, legacyDir: File, galleryIds: Set<Long>,
                            galleryDirs: Map<Long, UniFile> = emptyMap()): Boolean {
            var success = true
            val directories = mutableListOf(requireNotNull(UniFile.fromFile(cacheDir)) to galleryIds,
                requireNotNull(UniFile.fromFile(legacyDir)) to galleryIds)
            for ((gid, gallery) in galleryDirs) {
                if (gid !in galleryIds) continue
                gallery.findFile(DIRECTORY_NAME)?.let { directories.add(it to setOf(gid)) }
            }
            for ((dir, ids) in directories) {
                if (!dir.exists()) continue
                if (!dir.isDirectory) { success = false; continue }
                val files = dir.listFiles()
                if (files == null) { success = false; continue }
                for (file in files) {
                    val name = file.name ?: continue
                    val gid = name.substringBefore('_').removePrefix("g").toLongOrNull()
                    if (name.startsWith("g") && gid in ids && file.isFile &&
                        name.substringAfterLast('.') in listOf("png", "skip", "partial", "regions", "part")) {
                        if (!file.delete()) success = false
                    }
                }
            }
            return success
        }
    }
}
