package com.hippo.ehviewer.gallery

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.preference.PreferenceManager
import com.hippo.unifile.UniFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderResultIndexTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var directory: File
    private lateinit var provider: Provider
    private val tl = GalleryResultIndex.Kind.TL
    private val sr = GalleryResultIndex.Kind.SR
    @Before fun setup() {
        GalleryResultIndex.root(app).deleteRecursively(); GalleryResultIndex.dropMemory()
        directory = File(app.filesDir, "reader-status").apply { deleteRecursively(); mkdirs() }
        provider = Provider(checkNotNull(UniFile.fromFile(directory)))
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(GalleryResultIndex.PREFER_EXISTING, false).commit()
    }
    private fun disk(persist: Boolean = true) = GalleryResultIndex.open(app, provider.translationIdentity,
        null, provider.translationDirectory, persist)
    private fun write(page: Int, kind: GalleryResultIndex.Kind, format: String = "png", persist: Boolean = true): UniFile {
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        return try {
            disk(persist).write(GalleryResultIndex.Source("source-$page"), kind, format, if (kind == sr) 2 else 1) {
                if (format == "skip") it.write("skip".toByteArray()) else bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
    @Test fun distantPreviewStatusIsIndependentOfPreferenceAndDoesNotDecodeResults() {
        write(80, tl); write(80, sr)
        shadowOf(Looper.getMainLooper()).idle()
        val reader = ReaderExistingResults(app, provider, null)
        reader.decodeTranslation = { error("Status must not decode translated images") }
        try {
            reader.onPageChanged(0)
            reader.resultIndex.request(80)
            await { reader.resultIndex.flags(80) == 3 }
            assertFalse(provider.hasTranslatedPage(80)); assertFalse(provider.hasEnhancedPage(80))
            val reads = provider.metadataReads
            repeat(30) { reader.resultIndex.request(80); assertEquals(3, reader.resultIndex.flags(80)) }
            assertEquals(reads, provider.metadataReads)
            PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(GalleryResultIndex.PREFER_EXISTING, true).commit()
            reader.decodeTranslation = { null }
            reader.onPageChanged(80)
            await { provider.hasEnhancedPage(80) }
            // Source identity and validated hits are shared with the reader.
            assertEquals(1, provider.readsByPage[80])
        } finally { reader.close(); provider.stop() }
    }
    @Test fun publicationSkipPartialAndDeletionUpdateOnlyTheAffectedPage() {
        write(2, sr); write(3, tl, "skip"); write(4, tl, "partial")
        shadowOf(Looper.getMainLooper()).idle()
        val session = ReaderResultIndex(app, provider, null)
        val listener = Runnable { for (page in 2..4) session.request(page) }
        session.addListener(listener)
        try {
            for (page in 2..4) session.request(page)
            await { session.flags(2) == 2 && provider.readsByPage.keys.containsAll(listOf(2, 3, 4)) }
            assertEquals(0, session.flags(3)); assertEquals(0, session.flags(4))
            val unaffectedReads = provider.readsByPage[2]
            write(3, tl)
            await { session.flags(3) == 1 }
            assertEquals(unaffectedReads, provider.readsByPage[2])
            disk().invalidate(GalleryResultIndex.Source("source-3"), tl)
            await { session.flags(3) == 0 }
            assertEquals(2, session.flags(2))
        } finally { session.close(); provider.stop() }
    }
    @Test fun missingLocalResultFallsBackToCacheAndResumeRechecksExternalDeletion() {
        val local = write(8, tl, "partial")
        write(8, tl, persist = false)
        shadowOf(Looper.getMainLooper()).idle()
        val session = ReaderResultIndex(app, provider, null)
        session.addListener(Runnable { session.request(8) })
        try {
            resolve(session, 8)
            assertEquals(0, session.flags(8)) // Existing local partial keeps normal find() precedence.
            local.delete()
            session.reload()
            await { session.flags(8) == 0 }
            resolve(session, 8)
            await { session.flags(8) == 1 }
            val cache = disk(false).find(GalleryResultIndex.Source("source-8"), tl)!!.file!!
            cache.delete()
            session.reload()
            resolve(session, 8)
            assertEquals(0, session.flags(8))
        } finally { session.close(); provider.stop() }
    }
    @Test fun emptyManifestNeverEnumeratesSourcePagesAndNewPublicationClearsNegativeCache() {
        val session = ReaderResultIndex(app, provider, null)
        val listener = Runnable { session.request(90) }
        session.addListener(listener)
        try {
            resolve(session, 90)
            assertEquals(0, provider.metadataReads)
            assertFalse(GalleryResultIndex.root(app).exists())
            assertTrue(directory.listFiles()!!.isEmpty())
            write(90, tl)
            await { session.flags(90) == 1 }
        } finally { session.close(); provider.stop() }
    }
    private fun resolve(session: ReaderResultIndex, page: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val work = scope.async { session.page(page) }
        try { await { work.isCompleted }; runBlocking { work.await() } }
        finally { scope.cancel() }
    }
    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS); Thread.sleep(5)
            assertTrue("Timed out waiting for result status", System.nanoTime() < deadline)
        }
    }
    private class Provider(private val directory: UniFile) : GalleryProvider2() {
        var metadataReads = 0
        val readsByPage = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        override fun getTranslationIdentity() = "dir:${directory.uri}"
        override fun getTranslationDirectory() = directory
        override fun getPreviewFile(index: Int): UniFile? {
            metadataReads++; readsByPage.merge(index, 1, Int::plus); return null
        }
        override fun getResultPageName(index: Int) = "source-$index"
        override fun size() = 100
        override fun getError(): String? = null
        override fun getImageFilename(index: Int) = "source-$index"
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun save(index: Int, file: UniFile) = error("Status must not read original bytes")
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = error("Unexpected source read")
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
