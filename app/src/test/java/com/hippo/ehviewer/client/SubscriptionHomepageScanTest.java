package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SubscriptionHomepageScanTest {
    static GalleryInfo gallery(long gid, int minute) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.posted = String.format(java.util.Locale.US, "2026-10-23 00:%02d", minute);
        return gallery;
    }
    static List<GalleryInfo> page(long start, int count, int minute) {
        List<GalleryInfo> page = new ArrayList<>();
        for (int i = 0; i < count; i++) page.add(gallery(start + i, minute));
        return page;
    }
    private String seed() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan("");
        List<GalleryInfo> page = page(100, 25, 20);
        page.set(0, gallery(100, 30));
        assertTrue(scan.shouldContinue(page, true));
        assertEquals("", scan.checkpoint());
        assertFalse(scan.shouldContinue(page(500, 25, 19), true));
        return scan.checkpoint();
    }
    @Test public void firstPassSeedsWithoutMarkingHistoryUnreadAndRoundTrips() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        assertFalse(scan.isNew(gallery(100, 30)));
        assertTrue(scan.isNew(gallery(1, 31))); // GID order is irrelevant.
        assertFalse(scan.isNew(gallery(2, 19))); // Older than the previous scanned window.
        assertTrue(scan.isNew(gallery(3, 20))); // Reordered within the overlap.
    }
    @Test public void exactlyFifteenAfterAnchorStopsAfterFilteringWholePage() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        List<GalleryInfo> page = page(200, 9, 31);
        page.add(gallery(100, 30));
        page.addAll(page(300, 15, 25));
        assertTrue(scan.isNew(page.get(24)));
        assertFalse(scan.shouldContinue(page, true));
    }
    @Test public void fourteenAfterAnchorLoadsExactlyOneWholeExtraPage() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        List<GalleryInfo> page = page(200, 10, 31);
        page.add(gallery(100, 30));
        page.addAll(page(300, 14, 25));
        assertTrue(scan.shouldContinue(page, true));
        assertTrue(scan.isNew(gallery(424, 24)));
        assertFalse(scan.shouldContinue(page(400, 25, 24), true));
    }
    @Test public void scansSeveralPagesUntilActualAnchorAndIgnoresOlderOutlier() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        List<GalleryInfo> first = page(200, 25, 31);
        first.set(2, gallery(202, 10));
        assertTrue(scan.shouldContinue(first, true));
        assertTrue(scan.shouldContinue(page(300, 25, 31), true));
        List<GalleryInfo> last = page(400, 25, 29);
        last.set(2, gallery(100, 30));
        assertFalse(scan.shouldContinue(last, true));
    }
    @Test public void deletedAnchorRequiresTwoConsecutiveOldPagesAndStillAppliesMargin() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        assertTrue(scan.shouldContinue(page(200, 25, 31), true));
        assertTrue(scan.shouldContinue(page(300, 25, 29), true));
        assertFalse(scan.shouldContinue(page(400, 25, 28), true));
        scan = new SubscriptionHomepageScan(seed());
        assertTrue(scan.shouldContinue(page(300, 10, 29), true));
        assertTrue(scan.shouldContinue(page(400, 10, 28), true));
        assertFalse(scan.shouldContinue(page(500, 10, 27), true));
    }
    @Test public void exhaustionAndEmptyPagesStopWithoutErasingAnchor() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        assertFalse(scan.shouldContinue(List.of(), true));
        assertEquals("", scan.checkpoint());
        assertFalse(new SubscriptionHomepageScan(seed()).shouldContinue(page(200, 25, 31), false));
    }
    @Test(expected = IllegalStateException.class) public void repeatedRawPageFailsInsteadOfAdvancingCheckpoint() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        List<GalleryInfo> page = page(200, 25, 31);
        assertTrue(scan.shouldContinue(page, true));
        scan.shouldContinue(page, true);
    }
    @Test public void corruptStateReinitializesWithoutUnreadHistory() {
        for (String invalid : new String[]{"", "broken", "{}", "{\"version\":1,\"head\":100,\"minute\":20,\"floor\":10,\"ids\":[1]}"}) {
            SubscriptionHomepageScan scan = new SubscriptionHomepageScan(invalid);
            assertFalse(scan.isNew(gallery(200, 31)));
            assertTrue(scan.shouldContinue(page(200, 25, 31), true));
            assertFalse(scan.shouldContinue(page(300, 25, 30), true));
        }
    }

    @Test public void firstPassCompletesFloorMinuteBeforeCommittingAndDoesNotMoveItsFloor() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan("");
        assertTrue(scan.shouldContinue(page(100, 25, 30), true));
        assertTrue(scan.shouldContinue(page(200, 25, 30), true));
        assertEquals("", scan.checkpoint());
        List<GalleryInfo> last = page(300, 2, 30);
        last.addAll(page(400, 23, 29));
        assertFalse(scan.shouldContinue(last, true));
        SubscriptionHomepageScan next = new SubscriptionHomepageScan(scan.checkpoint());
        assertFalse(next.isNew(gallery(301, 30))); // Old same-minute spill beyond the original page.
        assertFalse(next.isNew(gallery(999, 29))); // Lookahead does not extend the retained window.
        assertTrue(next.isNew(gallery(999, 30)));  // A genuinely unseen ID in the complete minute.
    }

    @Test public void shorterRepeatRetainsPreviouslyCompletedMinuteWithoutAnotherLookahead() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan("");
        assertTrue(scan.shouldContinue(page(100, 25, 30), true));
        List<GalleryInfo> spill = page(200, 5, 30);
        spill.addAll(page(300, 20, 29));
        assertFalse(scan.shouldContinue(spill, true));
        SubscriptionHomepageScan repeat = new SubscriptionHomepageScan(scan.checkpoint());
        assertFalse(repeat.shouldContinue(page(100, 25, 30), true));
        assertFalse(new SubscriptionHomepageScan(repeat.checkpoint()).isNew(gallery(204, 30)));
    }

    @Test public void singleReorderedOldPageCannotStopBeforeTheActualAnchor() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        assertTrue(scan.shouldContinue(page(400, 16, 29), true));
        List<GalleryInfo> realAnchor = page(600, 25, 31);
        realAnchor.set(5, gallery(100, 30));
        assertFalse(scan.shouldContinue(realAnchor, true));
    }

    @Test public void newerPageResetsDeletedAnchorConfirmation() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        assertTrue(scan.shouldContinue(page(300, 25, 29), true));
        assertTrue(scan.shouldContinue(page(400, 25, 31), true));
        assertTrue(scan.shouldContinue(page(500, 25, 29), true));
        assertFalse(scan.shouldContinue(page(600, 25, 28), true));
    }

    @Test public void legacyPartialFloorIsNotTrustedAndUpgradesOnlyAfterClosingANewMinute() {
        long newest = GallerySeenState.postedMinute("2026-10-23 00:30");
        long oldest = GallerySeenState.postedMinute("2026-10-23 00:20");
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan("{\"version\":1,\"head\":100,\"minute\":"
                + newest + ",\"floor\":" + oldest + ",\"ids\":[100]}");
        assertFalse(scan.isNew(gallery(200, 20)));
        assertTrue(scan.isNew(gallery(200, 30)));
        assertTrue(scan.shouldContinue(page(100, 25, 30), true));
        assertFalse(scan.shouldContinue(page(300, 25, 29), true));
        assertTrue(scan.checkpoint().contains("\"version\":2"));
        assertTrue(new SubscriptionHomepageScan(scan.checkpoint()).isNew(gallery(999, 30)));
    }

    @Test public void currentPassDuplicatesDoNotNeedMetadataOrCountTowardsNewGalleries() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan(seed());
        assertTrue(scan.isNew(gallery(200, 31)));
        assertTrue(scan.shouldContinue(page(200, 25, 31), true));
        assertFalse(scan.isNew(gallery(200, 31)));
    }

    @Test public void duplicateWithMissingTimestampCannotInvalidateTheSavedAnchor() {
        SubscriptionHomepageScan scan = new SubscriptionHomepageScan("");
        assertTrue(scan.shouldContinue(page(100, 25, 30), true));
        GalleryInfo duplicate = gallery(100, 30);
        duplicate.posted = null;
        List<GalleryInfo> closure = new ArrayList<>(List.of(duplicate));
        closure.addAll(page(300, 15, 29));
        assertFalse(scan.shouldContinue(closure, true));
        SubscriptionHomepageScan restored = new SubscriptionHomepageScan(scan.checkpoint());
        assertFalse(restored.isNew(gallery(100, 30)));
        assertTrue(restored.isNew(gallery(999, 31)));
    }
}
