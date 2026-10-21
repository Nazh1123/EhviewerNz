package com.hippo.ehviewer.gallery

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.superresolution.SuperResolutionModels
import com.hippo.ehviewer.superresolution.SuperResolutionSettings
import com.hippo.ehviewer.translation.TranslationRuntime
import com.hippo.ehviewer.translation.TranslationSettings
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.unifile.UniFile
import java.io.DataInputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** User-invoked import only. Ordinary reader lookups never call this scanner. */
internal object GalleryResultImporter {
    data class Report(var translated: Int = 0, var enhanced: Int = 0, var ignored: Int = 0, var failed: Int = 0)
    private data class Candidate(val kind: GalleryResultIndex.Kind, val file: UniFile,
                                 val key: String, val persistent: Boolean)
    private data class Page(val source: GalleryResultIndex.Source, val original: UniFile?)
    private data class Size(val width: Int, val height: Int)
    private data class Geometry(val size: Size, val scale: Int, val bytes: Long, val modified: Long)

    suspend fun run(context: Context, provider: GalleryProvider2, info: GalleryInfo?): Report {
        val app = context.applicationContext
        val records = info?.let { GalleryProcessingProgress.savedResults(app, it.gid) }.orEmpty()
        return withContext(Dispatchers.IO) {
            val download = info?.let { EhApplication.getDownloadManager(app).getDownloadInfo(it.gid) }
            val directory = TranslationStorage.resultDirectory(app, download, provider, false)
            import(app, provider, info?.gid, GalleryResultIndex.identity(provider, info), directory, records)
        }
    }

    internal suspend fun import(context: Context, provider: GalleryProvider2, gid: Long?, identity: String,
                                directory: UniFile?, records: List<GalleryProcessingProgress.SavedResult>): Report =
        withContext(Dispatchers.IO) {
            TranslationRuntime.lock.withLock {
                SuperResolutionModels.lock.withLock {
                    collectAndImport(context, provider, gid, identity, directory, records)
                }
            }
        }

