package com.hippo.ehviewer.gallery

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProcessingProgress.Kind
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.ehviewer.translation.TranslationTasks
import com.hippo.unifile.UniFile
import com.hippo.unifile.UriHandler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ResultDeletionTasksTest {
    @get:Rule val temp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun deletingEitherKindWaitsForItsLastWriterAndLeavesTheOppositeTaskRunning() {
        for ((position, kind) in listOf(Kind.TRANSLATION, Kind.SUPER_RESOLUTION).withIndex()) {
            val folder = temp.newFolder("gallery-$position")
            val uri = Uri.fromFile(folder)
            val source = LocalFolderGallerySource.create(uri, "").encode()
            val handler = UriHandler { _, candidate -> if (candidate == uri) UniFile.fromFile(folder) else null }
            UniFile.addUriHandler(handler)
            val info = DownloadInfo(200L + position).apply { archiveUri = source }
            val provider = Provider(source)
            val translation = TranslationTasks.acquire(app, provider, info)
            val enhancement = SuperResolutionTasks.acquire(app, provider, info)
            val selected: Any = if (kind == Kind.TRANSLATION) translation else enhancement
            val other: Any = if (kind == Kind.TRANSLATION) enhancement else translation
            val selectedFolder = File(folder, if (kind == Kind.TRANSLATION) "_translated" else "_super_resolution").apply { mkdirs() }
            val otherFolder = File(folder, if (kind == Kind.TRANSLATION) "_super_resolution" else "_translated").apply { mkdirs() }
            val suffix = if (kind == Kind.TRANSLATION) "tl" else "sr"
            val original = File(folder, "original.jpg").apply { writeText("original") }
            val existing = File(selectedFolder, "one_$suffix.png").apply { writeText("result") }
            val preserved = File(otherFolder, "other_${if (suffix == "tl") "sr" else "tl"}.png").apply { writeText("other result") }
            val late = File(selectedFolder, "late_$suffix.png")
            val entered = CountDownLatch(1)
            val cleaning = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> failure.set(error) })
            val selectedWriter = scope.launch(Dispatchers.Default) {
                try { entered.countDown(); awaitCancellation() }
                finally { withContext(NonCancellable) {
                    cleaning.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    late.writeText("last native write during cancellation")
                } }
            }
            val otherWriter = scope.launch(Dispatchers.Default) { awaitCancellation() }
            ReflectionHelpers.setField(selected, "worker", selectedWriter)
            ReflectionHelpers.setField(other, "worker", otherWriter)
            ReflectionHelpers.setField(selected, "enabled", true)
            ReflectionHelpers.setField(other, "enabled", true)
            var success = false
            var deletion: Job? = null
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                deletion = scope.launch { success = GalleryResultStorage.delete(app, listOf(info), kind) }
                assertTrue(cleaning.await(5, TimeUnit.SECONDS))
                assertFalse(deletion.isCompleted); assertTrue(existing.isFile)
                release.countDown()
                await { deletion.isCompleted }
                assertNull(failure.get()); assertTrue(success)
                assertFalse(late.exists()); assertFalse(selectedFolder.exists())
                assertTrue(preserved.isFile); assertEquals("original", original.readText())
                assertTrue(ReflectionHelpers.getField<Boolean>(other, "enabled"))
                assertFalse(otherWriter.isCancelled)
            } finally {
                release.countDown()
                deletion?.cancel()
                TranslationTasks.release(translation)
                SuperResolutionTasks.release(enhancement)
                scope.cancel()
                UniFile.removeUriHandler(handler)
            }
        }
    }

    @Test fun importedArchiveDeletionAlsoStopsExternalReaderIdentityAndClearsItsCache() {
        val uri = "content://archive-deletion/document/source"
        val identity = "archive:$uri"
        val provider = Provider(identity)
        val session = TranslationTasks.acquire(app, provider, null)
        val worker = Job()
        ReflectionHelpers.setField(session, "worker", worker)
        ReflectionHelpers.setField(session, "enabled", true)
        val index = GalleryResultIndex.open(app, identity, null, null, false)
        val page = GalleryResultIndex.Source("chapter/001.jpg")
        val translated = index.write(page, GalleryResultIndex.Kind.TL, "skip") { it.write("skipped".toByteArray()) }
        val opposite = index.write(page, GalleryResultIndex.Kind.SR, "skip") { it.write("other".toByteArray()) }
        val failure = AtomicReference<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> failure.set(error) })
        var success = false
        try {
            val deletion = scope.launch {
                success = GalleryResultStorage.delete(app, listOf(DownloadInfo(210).apply { archiveUri = uri }), Kind.TRANSLATION)
            }
            await { deletion.isCompleted }
            assertNull(failure.get()); assertTrue(success)
            assertTrue(worker.isCancelled); assertFalse(session.enabled)
            assertFalse(translated.exists()); assertTrue(opposite.isFile)
            assertNull(index.find(page, GalleryResultIndex.Kind.TL))
        } finally {
            TranslationTasks.release(session); scope.cancel()
            GalleryResultIndex.deleteCachedKind(app, identity, GalleryResultIndex.Kind.SR)
        }
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!ready() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue(ready())
    }

    private class Provider(private val identity: String) : GalleryProvider2() {
        override fun createTranslationProvider(context: Context) = Provider(identity)
        override fun getTranslationIdentity() = identity
        override fun size() = 1
        override fun getError(): String? = null
        override fun getImageFilename(index: Int) = "001"
        override fun onRequest(index: Int) {}
        override fun onForceRequest(index: Int) {}
        override fun onCancelRequest(index: Int) {}
        override fun save(index: Int, file: UniFile) = false
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
