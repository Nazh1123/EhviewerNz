package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.*;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class GalleryRatingSortTest {
    private GalleryInfo gallery(long gid, float rating) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.rating = rating;
        return gallery;
    }

    @Test public void stableDescendingSortPreservesSourceAndPlacesUnknownLast() {
        List<GalleryInfo> source = new ArrayList<>(List.of(gallery(1, 4.5f), gallery(2, Float.NaN),
                gallery(3, 5), gallery(4, 4.5f), gallery(5, -1), gallery(6, 0),
                gallery(7, Float.POSITIVE_INFINITY), gallery(8, 6)));
        var snapshot = GalleryRatingSort.snapshot(source);
        source.get(0).rating = 1; // The worker must use its immutable score snapshot.
        assertEquals(List.of(3L, 1L, 4L, 6L, 2L, 5L, 7L, 8L), ids(GalleryRatingSort.sort(snapshot)));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ids(source));
    }

    @Test public void filteredProjectionUsesLiveObjectsAndRetainsNewRows() {
        GalleryInfo live = gallery(1, 4);
        List<GalleryInfo> result = GalleryRatingSort.project(List.of(live, gallery(3, 2), gallery(4, 1)),
                List.of(gallery(2, 5), gallery(3, 2), gallery(1, 4)));
        assertEquals(List.of(3L, 1L, 4L), ids(result));
        assertSame(live, result.get(1));
    }

    @Test public void calendarMonthsRespectLeapDayAndConvertToUtc() {
        assertEquals("2024-02-29 17:30", GalleryRatingSort.cutoff(
                ZonedDateTime.parse("2024-03-31T12:30:00-04:00[America/New_York]"), 1));
        assertTrue(GalleryRatingSort.reached("2024-02-29 17:30", "2024-02-29 17:30"));
        assertFalse(GalleryRatingSort.reached("2024-03-01 00:00", "2024-02-29 17:30"));
        assertFalse(GalleryRatingSort.validTime("2024-02-30 00:00"));
        assertFalse(GalleryRatingSort.reached(null, "2024-02-29 17:30"));
    }

    static List<Long> ids(List<GalleryInfo> list) { return list.stream().map(g -> g.gid).toList(); }
}
