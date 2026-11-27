package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import com.hippo.ehviewer.translation.engine.PreparedPage
import com.hippo.ehviewer.translation.engine.Pt
import com.hippo.ehviewer.translation.engine.TextLine
import com.hippo.ehviewer.translation.engine.TextRegion
import com.hippo.ehviewer.translation.engine.TranslationResume
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

    @Test fun fullRetranslationDiscardsTheCheckpointUnderTheResolvedKey() {
        val cache = PreparedPageCache(limit = 0)
        val quad = listOf(Pt(1f, 1f), Pt(5f, 1f), Pt(5f, 5f), Pt(1f, 5f))
        val line = TextLine(quad, 1f).apply { text = "原文" }
        val identity = "resolved-ja-translation"
        val checkpoint = TranslationResume(listOf("|$quad|原文"), mapOf(0 to "旧译"))
        fun seed(key: String) {
            val prepared = PreparedPage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
                listOf(TextRegion(listOf(line), "h")), 1, 0, 0)
            assertTrue(prepared.restoreTranslations(identity, checkpoint))
            cache.put(key, prepared, identity)
            assertTrue(prepared.mask.isRecycled)
        }
        seed("control")
        assertEquals(checkpoint, cache.takeCheckpoint("control", identity))

        seed("resolved-ja")
        cache.invalidate("unresolved-auto")
        assertNull(cache.takeCheckpoint("resolved-ja", identity, retranslate = true))
        assertNull(cache.takeCheckpoint("resolved-ja", identity))
    }
}
