package com.hippo.ehviewer.download;

import org.junit.Test;
import static org.junit.Assert.*;

public class GalleryPageRangeTest {
    @Test public void printStyleInputIsFrozenSortedMergedAndDeduplicated() {
        GalleryPageRange range = GalleryPageRange.parse(" 25-，2,1,6-10,8, -2 ", 30);
        assertEquals("1-2,6-10,25-30", range.toString());
        assertEquals(13, range.size());
        assertEquals(30, range.totalPages);
        assertTrue(range.contains(0)); assertFalse(range.contains(2)); assertTrue(range.contains(29));
        assertArrayEquals(range.pages(), GalleryPageRange.parse(range.toString(), 30).pages());
    }
    @Test public void malformedAndOutOfBoundsRangesAreRejected() {
        for (String value : new String[]{"", " ", "0", "-", "5-2", "31", "1,", "1,,2", "1-31",
                "31-", "1.5", "1-2-3", "a", "999999999999999999", "+1"}) {
            assertThrows(value, IllegalArgumentException.class, () -> GalleryPageRange.parse(value, 30));
        }
        assertThrows(IllegalArgumentException.class, () -> GalleryPageRange.parse("1", 0));
    }
    @Test public void sourceMappingUsesExactImmediateNextThenPreviousWithFirstPageFallback() {
        int[] pages = {9, 10, 19, 29};
        assertEquals(0, GalleryPageRange.positionForSourcePage(9, pages));
        assertEquals(1, GalleryPageRange.positionForSourcePage(10, pages));
        assertEquals(2, GalleryPageRange.positionForSourcePage(18, pages));
        assertEquals(1, GalleryPageRange.positionForSourcePage(17, pages));
        assertEquals(3, GalleryPageRange.positionForSourcePage(30, pages));
        assertEquals(0, GalleryPageRange.positionForSourcePage(4, pages));
        assertEquals(-1, GalleryPageRange.positionForSourcePage(0, new int[0]));
    }
}
