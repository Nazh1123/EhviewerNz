package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.parser.ParserUtils;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class GallerySeenStateTest {
    private static final long ORIGIN = 1767225600000L; // 2026-01-01 UTC
    public static GalleryInfo gallery(long gid, long minute) {
        GalleryInfo result = new GalleryInfo();
        result.gid = gid;
        result.posted = ParserUtils.formatPostedDate(ORIGIN + minute * 60000);
        return result;
    }
    public static GallerySeenState state(long minute) {
        return GallerySeenState.empty().observe(List.of(gallery(minute, minute)));
    }
    public static long marker(GallerySeenState state) {
        return state.minute() == 0 ? 0 : state.minute() - ORIGIN / 60000;
    }

    @Test public void latePublicationUsesTimeEvenWhenGidIsSmaller() {
        GallerySeenState previous = GallerySeenState.empty().observe(List.of(gallery(200, 10)));
        assertTrue(previous.isNew(gallery(100, 11)));
        assertFalse(previous.isNew(gallery(300, 9)));
    }
    @Test public void equalMinuteUsesMembershipInsteadOfMaximumGid() {
        GallerySeenState previous = state(200);
        assertTrue(previous.isNew(gallery(100, 200)));
        assertFalse(previous.isNew(gallery(200, 200)));
        assertFalse(previous.reachedOlderThanWindow(gallery(1, 200).posted));
        assertTrue(previous.reachedOlderThanWindow(gallery(1, 199).posted));
    }
    @Test public void retainsAllBoundaryIdsOrSixRecentIdsWhicheverIsLarger() {
        List<GalleryInfo> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) rows.add(gallery(i, 100));
        GallerySeenState previous = GallerySeenState.empty().observe(rows);
        assertEquals(12, previous.retainedCount());
        assertTrue(previous.isNew(gallery(99, 100)));
        previous = previous.observe(List.of(gallery(20, 101)));
        assertEquals(6, previous.retainedCount());
        assertEquals(GallerySeenState.postedMinute(gallery(1, 100).posted), previous.oldestMinute());
        // Once the old minute is partially evicted it is outside reliable overlap coverage.
        assertFalse(previous.isNew(gallery(99, 100)));
        assertFalse(GallerySeenState.parse(previous.serialize()).isNew(gallery(12, 100)));
        assertFalse(previous.isNew(gallery(99, 99)));
    }
    @Test public void recentWindowIncludesSixDifferentMinutesAndSurvivesRestart() {
        List<GalleryInfo> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) rows.add(gallery(100 - i, i));
        GallerySeenState previous = GallerySeenState.parse(GallerySeenState.empty().observe(rows).serialize());
        assertEquals(6, previous.retainedCount());
        assertEquals(10, marker(previous));
        assertEquals(state(5).minute(), previous.oldestMinute());
        assertFalse(previous.isNew(gallery(95, 5)));
        assertTrue(previous.isNew(gallery(1, 5)));
        assertFalse(previous.isNew(gallery(1, 4)));
    }
    @Test public void olderPageCannotRegressMaximumAndMergePreservesBoundaryIds() {
        GallerySeenState previous = state(200).merge(state(100));
        assertEquals(200, marker(previous));
        previous = previous.merge(GallerySeenState.empty().observe(List.of(gallery(99, 200))));
        assertFalse(previous.isNew(gallery(99, 200)));
        assertFalse(previous.isNew(gallery(200, 200)));
    }
    @Test public void missingDatesStayNewRatherThanSilentlyBeingRead() {
        GalleryInfo unknown = new GalleryInfo();
        unknown.gid = 7;
        assertTrue(state(100).isNew(unknown));
        assertFalse(state(100).reachedOlderThanWindow("invalid"));
        assertEquals(0, GallerySeenState.postedMinute("2026-02-30 10:00"));
    }
    @Test public void emptySuccessSeedsButLegacyAndDamagedRecordsDoNot() {
        assertTrue(GallerySeenState.parse(GallerySeenState.empty().observe(List.of()).serialize()).initialized());
        assertFalse(GallerySeenState.parse("2000000").initialized());
        assertFalse(GallerySeenState.parse("2;10;0;2@-1,").initialized());
        assertFalse(GallerySeenState.parse("2;10;0;2@11,").initialized());
    }
    @Test public void utcPublicationFormattingDoesNotFollowDeviceTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertEquals("2026-01-01 00:00", ParserUtils.formatPostedDate(ORIGIN));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals("2026-01-01 00:00", ParserUtils.formatPostedDate(ORIGIN));
        } finally { TimeZone.setDefault(original); }
    }
}
