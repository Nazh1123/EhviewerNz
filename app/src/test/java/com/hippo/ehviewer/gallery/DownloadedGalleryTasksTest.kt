package com.hippo.ehviewer.gallery

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.ehviewer.translation.GalleryPageWork
import com.hippo.ehviewer.translation.GalleryPageWorkRegistry
import com.hippo.ehviewer.translation.TranslationTasks
import com.hippo.unifile.UniFile
import com.hippo.unifile.UriHandler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [DownloadedGalleryTasksTest.DenShadow::class])
class DownloadedGalleryTasksTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun bothDetailActionsStartDetachedFullTasksAndWaitBehindTheCurrentGallery() {
        val blocker = GalleryPageWorkRegistry.acquire("detail-test-owner")
        val owner = GalleryPageWork.Queue({ true }, { listOf(0) }, { false }, fullGallery = { true })
        blocker.register(GalleryPageWork.Stage.TRANSLATION, owner)
        val info = GalleryInfo().apply { gid = 8101; title = "Downloaded gallery" }
        val source = Provider()
        val translation = TranslationTasks.acquire(context, source, info)
        val enhancement = SuperResolutionTasks.acquire(context, source, info)
        try {
            assertTrue(DownloadedGalleryTasks.start(context, source, info, false))
            assertTrue(DownloadedGalleryTasks.start(context, source, info, true))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(translation.fullGallery); assertTrue(enhancement.fullGallery)
            assertTrue(translation.working); assertTrue(enhancement.working)
            assertNull(translation.reader); assertNull(enhancement.reader)
            assertFalse(translation.readerOpen); assertFalse(translation.browsing)
            assertEquals(0, translation.current); assertEquals(0, enhancement.current)
            val work = GalleryPageWorkRegistry.acquire("gallery:${info.gid}")
            assertNull(work.next(GalleryPageWork.Stage.TRANSLATION))
            assertNull(work.next(GalleryPageWork.Stage.SUPER_RESOLUTION))
            assertEquals(0, source.starts)
        } finally {
            TranslationTasks.release(translation)
            SuperResolutionTasks.release(enhancement)
            shadowOf(Looper.getMainLooper()).idle()
            blocker.remove(GalleryPageWork.Stage.TRANSLATION, owner)
            GalleryPageWorkRegistry.release("detail-test-owner", blocker)
        }
    }

    @Test fun localSourcesAreResolvedWithoutStartingProvidersOrCreatingMissingFolders() {
        val download = DownloadInfo(8102)
        directory = checkNotNull(UniFile.fromFile(temporaryFolder.newFolder("download")))
        val folderUri = Uri.parse("content://downloaded-task-test/folder")
        val archiveUri = Uri.parse("content://downloaded-task-test/archive")
        val handler = UriHandler { _, uri -> when (uri) {
            folderUri -> directory
            archiveUri -> directory?.findFile("00000001.jpg")
            else -> null
        } }
        UniFile.addUriHandler(handler)
        try {
            assertNull(DownloadedGalleryTasks.source(context, download))
            directory!!.createFile("00000001.jpg")!!.openOutputStream().use { it.write(byteArrayOf(1)) }
            assertTrue(DownloadedGalleryTasks.source(context, download) is DirGalleryProvider)
            download.archiveUri = LocalFolderGallerySource.create(folderUri, "").encode()
            assertTrue(DownloadedGalleryTasks.source(context, download) is LocalFolderGalleryProvider)
            download.archiveUri = LocalFolderGallerySource.create(folderUri, "missing").encode()
            assertNull(DownloadedGalleryTasks.source(context, download))
            assertNull(directory!!.findFile("missing"))
            download.archiveUri = archiveUri.toString()
            assertTrue(DownloadedGalleryTasks.source(context, download) is ArchiveGalleryProvider)
        } finally { UniFile.removeUriHandler(handler); directory = null }
    }

    private class Provider : GalleryProvider2() {
        var starts = 0
        override fun createTranslationProvider(context: Context) = this
        override fun start() { starts++ }
        override fun stop() = Unit
        override fun size() = 3
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index"
        override fun save(index: Int, file: UniFile) = false
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }

    companion object { private var directory: UniFile? = null }
    @Implements(SpiderDen::class)
    class DenShadow {
        companion object {
            @JvmStatic @Implementation fun getExistingGalleryDownloadDir(info: GalleryInfo): UniFile? = directory
        }
    }
}