    private suspend fun collectAndImport(context: Context, provider: GalleryProvider2, gid: Long?, identity: String,
                                         directory: UniFile?, records: List<GalleryProcessingProgress.SavedResult>): Report {
        val report = Report()
        val local = mutableListOf<Candidate>()
        suspend fun scan(folder: UniFile, kind: GalleryResultIndex.Kind, prefix: String = "") {
            for (file in checkNotNull(folder.listFiles()) { "Cannot read result directory" }) {
                currentCoroutineContext().ensureActive()
                val name = file.name ?: continue
                if (file.isDirectory) scan(file, kind, "$prefix$name/")
                else if (completeName(name, kind)) local += Candidate(kind, file,
                    prefix + name.substringBeforeLast('.').removeSuffix(kind.suffix), true)
            }
        }
        for (kind in GalleryResultIndex.Kind.entries) directory?.findFile(kind.directory)?.let { scan(it, kind) }
        val localUris = local.map { it.file.uri.toString() }.toSet()
        val candidates = records.mapNotNull { record ->
            try {
                val kind = if (record.kind == GalleryProcessingProgress.Kind.TRANSLATION) GalleryResultIndex.Kind.TL else GalleryResultIndex.Kind.SR
                val file = resolve(context, record.uri)
                // Full-page renders and partial/checkpoint formats are deliberately excluded.
                if (file == null || file.name?.let { if (kind == GalleryResultIndex.Kind.SR)
                        it.endsWith(".png") || it.endsWith(".webp") else completeName(it, kind) } != true) { report.ignored++; null }
                else Candidate(kind, file, record.source, file.uri.toString() in localUris)
            } catch (error: Exception) {
                report.failed++
                android.util.Log.w("ResultImporter", "Cannot read old result reference", error)
                null
            }
        } + local
        if (candidates.isEmpty()) return report

        val aliases = mutableMapOf<String, MutableList<Page>>()
        for (page in 0 until provider.size().coerceAtLeast(0)) {
            currentCoroutineContext().ensureActive()
            val source = GalleryResultIndex.source(provider, page, gid)
            val entry = Page(source, provider.getPreviewFile(page))
            val names = setOf(source.name, provider.getTranslationFilename(page),
                GalleryResultIndex.filename(source.name, GalleryResultIndex.Kind.TL, "png").removeSuffix("_tl.png"))
            for (name in names) aliases.getOrPut(GalleryProcessingProgress.sourceKey(name)) { mutableListOf() }.add(entry)
        }
        val geometries = legacyGeometry(context, identity)
        val index = GalleryResultIndex.open(context, identity, gid, directory, false)
        val seen = mutableSetOf<Pair<String, GalleryResultIndex.Kind>>()
        val retired = mutableMapOf<String, UniFile>()
        val keep = mutableSetOf<String>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val uri = candidate.file.uri.toString()
            try {
                val matches = aliases[candidate.key].orEmpty().distinctBy { it.source.name }
                val page = matches.singleOrNull() ?: if (matches.isEmpty() &&
                    (provider is EhGalleryProvider || provider is UpdatedGalleryProvider) && candidate.key.matches(Regex("[0-9]{8}")))
                    Page(GalleryResultIndex.Source(candidate.key), null) else null
                if (page == null) { report.ignored++; keep += uri; continue }
                val key = page.source.name to candidate.kind
                if (key in seen) continue
                val current = index.find(page.source, candidate.kind)
                if (current != null) { seen += key; report.ignored++; keep += uri; continue }
                if (!candidate.file.isFile) { report.ignored++; keep += uri; continue }
                val size = validate(candidate.file)
                val scale = if (candidate.kind == GalleryResultIndex.Kind.TL) 1 else {
                    val metadata = geometries[uri]
                    if (metadata != null && metadata.size == size &&
                        (metadata.bytes <= 0 || metadata.bytes == candidate.file.length()) &&
                        (metadata.modified <= 0 || metadata.modified == candidate.file.lastModified())) metadata.scale
                    else page.original?.let(::bounds)?.let { original ->
                        listOf(2, 4, 8).firstOrNull { original.width.toLong() * it == size.width.toLong() && original.height.toLong() * it == size.height.toLong() }
                    }
                }
                if (scale == null) { report.ignored++; keep += uri; continue }
                if (index.importComplete(page.source, candidate.kind, candidate.file, scale, candidate.persistent)) {
                    if (candidate.kind == GalleryResultIndex.Kind.TL) report.translated++ else report.enhanced++
                    if (index.find(page.source, candidate.kind)?.file?.uri != candidate.file.uri) retired[uri] = candidate.file
                } else { report.ignored++; keep += uri }
                seen += key
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                report.failed++; keep += uri
                android.util.Log.w("ResultImporter", "Cannot import $uri", error)
            }
        }
        currentCoroutineContext().ensureActive()
        report.failed += GalleryProcessingProgress.retireImported(context, gid, retired.filterKeys { it !in keep }.values)
        GalleryResultIndex.prune(context, GalleryResultIndex.Kind.TL, TranslationSettings(context).read().cacheLimitBytes)
        GalleryResultIndex.prune(context, GalleryResultIndex.Kind.SR, SuperResolutionSettings(context).read().cacheMiB * 1024L * 1024)
        return report
    }

    private fun completeName(name: String, kind: GalleryResultIndex.Kind) =
        name.endsWith("${kind.suffix}.png") || name.endsWith("${kind.suffix}.webp") ||
            (kind == GalleryResultIndex.Kind.SR && name in setOf("result.png", "result.webp"))

    private fun resolve(context: Context, uri: Uri): UniFile? = if (uri.scheme == "file") {
        val path = uri.schemeSpecificPart.removePrefix("//")
        UniFile.fromFile(File(if (path.matches(Regex("^/[A-Za-z]:.*"))) path.drop(1) else path))
    } else UniFile.fromUri(context, uri)

    private fun bounds(image: UniFile): Size {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        image.openInputStream().use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "Invalid image dimensions" }
        return Size(options.outWidth, options.outHeight)
    }
    private fun validate(image: UniFile): Size {
        check(image.isFile)
        val length = image.length()
        DataInputStream(image.openInputStream()).use { input ->
            val head = ByteArray(12).also(input::readFully)
            if (image.name!!.endsWith(".webp")) {
                val bytes = (0..3).fold(0L) { value, i -> value or ((head[4 + i].toLong() and 255) shl (i * 8)) }
                check(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" && bytes + 8 == length)
            } else {
                check(length >= 45 && head.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
                var remaining = length - 24
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0) remaining -= skipped else { check(input.read() >= 0); remaining-- }
                }
                val tail = ByteArray(12).also(input::readFully)
                check(tail.contentEquals(byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)))
            }
        }
        return bounds(image)
    }

    private suspend fun legacyGeometry(context: Context, identity: String): Map<String, Geometry> {
        val result = mutableMapOf<String, Geometry>()
        for (root in listOf(context.cacheDir, context.noBackupFilesDir)) {
            val gallery = File(File(root, "super-resolution-pages"), GalleryResultIndex.digest(identity))
            for (page in gallery.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".tiles") }) {
                currentCoroutineContext().ensureActive()
                val metadata = File(page, "page.json")
                if (!metadata.isFile || metadata.length() > 1024 * 1024) continue
                runCatching {
                    val json = JSONObject(metadata.readText())
                    if (json.getInt("version") != 3 || !json.getBoolean("complete")) return@runCatching
                    val scale = json.getInt("scale")
                    val w = json.getInt("width"); val h = json.getInt("height")
                    check(scale in listOf(2, 4, 8) && w in 1..32768 && h in 1..32768)
                    result[json.getString("result")] = Geometry(Size(w * scale, h * scale), scale,
                        json.optLong("resultSize"), json.optLong("resultModified"))
                }
            }
        }
        return result
    }
}
