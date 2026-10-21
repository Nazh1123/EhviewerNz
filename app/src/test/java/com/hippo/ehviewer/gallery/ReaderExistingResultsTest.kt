package com.hippo.ehviewer.gallery

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.preference.PreferenceManager
import com.hippo.unifile.UniFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderExistingResultsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: Provider
    private lateinit var directory: File
    @Before fun setup() {
        GalleryResultIndex.root(app).deleteRecursively(); GalleryResultIndex.dropMemory()
        directory = File(app.filesDir, "reader-index").apply { deleteRecursively(); mkdirs() }
        provider = Provider(checkNotNull(UniFile.fromFile(directory)))
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(GalleryResultIndex.PREFER_EXISTING, true).commit()
    }
    private fun seed(kind: GalleryResultIndex.Kind, scale: Int = 1) {
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try { GalleryResultIndex.open(app, provider.translationIdentity, null, provider.translationDirectory, true)
            .write(GalleryResultIndex.Source("0"), kind, "png", scale) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    @Test fun bothResultsDisplayWhileProcessingIsOffWithoutReadingOriginalBytes() {
        seed(GalleryResultIndex.Kind.TL); seed(GalleryResultIndex.Kind.SR, 2)
        val reader = ReaderExistingResults(app, provider, null)
        try {
            reader.onPageChanged(0)
            await { provider.hasTranslatedPage(0) && provider.hasEnhancedPage(0) }
            assertNotNull(provider.getTranslationOverlay(0)); assertNotNull(provider.getEnhancedTiles(0))
            assertEquals(0, provider.sourceReads)
            provider.toggleTranslationOverlay(0); assertNull(provider.getTranslationOverlay(0))
            provider.toggleEnhancedPage(0); assertNull(provider.getEnhancedTiles(0))
            PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(GalleryResultIndex.PREFER_EXISTING, false).commit()
            reader.resume()
            assertFalse(provider.hasTranslatedPage(0)); assertFalse(provider.hasEnhancedPage(0))
        } finally { reader.close(); provider.stop() }
    }
    @Test fun emptyGalleryDoesNotReadSourcesOrCreateCaches() {
        val reader = ReaderExistingResults(app, provider, null)
        try {
            reader.onPageChanged(0)
            repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS); Thread.sleep(5) }
            assertEquals(0, provider.sourceReads)
            assertEquals(0, provider.metadataReads)
            assertFalse(provider.hasTranslatedPage(0)); assertFalse(provider.hasEnhancedPage(0))
            assertFalse(GalleryResultIndex.root(app).exists())
        } finally { reader.close(); provider.stop() }
    }
    @Test fun missingManifestsSkipAllPageWorkUntilPublicationOrResume() {
        provider.pageCount = 10000
        val reader = ReaderExistingResults(app, provider, null)
        fun idle() = await { ReflectionHelpers.getField<Job?>(reader, "job")?.isCompleted == true }
        try {
            reader.onPageChanged(0)
            idle()
            val queries = provider.directoryReads
            assertTrue(queries > 0)
            repeat(100) { page -> reader.onPageChanged(page); reader.onPageReady(page) }
            idle()
            assertEquals(queries, provider.directoryReads)
            assertEquals(0, provider.metadataReads)
            assertEquals(0, provider.sourceReads)
            assertFalse(File(directory, GalleryResultIndex.MANIFEST).exists())
            assertFalse(GalleryResultIndex.root(app).exists())

            reader.resume()
            idle()
            assertTrue(provider.directoryReads > queries)
            assertEquals(0, provider.metadataReads)
            // A newly published result invalidates the gallery-wide negative memo immediately.
            reader.onPageChanged(0)
            seed(GalleryResultIndex.Kind.TL)
            await { provider.hasTranslatedPage(0) }
            assertNotNull(provider.getTranslationOverlay(0))
        } finally { reader.close(); provider.stop() }
    }
    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS); Thread.sleep(5)
            assertTrue("Timed out loading indexed results", System.nanoTime() < deadline)
        }
    }
    private class Provider(private val directory: UniFile) : GalleryProvider2() {
        var sourceReads = 0
        var metadataReads = 0
        var directoryReads = 0
        var pageCount = 1
        override fun getTranslationIdentity() = "dir:${directory.uri}"
        override fun getTranslationDirectory(): UniFile { directoryReads++; return directory }
        override fun getPreviewFile(index: Int): UniFile? { metadataReads++; return null }
        override fun size() = pageCount
        override fun getError(): String? = null
        override fun getImageFilename(index: Int) = "$index"
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun save(index: Int, file: UniFile): Boolean { sourceReads++; error("The display-only loader must not read original bytes") }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? { sourceReads++; error("Unexpected source read") }
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
