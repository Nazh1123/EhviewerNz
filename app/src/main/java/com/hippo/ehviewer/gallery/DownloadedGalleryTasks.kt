package com.hippo.ehviewer.gallery

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.ehviewer.translation.TranslationTasks
import com.hippo.unifile.UniFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Detail-page actions use the same application-owned sessions and gallery queue as the reader. */
object DownloadedGalleryTasks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val submissions = Mutex()

    @JvmStatic fun translate(context: Context, info: GalleryInfo) = enqueue(context, info, false)
    @JvmStatic fun enhance(context: Context, info: GalleryInfo) = enqueue(context, info, true)

    private fun enqueue(context: Context, info: GalleryInfo, enhance: Boolean) {
        val app = context.applicationContext
        scope.launch {
            // Preserve click order even when resolving one SAF source takes longer than another.
            submissions.withLock {
                val provider = withContext(Dispatchers.IO) {
                    try {
                        val download = EhApplication.getDownloadManager(app).getDownloadInfo(info.gid)
                        if (download == null || download.state !in listOf(DownloadInfo.STATE_FINISH, DownloadInfo.STATE_NONE)) null
                        else source(app, download)
                    } catch (_: RuntimeException) { null }
                }
                if (provider == null) {
                    Toast.makeText(app, R.string.no_local_gallery_files, Toast.LENGTH_SHORT).show()
                    return@withLock
                }
                if (start(app, provider, info, enhance)) {
                    Toast.makeText(app, if (enhance) R.string.sr_gallery_submitted
                        else R.string.translation_gallery_submitted, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun source(context: Context, download: DownloadInfo): GalleryProvider2? {
        val folder = LocalFolderGallerySource.parse(download.archiveUri)
        if (folder != null) {
            if (TranslationStorage.sourceDir(context, folder) == null) return null
            return LocalFolderGalleryProvider(context, folder.encode())
        }
        val uri = download.archiveUri?.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        if (uri != null) {
            if (UniFile.fromUri(context, uri)?.isFile != true) return null
            return ArchiveGalleryProvider(context, uri)
        }
        val directory = SpiderDen.getExistingGalleryDownloadDir(download)
            ?.takeIf { it.isDirectory } ?: return null
        if (DirGalleryProvider.listAndSortImageFiles(directory).isNullOrEmpty()) return null
        return DirGalleryProvider(directory)
    }

    internal fun start(context: Context, provider: GalleryProvider2, info: GalleryInfo, enhance: Boolean): Boolean {
        if (enhance) {
            val session = SuperResolutionTasks.acquire(context, provider, info)
            if (session.reader == null && session.current < 0) session.pageChanged(0)
            session.full()
            if (session.reader == null) session.pause(false)
            return session.enabled
        }
        val session = TranslationTasks.acquire(context, provider, info)
        if (session.reader == null && session.current < 0) session.onPageChanged(0)
        val started = session.translateFullGallery()
        if (session.reader == null) session.leaveReader()
        return started
    }
}
