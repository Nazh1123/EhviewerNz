package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import com.hippo.lib.glview.image.ImageWrapper
import com.hippo.lib.glview.view.GLRoot
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TranslationImageOwnershipTest {
    private class Provider : GalleryProvider() {
        var requests = 0
        override fun size() = 1
        override fun getError() = ""
        override fun onRequest(index: Int) { requests++ }
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
    }

    @Test fun stoppingBackgroundProviderDoesNotRecycleTheReadersSharedImage() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val image = requireNotNull(Image.create(bitmap))
        val reader = Provider()
        val background = Provider()
        reader.start()
        background.start()
        reader.notifyPageSucceed(0, image)
        background.notifyPageSucceed(0, image)
        background.stop()
        assertFalse("Background cache disposal recycled the reader's image", image.isRecycled)
        reader.request(0)
        assertEquals(0, reader.requests)
        reader.stop()
        assertTrue(bitmap.isRecycled)
    }

    @Test fun differentWrappersAndDrawableOwnersReleaseOnlyTheirOwnReferences() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val image = requireNotNull(Image.create(bitmap))
        val first = ImageWrapper(image)
        val second = ImageWrapper(image)
        assertTrue(first.obtain())
        assertTrue(second.obtain())
        assertTrue(first.obtain())
        image.getDrawable()
        first.release()
        first.release()
        first.release() // A duplicate release must not consume another wrapper's lease.
        assertFalse(image.isRecycled)
        second.release()
        assertFalse(image.isRecycled)
        image.release()
        assertTrue(bitmap.isRecycled)
        assertFalse(second.obtain())
    }

    @Test fun queuedSuccessSurvivesCacheEvictionAndReleasesItsLeaseAfterDelivery() {
        val provider = Provider()
        val queue = attach(provider) { image -> assertFalse(image.isImageRecycled) }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        provider.notifyPageSucceed(0, requireNotNull(Image.create(bitmap)))
        provider.removeCache(0)
        assertFalse(bitmap.isRecycled)
        assertEquals(1, queue.size)
        queue.removeFirst().onGLIdle(null, false)
        assertTrue(bitmap.isRecycled)
        assertTrue(queue.isEmpty())
    }

    @Test fun recycledCacheEntryReloadsSourceInsteadOfQueuingAnotherSuccess() {
        val provider = Provider()
        val image = requireNotNull(Image.create(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)))
        provider.notifyPageSucceed(0, image)
        val queue = attach(provider) { fail("Recycled cache entry was delivered again") }
        image.recycle() // Model a forced disposal or a stale entry from an older owner.
        provider.request(0)
        assertEquals(1, provider.requests)
        assertTrue("The GL notification loop was not broken", queue.isEmpty())
    }

    @Test fun failingGlListenerStillReleasesTheQueuedImage() {
        val provider = Provider()
        val queue = attach(provider) { throw IllegalStateException("Failed display") }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        provider.notifyPageSucceed(0, requireNotNull(Image.create(bitmap)))
        provider.removeCache(0)
        assertTrue(runCatching { queue.removeFirst().onGLIdle(null, false) }.exceptionOrNull() is IllegalStateException)
        assertTrue(bitmap.isRecycled)
    }

    private fun attach(provider: Provider, onImage: (ImageWrapper) -> Unit): ArrayDeque<GLRoot.OnGLIdleListener> {
        val queue = ArrayDeque<GLRoot.OnGLIdleListener>()
        val root = Proxy.newProxyInstance(GLRoot::class.java.classLoader, arrayOf(GLRoot::class.java)) { _, method, args ->
            if (method.name == "addOnGLIdleListener") queue.addLast(args!![0] as GLRoot.OnGLIdleListener)
            null
        } as GLRoot
        provider.setGLRoot(root)
        provider.setListener(object : GalleryProvider.Listener {
            override fun onDataChanged() = Unit
            override fun onDataChanged(index: Int) = Unit
            override fun onPageWait(index: Int) = Unit
            override fun onPagePercent(index: Int, percent: Float) = Unit
            override fun onPageSucceed(index: Int, image: ImageWrapper) = onImage(image)
            override fun onPageFailed(index: Int, error: String?) = Unit
        })
        return queue
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
