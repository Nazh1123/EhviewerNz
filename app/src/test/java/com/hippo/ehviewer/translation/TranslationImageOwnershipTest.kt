package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TranslationImageOwnershipTest {
    private class Provider : GalleryProvider() {
        override fun size() = 1
        override fun getError() = ""
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
    }

    @Test fun clearingTranslationReleasesOnlyTranslatedBitmap() {
        val original = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val translated = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val provider = Provider()
        provider.start()
        provider.notifyPageSucceed(0, requireNotNull(Image.create(original)))
        provider.setTranslationOverlay(0, requireNotNull(Image.create(translated)))
        provider.setShowTranslations(true)
        provider.clearTranslatedPages()
        assertTrue(translated.isRecycled)
        assertFalse(original.isRecycled)
        assertFalse(provider.hasTranslatedPage(0))
        provider.stop()
        assertTrue(original.isRecycled)
    }

    @Test fun togglingDoesNotDestroyCacheAndStoppingReleasesBoth() {
        val original = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val translated = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val provider = Provider()
        provider.start()
        provider.notifyPageSucceed(0, requireNotNull(Image.create(original)))
        provider.setTranslationOverlay(0, requireNotNull(Image.create(translated)))
        provider.setShowTranslations(true)
        provider.setShowTranslations(false)
        assertTrue(provider.hasTranslatedPage(0))
        assertFalse(translated.isRecycled)
        assertFalse(original.isRecycled)
        provider.stop()
        assertTrue(translated.isRecycled)
        assertTrue(original.isRecycled)
    }
}
