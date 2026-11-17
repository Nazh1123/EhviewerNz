package com.hippo.ehviewer.client;

import android.app.Application;
import com.alibaba.fastjson.JSON;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SubscriptionUpdateManager.RecentCheckSource;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionDisplaySnapshotTest {
    @Before public void setUp() { Settings.initialize(RuntimeEnvironment.getApplication()); }

    private RecentCheckSource page(boolean eh, int count, boolean exhausted, long boundary, Long... gids) {
        ListUrlBuilder builder = new ListUrlBuilder();
        BookmarkSubscriptionPlanner.Source plan = null;
        if (eh) builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
        else {
            QuickSearch bookmark = new QuickSearch();
            bookmark.id = 1L; bookmark.subscribed = true; bookmark.keyword = "saved";
            bookmark.mode = ListUrlBuilder.MODE_NORMAL; bookmark.category = EhUtils.NONE;
            bookmark.advanceSearch = bookmark.minRating = bookmark.pageFrom = bookmark.pageTo = -1;
            plan = BookmarkSubscriptionPlanner.planExact(List.of(bookmark)).get(0);
            builder.set(plan.createBuilder());
        }
        GalleryListParser.Result result = new GalleryListParser.Result();
        for (long gid : gids) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.gid = gid; gallery.token = "t"; gallery.title = "gallery" + gid;
            gallery.posted = "2026-11-16 00:00";
            result.galleryInfoList.add(gallery);
        }
        result.rawResultCount = count; result.rawTailGid = boundary;
        result.rawTailPosted = "2026-11-16 00:00";
        result.nextHref = exhausted ? null : "https://e-hentai.org/?next=" + boundary;
        return RecentCheckSource.fromDisplay(builder, plan, result, 0);
    }

    private List<Long> ids(List<GalleryInfo> galleries) { return galleries.stream().map(g -> g.gid).toList(); }

    @Test public void mergeUsesExistingBatchSizeDeduplicatesAndFreezesDivider() {
        var snapshot = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION,
                List.of(page(true, 4, false, 70, 100L, 90L, 80L, 70L),
                        page(false, 4, false, 60, 100L, 95L, 85L, 60L)), Set.of(95L, 85L), "old-context");
        assertEquals(List.of(100L, 95L, 90L, 85L), ids(snapshot.chronologicalGalleries));
        assertEquals(List.of(95L, 85L, 100L, 90L), ids(snapshot.galleries));
        assertEquals(2, snapshot.unreadCount);
        assertEquals(2, snapshot.cursors.get(0).position());
        assertEquals(3, snapshot.cursors.get(1).position());
        assertTrue(snapshot.hasMore);
    }

    @Test public void firstBatchStopsAtUnknownBoundaryRatherThanIncludingUnorderedResults() {
        var snapshot = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION,
                List.of(page(true, 25, false, 90, 100L, 90L),
                        page(false, 25, false, 50, 95L, 80L, 50L)), Set.of(), "context");
        assertEquals(List.of(100L, 95L, 90L), ids(snapshot.chronologicalGalleries));
        assertEquals(0, snapshot.unreadCount);
        assertTrue(snapshot.hasMore);
    }

    @Test public void anExhaustedSourceDoesNotBlockOtherSourcesAndBookmarkEntryExcludesEh() {
        var sources = List.of(page(true, 4, true, 100, 100L), page(false, 4, true, 80, 95L, 90L, 80L));
        assertEquals(List.of(100L, 95L, 90L, 80L), ids(SubscriptionDisplaySnapshot.merge(
                ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION, sources, Set.of(), "context").chronologicalGalleries));
        var bookmark = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION,
                sources, Set.of(95L, 90L, 80L), "context");
        assertEquals(List.of(95L, 90L, 80L), ids(bookmark.galleries));
        assertEquals(3, bookmark.unreadCount);
        assertFalse(bookmark.hasMore);
    }

    @Test public void serializationRetainsDividerUnreadFlagsLeftoversAndOriginalSearch() {
        var snapshot = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION,
                List.of(page(true, 2, false, 80, 100L, 80L), page(false, 2, false, 70, 95L, 70L)),
                Set.of(95L), "old-context");
        var restored = SubscriptionDisplaySnapshot.fromJson(JSON.parseObject(snapshot.toJson().toJSONString()));
        assertEquals(snapshot.completedTimeMillis, restored.completedTimeMillis);
        assertEquals("old-context", restored.contextKey);
        assertEquals(List.of(95L, 100L), ids(restored.galleries));
        assertEquals(List.of(100L, 95L), ids(restored.chronologicalGalleries));
        assertEquals(Set.of(95L), restored.unreadGids());
        assertEquals(1, restored.cursors.get(1).position());
        var page = restored.cursors.get(1).page();
        assertEquals(List.of(95L, 70L), ids(page.galleryInfoList));
        assertEquals("https://e-hentai.org/?next=70", page.nextHref);
        assertEquals("saved", page.continuationBuilder().getKeyword());
        assertEquals("saved", page.continuationPlan().getBookmarks().get(0).keyword);
    }

    @Test public void snapshotOwnsCopiesAndSupportsEmptySuccessfulRounds() {
        var source = page(true, 2, true, 90, 100L, 90L);
        var snapshot = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION,
                List.of(source), Set.of(100L, 90L), "context");
        source.galleryInfoList.get(0).title = "changed";
        var browsing = SubscriptionGallerySnapshot.copy(snapshot.galleries);
        browsing.get(0).gid = 999;
        assertEquals("gallery100", snapshot.galleries.get(0).title);
        assertEquals(List.of(100L, 90L), ids(snapshot.galleries));
        var empty = SubscriptionDisplaySnapshot.merge(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION,
                List.of(), Set.of(), "context");
        assertTrue(empty.galleries.isEmpty()); assertEquals(0, empty.unreadCount); assertFalse(empty.hasMore);
        assertTrue(SubscriptionDisplaySnapshot.fromJson(empty.toJson()).galleries.isEmpty());
    }

    @Test public void continuationKeepsNumericPageIndexEvenWithoutNextHref() {
        var original = page(false, 1, false, 100, 100L);
        var cursor = RecentCheckSource.fromCursor(original.continuationBuilder(), original.continuationPlan(),
                original.galleryInfoList, 3, null, "2026-11-16 00:00", 100, true, false);
        var snapshot = new SubscriptionDisplaySnapshot(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION,
                System.currentTimeMillis(), "context", 1, new ArrayList<>(cursor.galleryInfoList), Set.of(),
                List.of(new SubscriptionDisplaySnapshot.Cursor(cursor, 1)));
        var restored = SubscriptionDisplaySnapshot.fromJson(JSON.parseObject(snapshot.toJson().toJSONString()));
        assertEquals(3, restored.cursors.get(0).page().pageIndex);
        assertEquals(3, restored.cursors.get(0).page().continuationBuilder().getPageIndex());
        assertTrue(restored.hasMore);
    }
}
