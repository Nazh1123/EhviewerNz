package com.hippo.ehviewer.gallery

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.gallery.GalleryProcessingProgress.Kind
import com.hippo.ehviewer.superresolution.SuperResolutionExport
import com.hippo.ehviewer.superresolution.SuperResolutionModels
import com.hippo.ehviewer.superresolution.SuperResolutionStore
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.ehviewer.translation.TranslationResultStore
import com.hippo.ehviewer.translation.TranslationRuntime
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.ehviewer.translation.TranslationTasks
import com.hippo.unifile.UniFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Selected-gallery storage operations. Files are enumerated only for an explicit inspect/delete action. */
object GalleryResultStorage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal data class Usage(val count: Long, val bytes: Long)
    internal data class Summary(val translation: Usage, val superResolution: Usage, val originals: Usage?)

    @JvmStatic fun showDeleteDialog(context: Context, galleries: List<DownloadInfo>, onDelete: Runnable) {
        val selected = galleries.distinctBy { it.gid }
        if (selected.isEmpty()) return
        val app = context.applicationContext
        fun delete(kind: Kind) {
            onDelete.run()
            scope.launch {
                val success = runCatching { delete(app, selected, kind) }.getOrDefault(false)
                Toast.makeText(app, if (success) R.string.gallery_results_deleted
                    else R.string.gallery_results_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.gallery_results_delete_title)
            .setMessage(R.string.gallery_results_calculating)
            .setPositiveButton(R.string.translation_delete_results) { _, _ -> delete(Kind.TRANSLATION) }
            .setNegativeButton(R.string.gallery_results_delete_sr) { _, _ -> delete(Kind.SUPER_RESOLUTION) }
            .setNeutralButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
        val loading = scope.launch {
            try {
                val summary = inspect(app, selected)
                ensureActive()
                if (!dialog.isShowing) return@launch
                val originals = summary.originals?.let {
                    context.getString(R.string.gallery_results_originals, it.count, Formatter.formatFileSize(context, it.bytes))
                } ?: context.getString(R.string.gallery_results_originals_unavailable)
                dialog.setMessage(context.getString(R.string.gallery_results_delete_summary, selected.size,
                    summary.translation.count, Formatter.formatFileSize(context, summary.translation.bytes),
                    summary.superResolution.count, Formatter.formatFileSize(context, summary.superResolution.bytes), originals))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                    summary.translation.count > 0 || summary.translation.bytes > 0
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled =
                    summary.superResolution.count > 0 || summary.superResolution.bytes > 0
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { dialog.setMessage(context.getString(R.string.gallery_results_inspect_failed)) }
        }
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
        lifecycle?.addObserver(observer)
        dialog.setOnDismissListener { loading.cancel(); lifecycle?.removeObserver(observer) }
    }

    internal suspend fun inspect(context: Context, galleries: List<DownloadInfo>): Summary = withContext(Dispatchers.IO) {
        val ids = galleries.map { it.gid }.toSet()
        val directories = directories(context, galleries)
        val translation = TranslationRuntime.lock.withLock {
            val bytes = collect(context, ids, directories, Kind.TRANSLATION).sumOf { it.length() }
            Usage(ids.sumOf { GalleryProcessingProgress.get(context, it).translated.toLong() }, bytes)
        }
        val sr = SuperResolutionModels.lock.withLock {
            val bytes = collect(context, ids, directories, Kind.SUPER_RESOLUTION).sumOf { it.length() }
            Usage(ids.sumOf { GalleryProcessingProgress.get(context, it).enhanced.toLong() }, bytes)
        }
        val originals = try { inspectOriginals(context, galleries, directories) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        Summary(translation, sr, originals)
    }

    /** Image metadata only: no decoding, hashing, extraction, or processing-progress backfill. */
    private suspend fun inspectOriginals(context: Context, galleries: List<DownloadInfo>, directories: List<UniFile>): Usage {
        val roots = directories.toMutableList()
        val archives = linkedMapOf<String, DownloadInfo>()
        for (info in galleries) {
            val source = info.archiveUri ?: continue
            if (source.startsWith("local-album:")) {
                roots += checkNotNull(SpiderDen.getExistingGalleryDownloadDir(info)?.takeIf { it.isDirectory })
            } else if (!LocalFolderGallerySource.isLocalFolderGallery(source)) archives.putIfAbsent(source, info)
        }
        var usage = collectOriginals(roots)
        for ((uri, info) in archives) {
            currentCoroutineContext().ensureActive()
            val archive = checkNotNull(UniFile.fromUri(context, Uri.parse(uri))) { "Archive unavailable" }
            check(archive.isFile) { "Archive unavailable" }
            val knownPages = ImportedGalleryProgress.get(context, info.gid)?.pages
                ?.takeIf { it > 0 } ?: maxOf(info.pages, info.total, info.finished)
            val count = if (knownPages > 0) knownPages else {
                val input = archive.createRandomAccessFile("r")
                try { A7ZipArchive.create(input).use { it.archiveEntries.size } }
                finally { input.close() }
            }
            // The archive container is the actual on-disk storage; never extract images to measure it.
            usage = Usage(usage.count + count, usage.bytes + archive.length())
        }
        return usage
    }

    internal suspend fun collectOriginals(directories: List<UniFile>): Usage {
        val pending = ArrayDeque(directories)
        val visited = mutableSetOf<String>()
        val files = linkedMapOf<String, UniFile>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeFirst()
            if (!visited.add(directory.uri.toString())) continue
            for (file in checkNotNull(directory.listFiles()) { "Original directory unavailable" }) {
                currentCoroutineContext().ensureActive()
                val name = file.name ?: continue
                if (file.isDirectory) {
                    if (!name.equals(TranslationResultStore.DIRECTORY_NAME, true) &&
                        !name.equals(SuperResolutionExport.DIRECTORY_NAME, true)) pending.add(file)
                } else if (file.isFile && GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.any { name.endsWith(it, true) }) {
                    files[file.uri.toString()] = file
                }
            }
        }
        return Usage(files.size.toLong(), files.values.sumOf { it.length() })
    }

    internal suspend fun delete(context: Context, galleries: List<DownloadInfo>, kind: Kind): Boolean {
        val ids = galleries.map { it.gid }.toSet()
        // Stop only the selected stage and await its writers before removing files.
        withContext(Dispatchers.Main.immediate) {
            if (kind == Kind.TRANSLATION) TranslationTasks.stopForResultDeletion(ids)
            else SuperResolutionTasks.stopForResultDeletion(ids)
        }
        return withContext(Dispatchers.IO) {
            val lock = if (kind == Kind.TRANSLATION) TranslationRuntime.lock else SuperResolutionModels.lock
            lock.withLock {
                val directories = directories(context, galleries)
                val files = collect(context, ids, directories, kind)
                var success = true
                val removed = files.filter { file ->
                    val deleted = runCatching { file.delete() || !file.exists() }.getOrDefault(false)
                    if (!deleted) success = false
                    deleted
                }
                GalleryProcessingProgress.forget(context, removed.map { it.uri }).join()
                GalleryProcessingProgress.removeMissing(context, ids, kind).join()
                success
            }
        }
    }

    private fun directories(context: Context, galleries: List<DownloadInfo>) = galleries.mapNotNull {
        val directory = TranslationStorage.galleryDir(context, it)
        check(directory != null || !TranslationStorage.hasGalleryDirectory(it)) { "Gallery directory unavailable" }
        directory
    }.distinctBy { it.uri }

    /** Include partial/checkpoint bytes; an internal tile page is storage, not one image per tile. */
    internal suspend fun collect(context: Context, ids: Set<Long>, galleries: List<UniFile>, kind: Kind): List<UniFile> {
        val files = linkedMapOf<String, UniFile>()
        suspend fun addTree(directory: UniFile, accept: (String) -> Boolean) {
            currentCoroutineContext().ensureActive()
            val children = checkNotNull(directory.listFiles()) { "Result directory unavailable" }
            for (file in children) {
                currentCoroutineContext().ensureActive()
                if (file.isDirectory) addTree(file, accept)
                else if (file.isFile && file.name?.let(accept) == true) files[file.uri.toString()] = file
            }
        }
        if (kind == Kind.TRANSLATION) {
            for (root in listOf(TranslationStorage.cacheDir(context), TranslationStorage.legacyPersistentDir(context))) {
                if (!root.exists()) continue
                for (file in checkNotNull(root.listFiles())) {
                    currentCoroutineContext().ensureActive()
                    val gid = file.name.substringBefore('_').removePrefix("g").toLongOrNull()
                    if (file.isFile && file.name.startsWith("g") && gid in ids &&
                        file.extension in setOf("webp", "png", "partial", "regions", "skip", "part")) {
                        val entry = checkNotNull(UniFile.fromFile(file))
                        files[entry.uri.toString()] = entry
                    }
                }
            }
            for (gallery in galleries) gallery.findFile(TranslationResultStore.DIRECTORY_NAME)?.let {
                check(it.isDirectory)
                addTree(it) { name -> TranslationResultStore.isResultFile(name) }
            }
        } else {
            for (gid in ids) for (root in SuperResolutionStore.galleryRoots(context, gid)) if (root.exists())
                addTree(checkNotNull(UniFile.fromFile(root))) { true }
            for (gallery in galleries) gallery.findFile(SuperResolutionExport.DIRECTORY_NAME)?.let {
                check(it.isDirectory)
                addTree(it) { name -> name.endsWith("_sr.png") || name.endsWith("_sr.png.part") ||
                    name.endsWith("_sr.webp") || name.endsWith("_sr.webp.part") }
            }
        }
        return files.values.toList()
    }
}
