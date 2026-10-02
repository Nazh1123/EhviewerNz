package com.hippo.ehviewer.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationWindowTest {
    @Test fun fullGalleryHasNoLookAheadLimitAndWrapsToTheUnfinishedPrefix() {
        val pages = TranslationWindow.fullPages(20, 100)
        assertEquals((20 until 100).toList() + (0 until 20).toList(), pages)
        assertEquals(100, pages.toSet().size)
    }

    @Test fun fullGalleryKeepsSmallTurnPrefixAndFallsBackOnlyWithoutAValidReaderPosition() {
        assertEquals(listOf(3, 4, 5, 6, 7, 0, 1, 2), TranslationWindow.fullPages(5, 8, 3))
        assertEquals(listOf(5, 6, 7, 0, 1, 2, 3, 4), TranslationWindow.fullPages(5, 8, 7))
        assertEquals((0 until 8).toList(), TranslationWindow.fullPages(-1, 8))
        assertEquals((0 until 8).toList(), TranslationWindow.fullPages(8, 8))
        assertTrue(TranslationWindow.fullPages(0, -1).isEmpty())
    }

    @Test fun zeroOnlyTranslatesCurrentPage() {
        assertEquals(listOf(8), TranslationWindow.pages(8, 100, 0))
    }
    @Test fun currentPageComesBeforeLookAheadPages() {
        assertEquals(listOf(8, 9, 10), TranslationWindow.pages(8, 100, 2))
    }
    @Test fun movingToDistantPageDropsPreviousPendingRange() {
        val old = TranslationWindow.pages(8, 100, 2)
        val next = TranslationWindow.pages(50, 100, 2)
        assertTrue(old.toSet().intersect(next.toSet()).isEmpty())
        assertEquals(50, next.first())
        assertEquals(listOf(49, 50, 51), TranslationWindow.pages(49, 100, 2))
    }
    @Test fun clampsAtLastPageAndHandlesUninitializedGallery() {
        assertEquals(listOf(98, 99), TranslationWindow.pages(98, 100, 10))
        assertTrue(TranslationWindow.pages(-1, 100, 2).isEmpty())
        assertTrue(TranslationWindow.pages(0, -1, 2).isEmpty())
        assertTrue(TranslationWindow.pages(0, 0, 2).isEmpty())
        assertTrue(TranslationWindow.pages(100, 100, 2).isEmpty())
    }
    @Test fun neverExceedsElevenPagesOrOverflows() {
        assertEquals(11, TranslationWindow.pages(8, 100, Int.MAX_VALUE).size)
        assertEquals(listOf(Int.MAX_VALUE - 2, Int.MAX_VALUE - 1),
            TranslationWindow.pages(Int.MAX_VALUE - 2, Int.MAX_VALUE, 10))
    }
    @Test fun lookAheadDoesNotInvalidateSameTranslation() {
        assertEquals(TranslationOptions(ahead = 0).cacheIdentity(), TranslationOptions(ahead = 10).cacheIdentity())
    }
}
