package com.hippo.ehviewer.translation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.gallery.LocalFolderGallerySource
import com.hippo.unifile.UniFile
import com.hippo.unifile.UriHandler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TranslationResume
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34],
    shadows = [TranslationPersistenceLifecycleTest.AppShadow::class, TranslationPersistenceLifecycleTest.DownloadShadow::class])
class TranslationPersistenceLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var gallery: File
    private lateinit var provider: Provider
    private lateinit var uriHandler: UriHandler
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val jobs = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, error -> errors.add(error) })
    private val stats = PageStats(0, 0, 0, 0, 0, 0, 0, 0)
    private val options = TranslationOptions(ahead = 2, persistDownloaded = true)
    private val savedDir get() = File(gallery, TranslationResultStore.DIRECTORY_NAME)

    @Before fun setUp() {
        gallery = temp.newFolder("gallery")
        val galleryUri = Uri.fromFile(gallery)
        // Android URI paths do not round-trip Windows drive letters on SDK 26.
        uriHandler = UriHandler { _, uri -> if (uri == galleryUri) UniFile.fromFile(gallery) else null }
        UniFile.addUriHandler(uriHandler)
        download = DownloadInfo(71).apply {
            archiveUri = LocalFolderGallerySource.create(galleryUri, "").encode()
            state = DownloadInfo.STATE_FINISH
        }
        manager = DownloadManager(context)
        val sources = (0..2).map { page -> temp.newFile("source$page.png").also { file ->
            val bitmap = Bitmap.createBitmap(8 + page, 8, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        } }
        provider = Provider(sources)
        TranslationCache(TranslationStorage.cacheDir(context)).clear()
    }

    @After fun tearDown() {
        jobs.cancel()
        UniFile.removeUriHandler(uriHandler)
        assertEquals(emptyList<Throwable>(), errors.toList())
    }

    @Test fun deletingSelectedModelStillAllowsRealEnableToRestoreImagesAndSettleMissingPages() {
        val selected = options.copy(nativeModelId = "a".repeat(64), nativeModelName = "deleted.gguf")
        TranslationSettings(context).save(selected)
        val nativeStore = NativeModelStore(context) { }
        nativeStore.file(selected.nativeModelId).apply { parentFile!!.mkdirs(); writeText("GGUF" + "0".repeat(28)) }
        // Leave the middle page uncached: it must not block restoration of the following page.
        for (page in listOf(0, 2)) {
            val results = store(selected, page.toString())
            results.writeImage(results.key(provider.sources[page])) { out ->
                provider.sources[page].inputStream().use { it.copyTo(out) }
            }
        }
        nativeStore.delete(selected.nativeModelId)
        assertEquals("", TranslationSettings(context).read().nativeModelId)
        assertFalse(TranslationModels(context).ready())
        withSession { session ->
            session.disable()
            session.onPageChanged(0)
            assertTrue(session.enable())
            await { !session.working && session.states.size == 3 }
            assertEquals(selected.cacheIdentity(), session.activeOptions.cacheIdentity())
            assertEquals(R.string.translation_done, session.states[0])
            assertEquals(R.string.translation_models_required, session.states[1])
            assertEquals(R.string.translation_done, session.states[2])
            assertTrue(session.enabled)
        }
    }

    @Test fun partialResultIsPersistedRestoredAndPreservedWhenRetryHasNoModels() {
        val selected = options.copy(ahead = 0)
        TranslationSettings(context).save(selected)
        val key = store(selected).key(provider.sources[0])
        val checkpoint = TranslationResume(listOf("one", "two"), mapOf(0 to "译文"))
        withSession(selected) { session ->
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val job = jobs.launch {
                assertTrue(session.finishWithSource(0, TranslationPageRequest(0, false), key, selected,
                    resume = { checkpoint }) { PageResult.Translated(image, stats) })
            }
            await { job.isCompleted }
            assertTrue(image.isRecycled)
            assertEquals(R.string.translation_partial, session.states[0])
            assertFalse(File(savedDir, "0_tl.png").exists())
            assertEquals(checkpoint, store(selected).readResume(key))
        }
        TranslationRuntime.preparedPages.clear()
        withSession(selected) { reopened ->
            reopened.disable()
            reopened.onPageChanged(0)
            assertTrue(reopened.enable())
            await { !reopened.working && reopened.states[0] == R.string.translation_partial }
            assertTrue(reopened.isPageSettled(0))
            assertEquals(1, reopened.partial)
            reopened.enqueue(0, true, retryMissing = true)
            await { !reopened.working }
            assertEquals(R.string.translation_partial, reopened.states[0])
            assertEquals(checkpoint, store(selected).readResume(key))
            assertEquals("0_tl.partial", store(selected).existingImage(key)?.name)
            val epoch = ReflectionHelpers.getField<Int>(reopened, "generation")
            val complete = jobs.launch {
                assertTrue(reopened.finishWithSource(epoch, TranslationPageRequest(0, true, retryMissing = true), key, selected) {
                    PageResult.Translated(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), stats)
                })
            }
            await { complete.isCompleted }
            assertEquals(R.string.translation_done, reopened.states[0])
            assertEquals(0, reopened.partial)
            assertTrue(File(savedDir, "0_tl.png").isFile)
            assertFalse(File(savedDir, "0_tl.partial").exists())
            assertNull(store(selected).readResume(key))
        }
    }

    @Test fun ordinaryReadingAndLookaheadPersistBeforeDownloadFinishesAndReopenWithoutModels() {
        download.state = DownloadInfo.STATE_DOWNLOAD
        val cached = store(options.copy(persistDownloaded = false))
        provider.sources.forEach { source -> cached.writeImage(cached.key(source)) { out -> source.inputStream().use { it.copyTo(out) } } }
        withSession { session ->
            ReflectionHelpers.setField(session, "activated", true)
            session.onPageChanged(0)
            await { !session.working && session.states.size == 3 }
            assertEquals(3, session.states.values.count { it == R.string.translation_done })
            assertEquals(setOf("0_tl.png", "1_tl.png", "2_tl.png"),
                savedDir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet())
            assertFalse(TranslationModels(context).ready())
        }
        TranslationCache(TranslationStorage.cacheDir(context)).clear()
        withSession(options.copy(persistDownloaded = false)) { reopened ->
            ReflectionHelpers.setField(reopened, "activated", true)
            reopened.onPageChanged(0)
            await { !reopened.working && reopened.states.size == 3 }
            assertTrue(reopened.enabled)
            assertEquals(3, reopened.states.values.count { it == R.string.translation_done })
        }
    }

    @Test fun readerExitAfterRenderingStillSavesAndRecyclesTheResult() = withSession { session ->
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val key = store().key(provider.sources[0])
        completeAfterCancellation(session, key, PageResult.Translated(bitmap, stats, PageAnalysis(mask, emptyList()))) {
            session.leaveReader()
        }
        assertTrue(File(savedDir, "0_tl.png").isFile)
        assertTrue(bitmap.isRecycled)
        assertTrue(mask.isRecycled)
        assertNull(session.states[0])
        TranslationCache(TranslationStorage.cacheDir(context)).clear()
        assertNotNull(store(options.copy(persistDownloaded = false)).existingImage(key))
    }

    @Test fun pageSupersededAfterRenderingSavesWithoutDisplayingAnObsoletePage() = withSession { session ->
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val key = store().key(provider.sources[0])
        val request = TranslationPageRequest(0, false)
        val job = jobs.launch {
            session.finishWithSource(0, request, key, options) {
                request.supersede()
                PageResult.Translated(bitmap, stats)
            }
        }
        await { job.isCompleted }
        assertTrue(File(savedDir, "0_tl.png").isFile)
        assertTrue(bitmap.isRecycled)
        assertNull(session.states[0])
    }

    @Test fun readerExitAfterNoTextDetectionStillSavesTheSkipMarker() = withSession { session ->
        val key = store().key(provider.sources[0])
        completeAfterCancellation(session, key, PageResult.Skipped("No text", stats)) { session.leaveReader() }
        assertTrue(File(savedDir, "0_tl.skip").isFile)
        TranslationCache(TranslationStorage.cacheDir(context)).clear()
        assertTrue(store(options.copy(persistDownloaded = false)).isSkipped(key))
        assertNull(session.states[0])
    }

    @Test fun savingUsesDownloadStateAtCompletionRatherThanWhenInferenceStarted() = withSession { session ->
        download.state = DownloadInfo.STATE_DOWNLOAD
        val key = store().key(provider.sources[0])
        val job = jobs.launch {
            session.finishWithSource(0, TranslationPageRequest(0, false), key, options) {
                download.state = DownloadInfo.STATE_FINISH
                PageResult.Skipped("No text", stats)
            }
        }
        await { job.isCompleted }
        assertTrue(File(savedDir, "0_tl.skip").isFile)
    }

    @Test fun enablingPersistenceAdoptsAllSettledReaderResultsIncludingOutOfWindowAndSkippedPages() =
        withSession(options.copy(persistDownloaded = false)) { session ->
            for (page in 0..2) {
                val key = store().key(provider.sources[page])
                val job = jobs.launch {
                    session.finishWithSource(0, TranslationPageRequest(page, false), key, session.activeOptions) {
                        if (page == 0) PageResult.Skipped("No text", stats)
                        else PageResult.Translated(Bitmap.createBitmap(8 + page, 8, Bitmap.Config.ARGB_8888), stats)
                    }
                }
                await { job.isCompleted }
            }
            assertFalse(savedDir.exists())
            // Migrating the first skip marker must not prune the remaining images.
            session.activeOptions = options.copy(ahead = 0, cacheSizeMb = 0)
            session.onPageChanged(0)
            await { savedDir.listFiles()?.size == 3 && ReflectionHelpers.getField<Job?>(session, "retentionJob") == null }
            assertNull(ReflectionHelpers.getField<Job?>(session, "worker"))
            TranslationCache(TranslationStorage.cacheDir(context)).clear()
            assertNotNull(store(name = "1").existingImage(store().key(provider.sources[1])))
            assertNotNull(store(name = "2").existingImage(store().key(provider.sources[2])))
            assertTrue(store().isSkipped(store().key(provider.sources[0])))
        }

    @Test fun failedTranslationDoesNotPublishAnImageOrSkipMarker() = withSession { session ->
        val key = store().key(provider.sources[0])
        val job = jobs.launch {
            assertFalse(session.finishWithSource(0, TranslationPageRequest(0, false), key, options) {
                PageResult.Failed("incomplete")
            })
        }
        await { job.isCompleted }
        assertFalse(savedDir.exists())
        assertNull(store().existingImage(key))
        assertFalse(store().isSkipped(key))
    }

    private fun completeAfterCancellation(session: GalleryTranslationSession, key: String, result: PageResult,
                                          cancel: () -> Unit) {
        val rendering = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val job = jobs.launch {
            session.finishWithSource(0, TranslationPageRequest(0, false), key, options) {
                rendering.countDown()
                check(finished.await(5, TimeUnit.SECONDS))
                result
            }
        }
        try {
            await { rendering.count == 0L }
            cancel()
            job.cancel()
        } finally { finished.countDown() }
        await { job.isCompleted }
        assertTrue(job.isCancelled)
    }

    private suspend fun GalleryTranslationSession.finishWithSource(epoch: Int, request: TranslationPageRequest,
            key: String, selected: TranslationOptions,
            resume: () -> TranslationResume? = { null }, translate: suspend () -> PageResult): Boolean {
        val original = requireNotNull(BitmapFactory.decodeFile(this@TranslationPersistenceLifecycleTest.provider.sources[request.page].absolutePath))
        return try { finishTranslation(epoch, request, key, selected, original, resume, translate) }
            finally { original.recycle() }
    }

    private fun store(selected: TranslationOptions = options, name: String = "0") = TranslationResultStore(
        TranslationStorage.cacheDir(context), UniFile.fromFile(gallery), selected, download.gid, true,
        name)

    private fun withSession(selected: TranslationOptions = options, test: (GalleryTranslationSession) -> Unit) {
        val session = TranslationTasks.acquire(context, provider, download)
        session.activeOptions = selected
        ReflectionHelpers.setField(session, "enabled", true)
        try { test(session) } finally {
            TranslationTasks.release(session)
            await { ReflectionHelpers.getField<Job?>(session, "worker") == null &&
                ReflectionHelpers.getField<Job?>(session, "retentionJob") == null }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Timed out waiting for persisted translation", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private class Provider(val sources: List<File>) : GalleryProvider2() {
        override fun size() = sources.size
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index"
        override fun save(index: Int, file: UniFile): Boolean {
            file.openOutputStream().use { out -> sources[index].inputStream().use { it.copyTo(out) } }
            return true
        }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }

    companion object {
        private lateinit var download: DownloadInfo
        private lateinit var manager: DownloadManager
    }

    @Implements(EhApplication::class)
    class AppShadow {
        companion object {
            @JvmStatic @Implementation fun getDownloadManager(context: Context): DownloadManager = manager
        }
    }

    @Implements(DownloadManager::class)
    class DownloadShadow {
        @Implementation fun __constructor__(context: Context) = Unit
        @Implementation fun getDownloadInfo(gid: Long): DownloadInfo? = download.takeIf { it.gid == gid }
    }
}
