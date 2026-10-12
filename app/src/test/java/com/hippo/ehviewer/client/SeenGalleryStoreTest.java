package com.hippo.ehviewer.client;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class SeenGalleryStoreTest {
    private static String query(String name) { return SubscriptionProgressStore.key("search", name); }
    private static String key(String name) { return "0/" + query(name); }

    @Test public void bookmarksSurviveEvictionAndRestartAndDeletedOrEditedQueriesBecomeOrdinary() {
        SeenGalleryStore store = new SeenGalleryStore("");
        store.record(key("bookmark"), 100, true, 1);
        store.record(key("a"), 200, false, 1);
        store.record(key("b"), 300, false, 1);
        store = new SeenGalleryStore(store.serialize());
        assertEquals(100, store.get(key("bookmark")));
        assertEquals(0, store.get(key("a")));
        assertEquals(300, store.get(key("b")));
        store.reconcile(Set.of(query("edited")), 1);
        assertEquals(100, store.get(key("bookmark")));
        assertEquals(0, store.get(key("b")));
        store.record(key("edited"), 400, true, 1);
        store.record(key("new"), 500, false, 1);
        assertEquals(0, store.get(key("bookmark")));
        assertEquals(400, store.get(key("edited")));
    }

    @Test public void addingBookmarkPromotesSearchAndProtectsBothSites() {
        SeenGalleryStore store = new SeenGalleryStore("");
        store.record(key("a"), 100, false, 3);
        store.record("1/" + query("a"), 200, false, 3);
        store.reconcile(Set.of(query("a")), 0);
        assertEquals(100, store.get(key("a")));
        assertEquals(200, store.get("1/" + query("a")));
        store.reconcile(Set.of(), 1);
        assertEquals(0, store.get(key("a")));
        assertEquals(200, store.get("1/" + query("a")));
    }

    @Test public void recentSearchIsRetainedAndOlderPagesCannotRegressTheMaximum() {
        SeenGalleryStore store = new SeenGalleryStore("");
        store.record(key("a"), 100, false, 2);
        store.record(key("b"), 200, false, 2);
        assertEquals(100, store.get(key("a")));
        store.record(key("c"), 300, false, 2);
        assertEquals(0, store.get(key("b")));
        store.record(key("a"), 50, false, 2);
        assertEquals(100, new SeenGalleryStore(store.serialize()).get(key("a")));
        store.record(key("a"), 0, false, 2);
        assertEquals(100, store.get(key("a")));
    }

    @Test public void limitUsesHalfHistoryAndNeverExceeds500AndShrinkTrimsImmediately() {
        assertEquals(50, SeenGalleryStore.limit(100));
        assertEquals(50, SeenGalleryStore.limit(101));
        assertEquals(500, SeenGalleryStore.limit(1000));
        assertEquals(500, SeenGalleryStore.limit(10000));
        SeenGalleryStore store = new SeenGalleryStore("broken\nS:" + key("bad") + ":oops\n");
        for (int i = 0; i < 600; i++) store.record(key("q" + i), i + 1, false, 500);
        assertEquals(500, store.serialize().lines().count());
        store.reconcile(Set.of(), 50);
        assertEquals(50, store.serialize().lines().count());
        assertEquals(0, store.get(key("q549")));
        assertEquals(600, store.get(key("q599")));
    }
}
