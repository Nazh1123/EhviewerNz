package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.os.Looper;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.SubscriptionSearchContext;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        shadows = GalleryRatingSortControllerTest.ContextShadow.class)
public class GalleryRatingSortControllerTest {
    private FakeHost host;
    private GalleryRatingSortController controller;
    private List<EhRequest> requests;

    @Before public void setUp() {
        host = new FakeHost();
        requests = new ArrayList<>();
        controller = new GalleryRatingSortController(host, requests::add, Runnable::run);
        host.rows.add(gallery(1, 2));
    }

    private GalleryInfo gallery(long gid, float rating) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.rating = rating;
        return gallery;
    }

    private void page(String cursor) {
        host.pages++;
        GalleryListParser.Result result = new GalleryListParser.Result();
        result.pages = -1;
        result.nextHref = cursor;
        controller.pageLoaded(result);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void loadsSequentiallyToTotalIncludingExistingPages() {
        controller.start(3, null);
        assertEquals(1, host.nextRequests);
        host.rows.add(gallery(2, 5));
        page("https://example.com/?next=2");
        assertEquals(2, host.nextRequests);
        page("https://example.com/?next=1");
        assertFalse(controller.isActive());
        assertEquals(2, host.nextRequests);
        assertEquals(List.of(2L, 1L), GalleryRatingSortTest.ids(host.order));
        assertEquals(List.of(1L, 2L), GalleryRatingSortTest.ids(host.rows));
    }

    @Test public void mergedSubscriptionSentinelDoesNotLookLikeARepeatedWebsiteCursor() {
        controller.start(4, null);
        page("bookmark-subscription-next");
        page("bookmark-subscription-next");
        page("bookmark-subscription-next");
        assertEquals(3, host.nextRequests);
        assertFalse(host.incomplete);
    }

    @Test public void repeatedWebsiteCursorStopsInsteadOfLooping() {
        controller.start(10, null);
        page("https://example.com/?next=2");
        page("https://example.com/?next=2");
        assertEquals(2, host.nextRequests);
        assertTrue(host.incomplete);
    }

    @Test public void monthBoundaryUsesRawPageTimeEvenWhenNoVisibleRowsAreAdded() {
        host.rows.get(0).posted = "1970-01-01 00:00";
        controller.start(0, "2026-10-05 00:00");
        assertEquals(1, host.nextRequests);
        host.oldest = "2026-10-04 23:59";
        page("https://example.com/?next=2");
        assertEquals(1, host.nextRequests);
        assertEquals(1, host.order.size());
        assertFalse(host.incomplete);
    }

    @Test public void stopsAtPageLimitAndAtUnparseableDates() {
        host.pages = GalleryRatingSort.MAX_PAGES;
        controller.start(0, "2026-01-01 00:00");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, host.nextRequests);
        assertTrue(host.incomplete);
        host.oldest = "bad date";
        host.pages = 1;
        controller.start(0, "2026-01-01 00:00");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, host.nextRequests);
        assertTrue(host.incomplete);
    }

    @Test public void cancelKeepsCompletedPagesAndRejectsLatePageCallbacks() {
        controller.start(4, null);
        host.rows.add(gallery(2, 4));
        page("https://example.com/?next=2");
        controller.stopAndSort();
        page("https://example.com/?next=1");
        assertEquals(1, host.cancelRequests);
        assertEquals(2, host.nextRequests);
        assertEquals(List.of(2L, 1L), GalleryRatingSortTest.ids(host.order));
        assertFalse(controller.isActive());
    }

    @SuppressWarnings("unchecked") private void completeMetadata(EhRequest request) {
        List<GalleryInfo> copies = (List<GalleryInfo>) request.getArgs()[0];
        for (GalleryInfo copy : copies) { copy.firstGid = copy.gid; copy.rating = copy.gid / 11f; }
        ((EhClient.Callback<List<GalleryInfo>>) request.getCallback()).onSuccess(copies);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void exactRatingsAreBatchedInParallelAndReusedWithoutChangingOtherFields() {
        host.rows.clear();
        for (int i = 1; i <= 51; i++) {
            GalleryInfo gallery = gallery(i, 0);
            gallery.token = "token";
            gallery.posted = "2026-11-05 00:00";
            host.rows.add(gallery);
        }
        controller.start(0, null);
        assertEquals(3, requests.size());
        for (int i : new int[]{2, 0, 1}) {
            EhRequest request = requests.get(i);
            assertTrue(request.isBulk());
            assertEquals(i < 2 ? 25 : 1, ((List<?>) request.getArgs()[0]).size());
            completeMetadata(request);
        }
        assertEquals(51L, host.order.get(0).gid);
        assertEquals("2026-11-05 00:00", host.rows.get(0).posted);
        controller.start(0, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(3, requests.size());
    }

    @Test public void cancelledMetadataCannotMutateRowsOrCompleteAnOldOperation() {
        host.rows.get(0).token = "token";
        controller.start(0, null);
        EhRequest previous = requests.get(0);
        controller.cancel();
        assertTrue(previous.isCancelled());
        completeMetadata(previous);
        assertEquals(2f, host.rows.get(0).rating, 0);
        assertNull(host.order);
    }

    @Test public void metadataFailureStillSortsAndKeepsEveryLoadedGallery() {
        host.rows.get(0).token = "token";
        controller.start(0, null);
        requests.get(0).getCallback().onFailure(new java.io.IOException("offline"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(host.incomplete);
        assertEquals(1, host.order.size());
        assertFalse(host.loading);
    }

    @Test public void cancelledWorkerCannotPublishAnOrderForThePreviousSearch() {
        List<Runnable> workers = new ArrayList<>();
        controller = new GalleryRatingSortController(host, requests::add, workers::add);
        controller.start(0, null);
        controller.cancel();
        host.rows.clear();
        host.rows.add(gallery(2, 5));
        controller.start(0, null);
        workers.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(host.order);
        workers.get(1).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(2L), GalleryRatingSortTest.ids(host.order));
    }

    @Test public void reusesExactApiScoresFromPageLoadingWithoutAnotherGdataRequest() {
        GalleryInfo gallery = host.rows.get(0);
        gallery.token = "token";
        gallery.rating = 4.5f;
        gallery.apiRating = 4.73f;
        controller.start(0, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(requests.isEmpty());
        assertEquals(4.73f, host.order.get(0).rating, 0);
    }

    @Test public void metadataUsesAtMostEightBatchesAndCancellationStopsEveryRequest() {
        host.rows.clear();
        addRows(1, 250);
        controller.start(0, null);
        assertEquals(8, requests.size());
        completeMetadata(requests.get(0));
        assertEquals(9, requests.size());
        controller.stopAndSort();
        for (int i = 1; i < requests.size(); i++) assertTrue(requests.get(i).isCancelled());
        completeMetadata(requests.get(1));
        assertEquals(250, host.order.size());
        assertEquals(9, requests.size());
    }

    @Test public void rawLookaheadHonorsPageAndMonthBoundaries() {
        controller.start(2, null);
        GalleryListParser.Result raw = new GalleryListParser.Result();
        raw.rawTailPosted = "2026-10-01 00:00";
        assertFalse(controller.shouldPrefetch(raw));
        controller.start(3, null);
        assertTrue(controller.shouldPrefetch(raw));
        controller.start(0, "2026-10-05 00:00");
        assertFalse(controller.shouldPrefetch(raw));
        raw.rawTailPosted = "2026-10-06 00:00";
        assertTrue(controller.shouldPrefetch(raw));
    }

    @Test public void completedPartialBatchCannotBeAppendedBelowAnAlreadySortedOrder() {
        List<Runnable> workers = new ArrayList<>();
        controller = new GalleryRatingSortController(host, requests::add, workers::add);
        controller.start(0, null);
        host.onStopped = () -> host.rows.add(gallery(2, 5));
        workers.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(host.order);
        workers.get(1).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(2L, 1L), GalleryRatingSortTest.ids(host.order));
    }

    private void addRows(int first, int last) {
        for (int i = first; i <= last; i++) {
            GalleryInfo gallery = gallery(i, 2);
            gallery.token = "token";
            host.rows.add(gallery);
        }
    }

    private static class FakeHost implements GalleryRatingSortController.Host {
        final List<GalleryInfo> rows = new ArrayList<>();
        int pages = 1;
        int nextRequests;
        int cancelRequests;
        String oldest = "2026-11-01 00:00";
        boolean loading;
        boolean incomplete;
        List<GalleryInfo> order;
        Runnable onStopped;
        @Override public List<GalleryInfo> galleries() { return rows; }
        @Override public int loadedPages() { return pages; }
        @Override public String oldestPosted() { return oldest; }
        @Override public boolean hasNextPage() { return true; }
        @Override public boolean requestNextPage() { nextRequests++; return true; }
        @Override public void cancelPageRequest() { cancelRequests++; }
        @Override public void loadingChanged(boolean value) {
            loading = value;
            if (!value && onStopped != null) { Runnable action = onStopped; onStopped = null; action.run(); }
        }
        @Override public void progressChanged() { }
        @Override public void sorted(List<GalleryInfo> result, boolean partial) { order = result; incomplete = partial; }
    }

    @Implements(SubscriptionSearchContext.class) public static class ContextShadow {
        @Implementation protected static String key() { return "rating-sort-test"; }
    }
}
