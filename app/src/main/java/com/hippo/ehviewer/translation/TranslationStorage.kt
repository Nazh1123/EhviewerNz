package com.hippo.ehviewer.translation

import android.content.Context
import android.widget.Toast
import com.hippo.ehviewer.R
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.LocalFolderGallerySource
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

object TranslationStorage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal fun cacheDir(context: Context) = File(context.cacheDir, "translated-pages")
    internal fun legacyPersistentDir(context: Context) = File(context.noBackupFilesDir, "translated-galleries")

    internal fun hasGalleryDirectory(info: DownloadInfo) = info.archiveUri.isNullOrEmpty() ||
        LocalFolderGallerySource.isLocalFolderGallery(info.archiveUri)

    /** Resolve the actual source folder, including imported SAF galleries outside the download root. */
    @JvmStatic fun sourceDir(context: Context, source: LocalFolderGallerySource): UniFile? {
        val uri = source.getTreeUri()
        var dir = if (UniFile.isTreeUri(uri)) UniFile.fromTreeUri(context, uri) else UniFile.fromUri(context, uri)
        if (source.relativePath.isNotEmpty()) {
            for (segment in source.relativePath.split('/')) dir = dir?.findFile(segment)
        }
        return dir?.takeIf { it.isDirectory }
    }

    internal fun galleryDir(context: Context, info: DownloadInfo): UniFile? {
        val source = LocalFolderGallerySource.parse(info.archiveUri)
        val directory = if (source != null) {
            sourceDir(context, source)
        } else if (!info.archiveUri.isNullOrEmpty()) null
        else {
            val existing = SpiderDen.getExistingGalleryDownloadDir(info)
            // Generic SAF providers use opaque IDs; subFile's guessed URI may not exist.
            existing?.takeIf { it.isDirectory } ?: EhDB.getDownloadDirname(info.gid)?.let {
                Settings.getDownloadLocation()?.findFile(it)
            }
        }
        return directory?.takeIf { it.isDirectory }
    }

    fun pruneCache(context: Context) {
        val app = context.applicationContext
        scope.launch {
            TranslationRuntime.lock.withLock {
                runCatching { TranslationCache(cacheDir(app), TranslationSettings(app).read().cacheLimitBytes).prune() }
            }
        }
    }

    @JvmStatic fun deleteGalleries(context: Context, galleryIds: LongArray) {
        val app = context.applicationContext
        val ids = galleryIds.toSet()
        scope.launch {
            val success = runCatching {
                TranslationRuntime.lock.withLock {
                    val manager = EhApplication.getDownloadManager(app)
                    var directoriesAccessible = true
                    val directories = ids.mapNotNull { gid ->
                        manager.getDownloadInfo(gid)?.let { info ->
                            val directory = galleryDir(app, info)
                            if (directory == null && hasGalleryDirectory(info)) directoriesAccessible = false
                            directory?.let { gid to it }
                        }
                    }.toMap()
                    TranslationResultStore.deleteGalleries(cacheDir(app), legacyPersistentDir(app), ids, directories) &&
                        directoriesAccessible
                }
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (success) R.string.translation_results_deleted
                    else R.string.translation_results_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
