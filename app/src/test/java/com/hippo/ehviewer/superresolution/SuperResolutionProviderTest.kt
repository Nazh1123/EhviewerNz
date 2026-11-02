package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import com.hippo.ehviewer.translation.engine.SuperResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SuperResolutionProviderTest {
    private class Provider : GalleryProvider() {
        override fun size() = 3
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getError(): String? = null
    }
    private fun image(w: Int, h: Int, color: Int) =
        checkNotNull(Image.create(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }))

    @Test fun enhancedComparisonNeverChangesOriginalInputOrTranslationVisibility() {
        val provider = Provider()
        provider.notifyPageSucceed(0, image(17, 19, -0x10000))
        val enhanced = image(34, 38, -0xff0100)
        val overlay = image(17, 19, -0xffff01)
        provider.setEnhancedPage(0, enhanced)
        provider.setTranslationOverlay(0, overlay)
        provider.setShowTranslations(true)
        try {
            val copy = checkNotNull(provider.getSuperResolutionInput(0))
            assertEquals(17, copy.width)
            assertEquals(19, copy.height)
            assertEquals(-0x10000, copy.getPixel(0, 0))
            copy.recycle()
            provider.toggleEnhancedPage(0)
            assertNull(provider.getEnhancedPage(0))
            assertTrue(provider.hasEnhancedPage(0))
            assertNotNull(provider.getTranslationOverlay(0))
            provider.toggleTranslationOverlay(0)
            provider.toggleEnhancedPage(0)
            assertNotNull(provider.getEnhancedPage(0))
            assertNull(provider.getTranslationOverlay(0))
            provider.removeCache(0)
            assertFalse(provider.hasEnhancedPage(0))
            assertTrue(enhanced.isRecycled)
            assertTrue(provider.hasTranslatedPage(0))
            assertNull(provider.getSuperResolutionInput(0))
        } finally { provider.stop() }
        assertTrue(overlay.isRecycled)
    }

    @Test fun workerCopyOutlivesSourceEvictionAndCannotRecycleReaderSource() {
        val provider = Provider()
        val source = image(11, 13, -0x10000)
        provider.notifyPageSucceed(0, source)
        val copy = checkNotNull(provider.getSuperResolutionInput(0))
        copy.eraseColor(-0xff0100)
        provider.removeCache(0)
        assertTrue(source.isRecycled)
        assertFalse(copy.isRecycled)
        assertEquals(-0xff0100, copy.getPixel(0, 0))
        copy.recycle()
        provider.stop()
    }

    @Test fun memoryAdmissionUsesLongArithmeticAndExactOutputBudget() {
        val budget = 128L * 1024 * 1024
        assertTrue(SuperResolution.accepts(2048, 4096, budget))
        assertFalse(SuperResolution.accepts(2048, 4097, budget))
        assertTrue(SuperResolution.accepts(1280, 1920))
        assertTrue(SuperResolution.accepts(1600, 2400))
        assertTrue(SuperResolution.accepts(2048, 3072))
        assertFalse(SuperResolution.accepts(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(SuperResolution.accepts(1, 0))
        assertTrue(SuperResolution.accepts(1, 1))
        val provider = Provider()
        provider.notifyPageSucceed(0, image(1500, 1500, -1))
        assertEquals(1, provider.getSuperResolutionStatus(0))
        assertEquals(-2, provider.getSuperResolutionStatus(0, 32L * 1024 * 1024))
        assertNull(provider.getSuperResolutionInput(0, 32L * 1024 * 1024))
        assertEquals(0, provider.getSuperResolutionStatus(1))
        provider.stop()
    }

    @Test fun enhancedResultAboveOldCacheLimitSurvivesAndIsReleasedBeforeAnotherPage() {
        val provider = Provider()
        val result = image(3000, 3000, -1) // 34.3 MiB: used to be evicted and recycled immediately.
        val overlay = image(17, 19, -0xffff01)
        provider.setSuperResolutionCacheLimit(64L * 1024 * 1024)
        provider.setEnhancedPage(0, result)
        provider.setTranslationOverlay(0, overlay)
        assertFalse(result.isRecycled)
        assertTrue(provider.hasEnhancedPage(0))
        provider.retainEnhancedPage(0)
        assertFalse(result.isRecycled)
        provider.retainEnhancedPage(1)
        assertTrue(result.isRecycled)
        assertFalse(provider.hasEnhancedPage(0))
        assertFalse(overlay.isRecycled)
        assertTrue(provider.hasTranslatedPage(0))
        provider.stop()
    }
}
