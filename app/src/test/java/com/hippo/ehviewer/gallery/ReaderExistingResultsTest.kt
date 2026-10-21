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
    private fun seed(kind: GalleryResultIndex.Kind, scale: Int = 1, page: Int = 0) {
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try { GalleryResultIndex.open(app, provider.translationIdentity, null, provider.translationDirectory, true)
            .write(GalleryResultIndex.Source("$page"), kind, "png", scale) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
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
    @Test fun prefetchAndOriginalReadinessReuseDecodedResultsWithoutRepeatedDirectoryQueries() {
        provider.pageCount = 2
        for (page in 0..1) { seed(GalleryResultIndex.Kind.TL, page = page); seed(GalleryResultIndex.Kind.SR, 2, page) }
        shadowOf(Looper.getMainLooper()).idle() // Deliver publication events before subscribing.
        val reader = ReaderExistingResults(app, provider, null)
        val decode = reader.decodeTranslation
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        reader.decodeTranslation = { decoded.incrementAndGet(); decode(it) }
        try {
            reader.onPageChanged(0)
            await { provider.hasTranslatedPage(1) && provider.hasEnhancedPage(1) }
            val originalOverlay = provider.getTranslationOverlay(0)
            val enhanced = provider.getEnhancedTiles(0)
            val queries = provider.directoryReads
            repeat(20) { reader.onPageReady(0) }
            reader.onPageChanged(1)
            assertNotNull(provider.getTranslationOverlay(1)); assertNotNull(provider.getEnhancedTiles(1))
            reader.onPageChanged(0)
            assertSame(originalOverlay, provider.getTranslationOverlay(0))
            assertSame(enhanced, provider.getEnhancedTiles(0))
            await { ReflectionHelpers.getField<Job?>(reader, "job")?.isCompleted == true }
            assertEquals(2, decoded.get())
            assertEquals(queries, provider.directoryReads)
        } finally { reader.close(); provider.stop() }
    }
    @Test fun enhancedResultPublishesWithoutWaitingForTranslationDecode() {
        seed(GalleryResultIndex.Kind.TL); seed(GalleryResultIndex.Kind.SR, 2)
        shadowOf(Looper.getMainLooper()).idle()
        val reader = ReaderExistingResults(app, provider, null)
        val decode = reader.decodeTranslation
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        reader.decodeTranslation = {
            entered.countDown()
            check(finish.await(8, TimeUnit.SECONDS))
            decode(it)
        }
        try {
            reader.onPageChanged(0)
            await { entered.count == 0L && provider.hasEnhancedPage(0) }
            assertFalse(provider.hasTranslatedPage(0))
            finish.countDown()
            await { provider.hasTranslatedPage(0) }
        } finally { finish.countDown(); reader.close(); provider.stop() }
    }
    @Test fun speculativeDecodeDoesNotEvictTheVisibleOverlayWhenCacheIsSmall() {
        provider.pageCount = 2
        seed(GalleryResultIndex.Kind.TL); seed(GalleryResultIndex.Kind.TL, page = 1)
        shadowOf(Looper.getMainLooper()).idle()
        val cache = ReflectionHelpers.getField<android.util.LruCache<Int, com.hippo.lib.glview.image.ImageWrapper>>(provider, "mTranslatedCache")
        cache.resize(2048) // Each 16x24 overlay costs 1536 bytes; only the visible one fits.
        val reader = ReaderExistingResults(app, provider, null)
        val decode = reader.decodeTranslation
        val count = java.util.concurrent.atomic.AtomicInteger()
        reader.decodeTranslation = { count.incrementAndGet(); decode(it) }
        try {
            reader.onPageChanged(0)
            await { ReflectionHelpers.getField<Job?>(reader, "job")?.isCompleted == true }
            assertTrue(provider.hasTranslatedPage(0)); assertFalse(provider.hasTranslatedPage(1))
            assertEquals(1, count.get())
            reader.onPageChanged(1)
            await { provider.hasTranslatedPage(1) }
            assertEquals(2, count.get())
        } finally { reader.close(); provider.stop() }
    }
    @Test fun publishingAnotherPageDoesNotInvalidateTheVisibleResultsOrManualComparison() {
        provider.pageCount = 2
        seed(GalleryResultIndex.Kind.TL)
        shadowOf(Looper.getMainLooper()).idle()
        val reader = ReaderExistingResults(app, provider, null)
        val decode = reader.decodeTranslation
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        reader.decodeTranslation = { decoded.incrementAndGet(); decode(it) }
        try {
            reader.onPageChanged(0)
            await { provider.hasTranslatedPage(0) }
            val overlay = provider.getTranslationOverlay(0)
            await { ReflectionHelpers.getField<Job?>(reader, "job")?.isCompleted == true }
            provider.toggleTranslationOverlay(0)
            seed(GalleryResultIndex.Kind.TL, page = 1)
            await { provider.hasTranslatedPage(1) }
            assertNull(provider.getTranslationOverlay(0))
            provider.toggleTranslationOverlay(0)
            assertSame(overlay, provider.getTranslationOverlay(0))
            assertEquals(2, decoded.get())
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
