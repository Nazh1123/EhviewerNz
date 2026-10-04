package com.hippo.ehviewer.translation

import com.hippo.lib.yorozuya.FileUtils
import com.hippo.unifile.UniFile
import java.io.File
import java.io.OutputStream
import li.joye.yakuyomi.engine.TranslationResume
import org.json.JSONArray
import org.json.JSONObject

/** Sparse overlays are found by source name; text checkpoints only support unfinished translations. */
internal class TranslationResultStore(
    cacheDir: File,
    private val galleryDir: UniFile?,
    private val options: TranslationOptions,
    private val galleryId: Long?,
    downloaded: Boolean,
    private val sourceName: String? = null,
    private val legacyDir: File? = null,
) {
    private val cache = TranslationCache(cacheDir, options.cacheLimitBytes)
    private val cacheDirectory = requireNotNull(UniFile.fromFile(cacheDir))
    val persists = options.persistDownloaded && downloaded && galleryId != null && galleryDir != null

    fun key(source: File): String = (galleryId?.let { "g${it}_" } ?: "") + cache.key(source, options)
    fun preparationKey(source: File): String = cache.preparationKey(source, options)

    /** Probe resolved identities without restoring a previous gallery's language decision. */
    fun lookupKeys(source: File, canonicalKey: String = key(source)): List<String> {
        val candidates = (listOf(options.source) + TranslationLanguages.sources + TranslationLanguages.detectedSources)
            .distinct().map { options.copy(source = it) }
        val identities = candidates.map { it.cacheIdentity() } + candidates.mapNotNull { it.legacyOverlayIdentity() }
        return listOf(canonicalKey) + cache.keys(source, identities.distinct())
            .map { (galleryId?.let { id -> "g${id}_" } ?: "") + it }
    }

    fun discardCachedAliases(keys: List<String>, keep: String) {
        keys.filter { it != keep }.forEach(cache::invalidate)
    }

    data class Hit(val key: String, val image: UniFile?)

    fun findResult(keys: List<String>): Hit? {
        // A complete result wins over a partial preview from another compatible identity.
        for (extension in listOf("png", "partial")) for (key in keys)
            existing(key, extension)?.let { return Hit(key, it) }
        for (key in keys) if (isSkipped(key)) return Hit(key, null)
        return null
    }

    /** Named overlays need neither the original download nor a content hash to be displayed. */
    fun findSavedResult(): Hit? {
        if (sourceName == null) return null
        val key = (galleryId?.let { "g${it}_" } ?: "") + "named"
        for (extension in listOf("png", "partial")) saved(key, extension)?.let { return Hit(key, it) }
        return if (saved(key, "skip") != null) Hit(key, null) else null
    }

    private fun legacyKeys(source: File): List<String> {
        val japanese = options.copy(source = "ja")
        val identity = japanese.legacyFullPageIdentity() ?: return emptyList()
        return listOf((galleryId?.let { "g${it}_" } ?: "") + cache.key(source, identity))
    }

    private fun legacyFiles(key: String, extension: String): List<UniFile> = listOfNotNull(
        galleryDir?.findFile(DIRECTORY_NAME)?.findFile("$key.$extension"),
        legacyDir?.let { UniFile.fromFile(File(it, "$key.$extension")) },
        cacheDirectory.findFile("$key.$extension"),
    ).filter { it.isFile }

    /** Full-page renders from before source-named overlays must be converted before display. */
    fun findLegacyResult(source: File): Hit? {
        val keys = legacyKeys(source)
        for (extension in listOf("png", "partial", "skip")) for (key in keys)
            legacyFiles(key, extension).firstOrNull()?.let { return Hit(key, it.takeUnless { extension == "skip" }) }
        return null
    }

    fun invalidateLegacy(source: File) {
        for (key in legacyKeys(source)) for (extension in listOf("png", "partial", "regions", "skip"))
            legacyFiles(key, extension).forEach { check(it.delete()) { "Cannot delete legacy translation" } }
    }

    private fun sourceParts(key: String): List<String> = (sourceName ?: key).replace('\\', '/').split('/')
        .filter { it.isNotEmpty() }.map {
            require(it != "." && it != "..") { "Invalid source name" }
            var name = FileUtils.sanitizeFilename(it).trimEnd('.')
            while (name.toByteArray(Charsets.UTF_8).size > 220)
                name = name.substring(0, name.offsetByCodePoints(name.length, -1))
            name.ifBlank { "page" }
        }.ifEmpty { listOf("page") }

    private fun savedDirectory(key: String, create: Boolean): UniFile? {
        var directory = galleryDir?.let {
            if (create) it.createDirectory(DIRECTORY_NAME) else it.findFile(DIRECTORY_NAME)
        }?.takeIf { it.isDirectory } ?: return null
        for (part in sourceParts(key).dropLast(1)) {
            directory = (if (create) directory.createDirectory(part) else directory.findFile(part))
                ?.takeIf { it.isDirectory } ?: return null
        }
        return directory
    }

    private fun savedName(key: String, extension: String) = "${sourceParts(key).last()}_tl.$extension"
    private fun saved(key: String, extension: String): UniFile? =
        savedDirectory(key, false)?.findFile(savedName(key, extension))?.takeIf { it.isFile }
    private fun cached(key: String, extension: String): UniFile? =
        cacheDirectory.findFile("${key}_tl.$extension")?.takeIf { it.isFile }
    private fun existing(key: String, extension: String): UniFile? =
        saved(key, extension) ?: cached(key, extension)

    fun existingImage(key: String): UniFile? = existing(key, "png") ?: existing(key, "partial")

    private fun checkpoint(image: UniFile): UniFile? = image.name?.let {
        image.parentFile?.findFile("${it.substringBeforeLast('.')}.regions")
    }

    fun readResume(key: String): TranslationResume? {
        val image = existingImage(key)?.takeIf { isPartial(it) } ?: return null
        return readResume(key, image)
    }

    fun readResume(key: String, image: UniFile): TranslationResume? {
        val json = readCheckpoint(image) ?: return null
        return runCatching {
            require(json.getInt("version") == 1 && json.getString("key") == key)
            if (json.has("identity")) require(json.getString("identity") == options.cacheIdentity())
            val regions = json.getJSONArray("regions").let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            val entries = json.getJSONObject("translations")
            val translations = entries.keys().asSequence().associate { it.toInt() to entries.getString(it) }
            require(translations.all { it.key in regions.indices && it.value.isNotBlank() })
            TranslationResume(regions, translations)
        }.getOrNull()
    }

    private fun readCheckpoint(image: UniFile): JSONObject? {
        val checkpoint = checkpoint(image) ?: return null
        return runCatching {
            checkpoint.openInputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString(Charsets.UTF_8.name()))
            }
        }.getOrNull()
    }

    fun writePartial(key: String, resume: TranslationResume, writer: (OutputStream) -> Unit): UniFile {
        require(resume.missingCount > 0)
        val json = JSONObject().put("version", 1).put("key", key).put("identity", options.cacheIdentity())
            .put("regions", JSONArray(resume.regions))
            .put("translations", JSONObject().apply { resume.translations.forEach { (index, text) -> put(index.toString(), text) } })
        write(key, "regions") { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return writePartialPreview(key, writer, keepCheckpoint = true)
    }

    fun writePartialPreview(key: String, writer: (OutputStream) -> Unit, keepCheckpoint: Boolean = false): UniFile {
        val result = write(key, "partial", writer)
        for (extension in listOf("png", "skip") + if (keepCheckpoint) emptyList() else listOf("regions")) {
            cached(key, extension)?.delete()
            saved(key, extension)?.delete()
        }
        if (persists) {
            cached(key, "partial")?.delete()
            cached(key, "regions")?.delete()
        }
        return result
    }

    private fun write(key: String, extension: String, writer: (OutputStream) -> Unit): UniFile {
        val dir = if (persists) checkNotNull(savedDirectory(key, true)) { "Gallery directory unavailable" }
            else cacheDirectory
        val name = if (persists) savedName(key, extension) else "${key}_tl.$extension"
        return publish(dir, name, writer)
    }

    fun writeImage(key: String, writer: (OutputStream) -> Unit): UniFile = write(key, "png", writer).also {
        if (persists) cached(key, "png")?.delete()
        for (extension in listOf("partial", "regions", "skip")) {
            cached(key, extension)?.delete()
            saved(key, extension)?.delete()
        }
    }

    /** Adopt current overlay-cache hits when persistent saving is enabled. */
    fun retain(key: String, file: UniFile): UniFile {
        val extension = if (isPartial(file)) "partial" else "png"
        val destination = if (persists) saved(key, extension) else cached(key, extension)
        val retained = if (file.uri != destination?.uri && (persists || file.parentFile?.uri == cacheDirectory.uri)) {
            if (isPartial(file)) {
                val json = readCheckpoint(file)
                if (json != null) write(key, "regions") { output ->
                    output.write(json.put("key", key).toString().toByteArray(Charsets.UTF_8))
                }
            }
            write(key, extension) { output -> file.openInputStream().use { it.copyTo(output) } }
        } else file
        if (retained.uri != file.uri && file.parentFile?.uri == cacheDirectory.uri) {
            checkpoint(file)?.delete()
            file.delete()
        }
        touch(retained)
        return retained
    }

    fun isSkipped(key: String): Boolean {
        val saved = saved(key, "skip")
        if (saved != null) return true
        val marker = cached(key, "skip") ?: return false
        if (persists) recordSkipped(key) else touch(marker)
        return true
    }

    fun recordSkipped(key: String) {
        write(key, "skip") { it.write("skipped".toByteArray(Charsets.UTF_8)) }
        if (persists) cached(key, "skip")?.delete()
        for (extension in listOf("png", "partial", "regions")) {
            cached(key, extension)?.delete()
            saved(key, extension)?.delete()
        }
        if (!persists) cache.prune()
    }

    fun invalidate(key: String) {
        cache.invalidate(key)
        for (extension in listOf("png", "skip", "partial", "regions")) {
            saved(key, extension)?.let { check(it.delete()) { "Cannot delete translation overlay" } }
        }
    }

    fun prune() = cache.prune()

    companion object {
        const val DIRECTORY_NAME = "_translated"
        fun isPartial(file: UniFile) = file.name?.endsWith(".partial") == true

        fun touch(file: UniFile) {
            if (UniFile.isFileUri(file.uri)) file.uri.path?.let { File(it).setLastModified(System.currentTimeMillis()) }
        }

        private fun publish(dir: UniFile, name: String, writer: (OutputStream) -> Unit): UniFile {
            val temp = checkNotNull(dir.createFile("$name.part")) { "Cannot create translation overlay" }
            var published = false
            try {
                temp.openOutputStream(false).use(writer)
                dir.findFile(name)?.let { check(it.isFile && it.delete()) { "Cannot replace translation overlay" } }
                check(temp.renameTo(name)) { "Cannot publish translation overlay" }
                published = true
                return temp
            } finally { if (!published) temp.delete() }
        }

        private fun deleteNamedOverlays(directory: UniFile): Boolean {
            val files = directory.listFiles() ?: return false
            var success = true
            for (file in files) {
                if (file.isDirectory) {
                    if (!deleteNamedOverlays(file)) success = false
                } else if (file.isFile && file.name?.matches(Regex(
                        "(.*_tl|g-?\\d+_[a-f0-9]{64})\\.(png|partial|regions|skip)(\\.part)?")) == true) {
                    if (!file.delete()) success = false
                }
            }
            return success
        }

        fun deleteGalleries(cacheDir: File, legacyDir: File, galleryIds: Set<Long>,
                            galleryDirs: Map<Long, UniFile> = emptyMap()): Boolean {
            var success = true
            for (dir in listOf(cacheDir, legacyDir)) {
                if (!dir.exists()) continue
                val files = dir.listFiles()
                if (files == null) { success = false; continue }
                for (file in files) {
                    val gid = file.name.substringBefore('_').removePrefix("g").toLongOrNull()
                    if (file.name.startsWith("g") && gid in galleryIds && file.isFile &&
                        file.extension in listOf("png", "skip", "partial", "regions", "part")) {
                        if (!file.delete()) success = false
                    }
                }
            }
            for ((gid, gallery) in galleryDirs) if (gid in galleryIds) {
                gallery.findFile(DIRECTORY_NAME)?.let {
                    if (!it.isDirectory || !deleteNamedOverlays(it)) success = false
                }
            }
            return success
        }
    }
}
