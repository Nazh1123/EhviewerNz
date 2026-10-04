package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import com.hippo.ehviewer.translation.engine.PreparedPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PreparedPageCacheTest {
    private fun page() = PreparedPage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), emptyList(), 0, 0, 0)

    @Test fun takeTransfersOwnershipAndReinsertionRefreshesRecency() {
        val cache = PreparedPageCache(maxPages = 2)
        val first = page(); val second = page(); val third = page()
        cache.put("one", first); cache.put("two", second)
        assertSame(first, cache.take("one"))
        cache.put("one", first); cache.put("three", third)
        assertTrue(second.mask.isRecycled)
        assertFalse(first.mask.isRecycled)
        val borrowed = cache.take("one")!!
        cache.clear()
        assertTrue(third.mask.isRecycled)
        assertFalse(borrowed.mask.isRecycled)
        borrowed.close()
    }

    @Test fun byteLimitAndInvalidationReleaseOwnedBitmaps() {
        val first = page()
        val cache = PreparedPageCache(limit = first.byteCount)
        cache.put("one", first)
        val second = page()
        cache.put("two", second)
        assertTrue(first.mask.isRecycled)
        cache.invalidate("two")
        assertTrue(second.mask.isRecycled)
        assertNull(cache.take("two"))
        val oversized = page()
        PreparedPageCache(limit = 0).put("large", oversized)
        assertTrue(oversized.mask.isRecycled)
    }
}
