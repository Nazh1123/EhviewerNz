package com.hippo.ehviewer.gallery

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
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
import java.text.NumberFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Selected-gallery storage operations. Files are enumerated only for an explicit inspect/delete action. */
object GalleryResultStorage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal data class Usage(val count: Long, val bytes: Long)
    internal data class Summary(val translation: Usage, val superResolution: Usage, val originals: Usage?,
                                val downloadedOriginals: Usage)

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
        val action = LayoutInflater.from(context).inflate(R.layout.gallery_delete_original_results, null)
        val summaryView = action.findViewById<TextView>(R.id.gallery_results_summary)
        action.findViewById<TextView>(R.id.gallery_results_selection).text =
            context.getString(R.string.gallery_results_selected, selected.size)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.gallery_results_delete_title)
            .setView(action)
            .setNeutralButton(android.R.string.cancel, null)
            .create()
        var confirmation: AlertDialog? = null
        fun bindDeleteButton(id: Int, kind: Kind): ImageButton = action.findViewById<ImageButton>(id).apply {
            isEnabled = false
            setOnClickListener {
                if (confirmation?.isShowing == true) return@setOnClickListener
                confirmation = AlertDialog.Builder(context)
                    .setTitle(contentDescription)
                    .setMessage(context.getString(R.string.gallery_results_delete_confirm, selected.size))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        if (dialog.isShowing) {
                            dialog.dismiss()
                            delete(kind)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create().also { prompt ->
                        prompt.setOnDismissListener { if (confirmation === prompt) confirmation = null }
                        prompt.show()
                    }
            }
        }
        val deleteTranslation = bindDeleteButton(R.id.delete_translation_results, Kind.TRANSLATION)
        val deleteSuperResolution = bindDeleteButton(R.id.delete_super_resolution_results, Kind.SUPER_RESOLUTION)
        val deleteOriginal = bindDeleteButton(R.id.delete_original_results, Kind.ORIGINAL)
        dialog.show()
        val numberFormat = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])
        fun bindUsage(countId: Int, sizeId: Int, usage: Usage?, button: ImageButton? = null) {
            if (usage != null) {
                action.findViewById<TextView>(countId).text = numberFormat.format(usage.count)
                action.findViewById<TextView>(sizeId).text = Formatter.formatFileSize(context, usage.bytes)
            }
            button?.isEnabled = usage != null && (usage.count > 0 || usage.bytes > 0)
        }
        val loading = scope.launch {
            try {
                val summary = inspect(app, selected)
                ensureActive()
                if (!dialog.isShowing) return@launch
                bindUsage(R.id.gallery_results_initial_count, R.id.gallery_results_initial_size, summary.originals)
                bindUsage(R.id.gallery_results_translation_count, R.id.gallery_results_translation_size,
                    summary.translation, deleteTranslation)
                bindUsage(R.id.gallery_results_sr_count, R.id.gallery_results_sr_size,
                    summary.superResolution, deleteSuperResolution)
                bindUsage(R.id.gallery_results_original_count, R.id.gallery_results_original_size,
                    summary.downloadedOriginals, deleteOriginal)
                if (summary.originals == null) summaryView.setText(R.string.gallery_results_originals_unavailable)
                else summaryView.visibility = View.GONE
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { summaryView.setText(R.string.gallery_results_inspect_failed) }
        }
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
        lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            confirmation?.dismiss()
            loading.cancel()
            lifecycle?.removeObserver(observer)
        }
    }

    internal suspend fun inspect(context: Context, galleries: List<DownloadInfo>): Summary = withContext(Dispatchers.IO) {
        val ids = galleries.map { it.gid }.toSet()
        val directories = directories(context, galleries)
        val counts = galleries.distinctBy { it.gid }.map { DownloadResultProgress.read(context, it) }
        val translation = TranslationRuntime.lock.withLock {
            val bytes = collect(context, ids, directories, Kind.TRANSLATION, sourceIdentities(galleries)).sumOf { it.length() }
            Usage(counts.sumOf { it.translated.toLong() }, bytes)
        }
        val sr = SuperResolutionModels.lock.withLock {
            val bytes = collect(context, ids, directories, Kind.SUPER_RESOLUTION, sourceIdentities(galleries)).sumOf { it.length() }
            Usage(counts.sumOf { it.enhanced.toLong() }, bytes)
        }
        val originals = try { inspectOriginals(context, galleries, directories) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        val downloadedOriginals = Usage(counts.sumOf { it.originals.toLong() },
            collect(context, ids, directories, Kind.ORIGINAL).sumOf { it.length() })
        Summary(translation, sr, originals, downloadedOriginals)
    }

    /** Image metadata only: no decoding, hashing, extraction, or processing-progress backfill. */
    private suspend fun inspectOriginals(context: Context, galleries: List<DownloadInfo>, directories: List<UniFile>): Usage {
        val archives = linkedMapOf<String, DownloadInfo>()
        for (info in galleries) {
            val source = info.archiveUri ?: continue
            if (!TranslationStorage.hasGalleryDirectory(info)) archives.putIfAbsent(source, info)
        }
        var usage = collectOriginals(directories)
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
                        !name.equals(SuperResolutionExport.DIRECTORY_NAME, true) &&
                        !name.equals(GalleryResultIndex.Kind.ORIGINAL.directory, true)) pending.add(file)
                } else if (file.isFile && GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.any { name.endsWith(it, true) }) {
                    files[file.uri.toString()] = file
                }
            }
        }
        return Usage(files.size.toLong(), files.values.sumOf { it.length() })
    }

    internal suspend fun delete(context: Context, galleries: List<DownloadInfo>, kind: Kind): Boolean {
        val ids = galleries.map { it.gid }.toSet()
        if (kind == Kind.ORIGINAL) {
            withContext(Dispatchers.Main.immediate) { ReaderOriginalController.cancelDownloads(ids) }
            return withContext(Dispatchers.IO) {
                var success = true
                for (gallery in galleries.distinctBy { it.gid }) {
                    val directory = TranslationStorage.galleryDir(context, gallery)
                    if (directory == null && TranslationStorage.hasGalleryDirectory(gallery)) success = false
                    try {
                        GalleryResultIndex.deleteKind(context, gallery.gid, directory, GalleryResultIndex.Kind.ORIGINAL)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        success = false
                    }
                }
                GalleryProcessingProgress.removeMissing(context, ids, kind).join()
                success
            }
        }
        val sourceIdentities = sourceIdentities(galleries)
        // Stop only the selected stage and await its writers before removing files.
        withContext(Dispatchers.Main.immediate) {
            if (kind == Kind.TRANSLATION) TranslationTasks.stopForResultDeletion(ids, sourceIdentities)
            else SuperResolutionTasks.stopForResultDeletion(ids, sourceIdentities)
        }
        return withContext(Dispatchers.IO) {
            val lock = if (kind == Kind.TRANSLATION) TranslationRuntime.lock else SuperResolutionModels.lock
            lock.withLock {
                val directories = directories(context, galleries)
                val files = collect(context, ids, directories, kind, sourceIdentities)
                var success = true
                val removed = files.filter { file ->
                    val deleted = runCatching { file.delete() || !file.exists() }.getOrDefault(false)
                    if (!deleted) success = false
                    deleted
                }
                for (gallery in galleries) GalleryResultIndex.deleteKind(context, gallery.gid,
                    TranslationStorage.galleryDir(context, gallery),
                    if (kind == Kind.TRANSLATION) GalleryResultIndex.Kind.TL else GalleryResultIndex.Kind.SR,
                    deleteEmptyRoot = ArchiveResultStorage.isArchiveUri(gallery.archiveUri))
                for (identity in sourceIdentities) GalleryResultIndex.deleteCachedKind(context, identity,
                    if (kind == Kind.TRANSLATION) GalleryResultIndex.Kind.TL else GalleryResultIndex.Kind.SR)
                if (kind == Kind.SUPER_RESOLUTION) for (gid in ids)
                    for (root in SuperResolutionStore.galleryRoots(context, gid))
                        if (root.isDirectory) GalleryResultIndex.pruneEmptyDirectories(checkNotNull(UniFile.fromFile(root)))
                GalleryProcessingProgress.forget(context, removed.map { it.uri }).join()
                GalleryProcessingProgress.removeMissing(context, ids, kind).join()
                success
            }
        }
    }

    private fun sourceIdentities(galleries: List<DownloadInfo>) = galleries.mapNotNull { gallery ->
        gallery.archiveUri?.let { uri ->
            if (ArchiveResultStorage.isArchiveUri(uri)) "archive:$uri"
            else if (LocalFolderGallerySource.isLocalFolderGallery(uri)) uri else null
        }
    }.toSet()

    private fun directories(context: Context, galleries: List<DownloadInfo>) = galleries.mapNotNull {
        val directory = TranslationStorage.galleryDir(context, it)
        check(directory != null || !TranslationStorage.hasGalleryDirectory(it)) { "Gallery directory unavailable" }
        directory
    }.distinctBy { it.uri }

    /** Include partial/checkpoint bytes; an internal tile page is storage, not one image per tile. */
    internal suspend fun collect(context: Context, ids: Set<Long>, galleries: List<UniFile>, kind: Kind,
                                 sourceIdentities: Set<String> = emptySet()): List<UniFile> {
        val files = linkedMapOf<String, UniFile>()
        if (kind == Kind.ORIGINAL) {
            // Local OR files use a dedicated subfolder. Private files are scoped by gallery ID.
            for (gid in ids) for (entry in GalleryResultIndex.files(context, gid, null, GalleryResultIndex.Kind.ORIGINAL)) {
                files[entry.uri.toString()] = entry
            }
            for (directory in galleries) for (entry in GalleryResultIndex.files(context, null, directory,
                GalleryResultIndex.Kind.ORIGINAL, "")) {
                files[entry.uri.toString()] = entry
            }
            return files.values.toList()
        }
        for (gid in ids) for (entry in GalleryResultIndex.files(context, gid, null,
            if (kind == Kind.TRANSLATION) GalleryResultIndex.Kind.TL else GalleryResultIndex.Kind.SR)) files[entry.uri.toString()] = entry
        for (identity in sourceIdentities) for (entry in GalleryResultIndex.files(context, null, null,
            if (kind == Kind.TRANSLATION) GalleryResultIndex.Kind.TL else GalleryResultIndex.Kind.SR, identity)) files[entry.uri.toString()] = entry
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
