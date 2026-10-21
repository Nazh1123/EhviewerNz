package com.hippo.ehviewer.gallery

import android.content.Context
import android.graphics.BitmapFactory
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.unifile.UniFile
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import org.json.JSONObject

/** Per-gallery result manifests. All file work runs on IO; a missing manifest never creates files. */
class GalleryResultIndex private constructor(
    private val context: Context, val identity: String, private val gid: Long?,
    private val local: UniFile?, private val persist: Boolean,
) {
    enum class Kind(val key: String, val directory: String, val suffix: String) {
        TL("tl", "_translated", "_tl"), SR("sr", "_suepr_resolution", "_sr")
    }
    data class Source(val name: String, val stamp: String = "", val uri: String = "")
    data class Hit(val file: UniFile?, val state: String, val width: Int, val height: Int,
                   val scale: Int, val persistent: Boolean)
    private val cache = File(root(context), digest(identity))
    internal val applicationContext get() = context
    private val cacheDirectory get() = checkNotNull(UniFile.fromFile(cache))

    private fun manifest(directory: UniFile): JSONObject {
        val key = directory.uri.toString()
        return documents.getOrPut(key) {
            listOf(MANIFEST, "$MANIFEST.bak").firstNotNullOfOrNull { name ->
                runCatching { directory.findFile(name)?.openInputStream()?.use {
                    val bytes = it.readBytesBounded()
                    JSONObject(String(bytes, Charsets.UTF_8)).also { json -> require(json.getInt("version") == 1); json.getJSONObject("pages") }
                } }.getOrNull()
            } ?: JSONObject().put("version", 1).put("gallery", identity).put("pages", JSONObject())
        }
    }
    private fun commit(directory: UniFile, json: JSONObject, source: Source, kind: Kind) {
        if (identity.isNotEmpty()) json.put("gallery", identity)
        if (json.getJSONObject("pages").length() == 0) {
            for (name in listOf(MANIFEST, "$MANIFEST.bak", "$MANIFEST.part")) directory.findFile(name)?.let { check(it.delete()) }
            documents[directory.uri.toString()] = json
            changed(json, source, kind)
            return
        }
        val temp = checkNotNull(directory.createFile("$MANIFEST.part"))
        var backup: UniFile? = null
        var published = false
        try {
            temp.openOutputStream(false).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            directory.findFile(MANIFEST)?.let { previous ->
                directory.findFile("$MANIFEST.bak")?.let { check(it.delete()) }
                check(previous.renameTo("$MANIFEST.bak")); backup = previous
            }
            check(temp.renameTo(MANIFEST))
            published = true
        } catch (error: Throwable) {
            backup?.renameTo(MANIFEST)
            documents.remove(directory.uri.toString())
            throw error
        } finally {
            if (!published) temp.delete() else backup?.delete()
        }
        documents[directory.uri.toString()] = json
        changed(json, source, kind)
    }
    private fun changed(json: JSONObject, source: Source, kind: Kind) {
        revision++
        val changed = json.optString("gallery", identity)
        val event = Change(changed, source.name, kind)
        android.os.Handler(android.os.Looper.getMainLooper()).post { listeners.forEach { it(event) } }
    }
    private fun row(json: JSONObject, source: Source, kind: Kind) =
        json.getJSONObject("pages").optJSONObject(source.name)?.optJSONObject(kind.key)
    private fun file(directory: UniFile, source: Source, kind: Kind, entry: JSONObject): UniFile? {
        val locator = entry.getString("uri")
        return if (locator == "done") directory.findFile(kind.directory)?.findFile(filename(source.name, kind, entry.getString("format")))
        else {
            require(locator.matches(Regex("[a-f0-9]{64}/[a-z]+\\.[a-z]+")))
            val parts = locator.split('/')
            directory.findFile(parts[0])?.findFile(parts[1])
        }
    }
    fun find(source: Source, kind: Kind): Hit? = synchronized(guard) {
        for ((directory, persistent) in listOfNotNull(local?.let { it to true }, cacheDirectory to false)) {
            val json = manifest(directory)
            val entry = row(json, source, kind) ?: continue
            if (source.stamp.isNotEmpty() && entry.optString("stamp").isNotEmpty() && source.stamp != entry.optString("stamp")) {
                remove(directory, json, source, kind)
                continue
            }
            val image = file(directory, source, kind, entry)
            if (image == null || !image.isFile) {
                remove(directory, json, source, kind)
                continue
            }
            if (persist && local != null && !persistent) {
                if (entry.optString("state") == "partial") {
                    image.parentFile?.findFile("${kind.key}.regions")?.let { checkpoint ->
                        write(source, kind, "regions") { out -> checkpoint.openInputStream().use { it.copyTo(out) } }
                    }
                }
                write(source, kind, entry.getString("format"), entry.optInt("scale", 1)) { out -> image.openInputStream().use { it.copyTo(out) } }
                return@synchronized find(source, kind)
            }
            if (entry.optString("state") == "skip") return@synchronized Hit(null, "skip", 0, 0, 1, persistent)
            return@synchronized Hit(image, entry.optString("state", "complete"), entry.optInt("width"),
                entry.optInt("height"), entry.optInt("scale", 1), persistent)
        }
        null
    }
    /** Atomically publish bytes before advertising the new result. Writers of both kinds share guard. */
    fun write(source: Source, kind: Kind, format: String, scale: Int = 1, writer: (OutputStream) -> Unit): UniFile = synchronized(guard) {
        require(format in setOf("webp", "png", "partial", "regions", "skip"))
        val persistent = persist && local != null
        val directory = if (persistent) checkNotNull(local) else {
            check(cache.isDirectory || cache.mkdirs()); cacheDirectory
        }
        val target = if (persistent) checkNotNull(directory.createDirectory(kind.directory))
            else checkNotNull(directory.createDirectory(digest(source.name)))
        val name = if (persistent) filename(source.name, kind, format) else "${kind.key}.$format"
        val temporary = checkNotNull(target.createFile("$name.part"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var backup: UniFile? = null
        var published = false
        try {
            temporary.openOutputStream(false).use(writer)
            if (format in setOf("png", "webp", "partial")) temporary.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (format in setOf("png", "webp")) check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid result image" }
            target.findFile(name)?.let {
                target.findFile("$name.bak")?.let { oldBackup -> check(oldBackup.delete()) }
                check(it.renameTo("$name.bak")); backup = it
            }
            check(temporary.renameTo(name))
            if (format != "regions") {
                val json = manifest(directory)
                val pages = json.getJSONObject("pages")
                val page = pages.optJSONObject(source.name) ?: JSONObject().also { pages.put(source.name, it) }
                val old = page.optJSONObject(kind.key)
                val entry = JSONObject().put("uri", if (persistent) "done" else "${digest(source.name)}/$name")
                    .put("format", format).put("state", when (format) { "partial" -> "partial"; "skip" -> "skip"; else -> "complete" })
                    .put("width", bounds.outWidth).put("height", bounds.outHeight).put("scale", scale)
                    .put("stamp", source.stamp).put("sourceId", source.uri.takeIf { it.isNotEmpty() }?.let(::digest).orEmpty())
                    .put("used", System.currentTimeMillis())
                page.put(kind.key, entry)
                commit(directory, json, source, kind)
                published = true
                if (old != null && old.optString("format") != format) file(directory, source, kind, old)?.delete()
                if (format != "partial") target.findFile(if (persistent) filename(source.name, kind, "regions") else "${kind.key}.regions")?.delete()
                if (persistent) removeCache(source, kind)
                record(source, kind, temporary.takeIf { format in setOf("png", "webp") })
            } else published = true
        } catch (error: Throwable) {
            if (!published) {
                temporary.delete()
                backup?.renameTo(name)
                documents.remove(directory.uri.toString())
            }
            throw error
        } finally {
            if (!published) temporary.takeIf { it.name?.endsWith(".part") == true }?.delete()
            else backup?.delete()
        }
        temporary
    }
    fun invalidate(source: Source, kind: Kind) = synchronized(guard) {
        for (directory in listOfNotNull(local, cacheDirectory)) {
            val json = manifest(directory)
            if (row(json, source, kind) != null) remove(directory, json, source, kind)
        }
    }
    /** Explicit import: merge without replacing a current result or recompressing old pixels. */
    internal fun importComplete(source: Source, kind: Kind, image: UniFile, scale: Int,
                                persistent: Boolean): Boolean = synchronized(guard) {
        if (find(source, kind) != null) return@synchronized false
        val format = image.name!!.substringAfterLast('.')
        require(format in setOf("png", "webp"))
        val target = GalleryResultIndex(context, identity, gid, local, persistent && local != null)
        val existing = local?.findFile(kind.directory)?.findFile(filename(source.name, kind, format))
        if (persistent && existing?.uri == image.uri) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            image.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0)
            val json = target.manifest(checkNotNull(local))
            val pages = json.getJSONObject("pages")
            val page = pages.optJSONObject(source.name) ?: JSONObject().also { pages.put(source.name, it) }
            page.put(kind.key, JSONObject().put("uri", "done").put("format", format).put("state", "complete")
                .put("width", bounds.outWidth).put("height", bounds.outHeight).put("scale", scale)
                .put("stamp", source.stamp).put("sourceId", source.uri.takeIf { it.isNotEmpty() }?.let(::digest).orEmpty())
                .put("used", System.currentTimeMillis()))
            try { target.commit(local, json, source, kind) }
            catch (error: Throwable) { documents.remove(local.uri.toString()); throw error }
            target.record(source, kind, image)
        } else target.write(source, kind, format, scale) { out -> image.openInputStream().use { it.copyTo(out) } }
        true
    }
    private fun removeCache(source: Source, kind: Kind) {
        val json = manifest(cacheDirectory)
        if (row(json, source, kind) != null) remove(cacheDirectory, json, source, kind)
    }
    private fun remove(directory: UniFile, json: JSONObject, source: Source, kind: Kind) {
        val entry = row(json, source, kind) ?: return
        if (!directory.isDirectory) { documents.remove(directory.uri.toString()); return }
        file(directory, source, kind, entry)?.let { image ->
            if (image.exists()) check(image.delete())
            GalleryProcessingProgress.forget(context, listOf(image.uri))
        }
        val target = if (directory.uri == local?.uri) directory.findFile(kind.directory) else directory.findFile(digest(source.name))
        target?.let { page ->
            for (extension in listOf("regions", "partial", "skip", "png", "webp"))
                page.findFile(if (directory.uri == local?.uri) filename(source.name, kind, extension) else "${kind.key}.$extension")?.delete()
            if (directory.uri != local?.uri && page.listFiles()?.isEmpty() == true) page.delete()
        }
        val pages = json.getJSONObject("pages")
        val page = pages.getJSONObject(source.name)
        page.remove(kind.key)
        if (page.length() == 0) pages.remove(source.name)
        commit(directory, json, source, kind)
        if (directory.uri != local?.uri && directory.listFiles()?.isEmpty() == true) directory.delete()
    }
    private fun record(source: Source, kind: Kind, file: UniFile?) {
        gid?.let { GalleryProcessingProgress.record(context, it,
            if (kind == Kind.TL) GalleryProcessingProgress.Kind.TRANSLATION else GalleryProcessingProgress.Kind.SUPER_RESOLUTION,
            source.name, file) }
    }
    /** Local scans already know URI/mtime/size. Renames with stable document IDs are preserved; deletions expire. */
    fun reconcile(sources: List<Source>) = synchronized(guard) {
        val byName = sources.associateBy { it.name }
        val byDocument = sources.filter { it.uri.isNotEmpty() }.groupBy { digest(it.uri) to it.stamp }
        for (directory in listOfNotNull(local, cacheDirectory)) {
            val json = manifest(directory)
            val pages = json.getJSONObject("pages")
            for (name in pages.keys().asSequence().toList()) {
                val current = byName[name]
                if (current != null) {
                    for (kind in Kind.entries) {
                        val entry = row(json, current, kind) ?: continue
                        if (current.stamp.isNotEmpty() && entry.optString("stamp").isNotEmpty() && current.stamp != entry.optString("stamp"))
                            remove(directory, json, current, kind)
                    }
                    continue
                }
                for (kind in Kind.entries) {
                    val old = pages.optJSONObject(name)?.optJSONObject(kind.key) ?: continue
                    val matches = byDocument[old.optString("sourceId") to old.optString("stamp")].orEmpty()
                    val source = Source(name)
                    if (matches.size == 1) {
                        val next = matches.single()
                        val image = file(directory, source, kind, old)
                        if (image != null && old.optString("state") in setOf("complete", "skip")) {
                            val destination = GalleryResultIndex(context, identity, gid, local, directory.uri == local?.uri)
                            destination.write(next, kind, old.getString("format"), old.optInt("scale", 1)) { out -> image.openInputStream().use { it.copyTo(out) } }
                        }
                    }
                    remove(directory, json, source, kind)
                }
            }
        }
    }
    /** Only providers exposing a complete local listing may remove pages absent from that listing. */
    fun reconcile(provider: GalleryProvider2) {
        if (provider is EhGalleryProvider || provider is UpdatedGalleryProvider || provider.size() <= 0) return
        if (hasEntries()) reconcile((0 until provider.size()).map { source(provider, it, gid) })
    }
    fun hasEntries(): Boolean = synchronized(guard) {
        listOfNotNull(local, cacheDirectory).any { manifest(it).getJSONObject("pages").length() > 0 }
    }
    companion object {
        const val MANIFEST = ".ehviewer-results.json"
        const val PREFER_EXISTING = "reader_prefer_existing_results"
        private val guard = Any()
        internal data class Change(val identity: String, val source: String, val kind: Kind)
        internal val listeners = java.util.concurrent.CopyOnWriteArraySet<(Change) -> Unit>()
        // Includes negative entries. Bounded so reading many empty galleries cannot grow this indefinitely.
        private val documents = object : LinkedHashMap<String, JSONObject>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 48
        }
        @Volatile var revision = 0L
            private set
        internal fun dropMemory() = synchronized(guard) { documents.clear() }
        fun root(context: Context) = File(context.cacheDir, "gallery-results")
        internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        internal fun filename(name: String, kind: Kind, extension: String): String {
            require(extension in setOf("png", "webp", "partial", "skip", "regions"))
            if (name.matches(Regex("[0-9]{1,8}"))) return name + kind.suffix + ".$extension"
            var base = FileUtils.sanitizeFilename(name.substringAfterLast('/'))
            while (base.toByteArray(Charsets.UTF_8).size > 180)
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            return base + "-" + digest(name).take(12) + kind.suffix + ".$extension"
        }
        private fun java.io.InputStream.readBytesBounded(): ByteArray {
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= 16 * 1024 * 1024); out.write(buffer, 0, n) }
            return out.toByteArray()
        }
        fun source(provider: GalleryProvider2, page: Int, gid: Long?): Source {
            val image = provider.getPreviewFile(page)
            val remote = provider is EhGalleryProvider || provider is UpdatedGalleryProvider
            val name = if (gid != null && gid > 0) image?.name?.takeIf { it.matches(Regex("\\d{8}\\.[^.]+")) }?.substringBefore('.')
                ?: if (remote) provider.getTranslationFilename(page) else provider.getResultPageName(page)
                else provider.getResultPageName(page)
            return Source(name, if (remote) "" else image?.let { "${it.length()}:${it.lastModified()}" }
                ?: provider.getResultSourceStamp(page), image?.uri?.toString().orEmpty())
        }
        fun open(context: Context, identity: String, gid: Long?, local: UniFile?, persist: Boolean) =
            GalleryResultIndex(context.applicationContext, identity, gid, local, persist)
        fun identity(provider: GalleryProvider2, info: GalleryInfo?) = info?.let { "gallery:${it.gid}" }
            ?: provider.translationIdentity ?: "reader:${System.identityHashCode(provider)}"
        @JvmStatic fun clearCache(context: Context, kind: Kind) = synchronized(guard) {
            for (gallery in root(context).listFiles().orEmpty()) {
                val index = GalleryResultIndex(context, "", null, null, false)
                val directory = checkNotNull(UniFile.fromFile(gallery))
                index.clearDirectory(directory, kind)
                cleanOrphans(gallery, index.manifest(directory), kind)
            }
        }
        private fun cleanOrphans(gallery: File, json: JSONObject, kind: Kind) {
            val expected = mutableMapOf<String, Set<String>>()
            val pages = json.getJSONObject("pages")
            for (name in pages.keys()) pages.getJSONObject(name).optJSONObject(kind.key)?.let {
                expected[digest(name)] = setOf("${kind.key}.${it.getString("format")}") +
                    if (it.optString("state") == "partial") setOf("${kind.key}.regions") else emptySet()
            }
            for (page in gallery.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }) {
                for (file in page.listFiles().orEmpty()) if (file.name.startsWith("${kind.key}.") && file.name !in expected[page.name].orEmpty())
                    check(file.delete()) { "Cannot remove orphan result" }
                if (page.listFiles()?.isEmpty() == true) page.delete()
            }
            if (gallery.listFiles()?.isEmpty() == true) gallery.delete()
        }
        private fun GalleryResultIndex.clearDirectory(directory: UniFile, kind: Kind) {
            val json = manifest(directory)
            for (name in json.getJSONObject("pages").keys().asSequence().toList()) remove(directory, json, Source(name), kind)
        }
        @JvmStatic fun prune(context: Context, kind: Kind, limit: Long) = synchronized(guard) {
            data class Candidate(val index: GalleryResultIndex, val dir: UniFile, val json: JSONObject, val source: Source, val size: Long, val used: Long)
            val candidates = mutableListOf<Candidate>()
            for (gallery in root(context).listFiles().orEmpty()) {
                val index = GalleryResultIndex(context, "", null, null, false)
                val dir = checkNotNull(UniFile.fromFile(gallery)); val json = index.manifest(dir)
                cleanOrphans(gallery, json, kind)
                for (name in json.getJSONObject("pages").keys().asSequence().toList()) {
                    val source = Source(name); val entry = index.row(json, source, kind) ?: continue
                    if (index.file(dir, source, kind, entry)?.isFile != true) {
                        index.remove(dir, json, source, kind); continue
                    }
                    val bytes = dir.findFile(digest(name))?.listFiles().orEmpty()
                        .filter { it.name?.startsWith("${kind.key}.") == true }.sumOf { it.length() }
                    candidates += Candidate(index, dir, json, source, bytes, entry.optLong("used"))
                }
            }
            var size = candidates.sumOf { it.size }
            for (candidate in candidates.sortedBy { it.used }) if (size > limit) {
                candidate.index.remove(candidate.dir, candidate.json, candidate.source, kind); size -= candidate.size
            }
        }
        /** Called only after a pToken-verified original was copied into its new numbered position. */
        @JvmStatic fun copyPage(context: Context, sourceGid: Long, targetGid: Long, oldName: String, newName: String,
                                sourceDir: UniFile, targetDir: UniFile) = synchronized(guard) {
            val source = open(context, "gallery:$sourceGid", sourceGid, sourceDir, false)
            for (kind in Kind.entries) {
                val hit = source.find(Source(oldName), kind) ?: continue
                val target = open(context, "gallery:$targetGid", targetGid, targetDir, hit.persistent)
                if (hit.state == "skip") {
                    target.write(Source(newName), kind, "skip") { it.write("skipped".toByteArray()) }
                    continue
                }
                if (hit.state != "complete") continue
                val file = hit.file ?: continue
                target.write(Source(newName), kind, file.name!!.substringAfterLast('.'), hit.scale) { out -> file.openInputStream().use { it.copyTo(out) } }
            }
        }
        @JvmStatic fun clearCachedGallery(context: Context, gid: Long) = synchronized(guard) {
            val index = open(context, "gallery:$gid", gid, null, false)
            if (index.cache.isDirectory) for (kind in Kind.entries) index.clearDirectory(index.cacheDirectory, kind)
        }
        @JvmStatic fun invalidatePage(context: Context, gid: Long, page: Int, directory: UniFile?) {
            val index = open(context, "gallery:$gid", gid, directory, false)
            val source = Source(com.hippo.ehviewer.spider.SpiderDen.generateImageFilename(page, ""))
            for (kind in Kind.entries) index.invalidate(source, kind)
        }
        @JvmStatic fun deleteKind(context: Context, gid: Long, directory: UniFile?, kind: Kind) = synchronized(guard) {
            val index = open(context, "gallery:$gid", gid, directory, false)
            directory?.let { index.clearDirectory(it, kind) }
            if (index.cache.isDirectory) index.clearDirectory(index.cacheDirectory, kind)
        }
        internal fun files(context: Context, gid: Long, directory: UniFile?, kind: Kind): List<UniFile> = synchronized(guard) {
            val index = open(context, "gallery:$gid", gid, directory, false)
            val files = mutableListOf<UniFile>()
            for (dir in listOfNotNull(directory, index.cacheDirectory)) {
                val json = index.manifest(dir)
                for (name in json.getJSONObject("pages").keys()) {
                    index.row(json, Source(name), kind)?.let { index.file(dir, Source(name), kind, it)?.let(files::add) }
                    if (dir.uri != directory?.uri) dir.findFile(digest(name))?.findFile("${kind.key}.regions")?.let(files::add)
                }
            }
            files
        }
        @JvmStatic fun deleteGallery(context: Context, gid: Long, directory: UniFile?) = synchronized(guard) {
            val index = open(context, "gallery:$gid", gid, directory, false)
            for (kind in Kind.entries) {
                directory?.let { index.clearDirectory(it, kind) }
                if (index.cache.isDirectory) index.clearDirectory(index.cacheDirectory, kind)
            }
        }
    }
}
