package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.*;
import com.hippo.ehviewer.client.data.*;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE, shadows = {
        BookmarkSubscriptionConcurrencyTest.ClientShadow.class, BookmarkSubscriptionConcurrencyTest.ContextShadow.class,
        BookmarkSubscriptionConcurrencyTest.DbShadow.class})
public class BookmarkSubscriptionConcurrencyTest {
    private static final List<EhRequest> requests = new ArrayList<>();
    private final List<List<Long>> batches = new ArrayList<>();
    private BookmarkSubscriptionCoordinator coordinator;

    @Before public void setUp() {
        requests.clear();
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase(SearchUpdateStore.DATABASE);
        SearchUpdateStore.initialize(app);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                app.getSharedPreferences("concurrency-test", Context.MODE_PRIVATE));
        ReflectionHelpers.setStaticField(Settings.class, "sEhConfig", new EhConfig());
        coordinator = new BookmarkSubscriptionCoordinator(Shadow.newInstanceOf(EhClient.class),
                new BookmarkSubscriptionCoordinator.Listener() {
                    @Override public void onBookmarkSubscriptionBatch(int task, List<GalleryInfo> data,
                            boolean more, boolean refresh, boolean empty, boolean partial) {
                        assertFalse(partial);
                        batches.add(data.stream().map(g -> g.gid).toList());
                    }
                    @Override public void onBookmarkSubscriptionFailure(int task, Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    private List<QuickSearch> bookmarks(int count) {
        List<QuickSearch> searches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            QuickSearch q = new QuickSearch();
            q.id = (long) i + 1;
            q.subscribed = true;
            q.mode = ListUrlBuilder.MODE_NORMAL;
            q.category = EhUtils.NONE;
            q.advanceSearch = q.minRating = q.pageFrom = q.pageTo = -1;
            q.keyword = "keyword" + i;
            searches.add(q);
        }
        return searches;
    }

    @SuppressWarnings("unchecked") private void complete(int index, long gid, String next) {
        complete(index, gid > 0 ? List.of(gid) : List.of(), next);
    }

    @SuppressWarnings("unchecked") private void complete(int index, List<Long> gids, String next) {
        GalleryListParser.Result result = new GalleryListParser.Result();
        result.pages = -1;
        result.nextPage = -1;
        result.nextHref = next;
        for (long gid : gids) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.gid = gid;
            gallery.posted = "2026-11-01 00:00";
            result.galleryInfoList.add(gallery);
            result.rawTailPosted = gallery.posted;
            result.rawTailGid = gid;
            result.rawResultCount++;
        }
        ((EhClient.Callback<GalleryListParser.Result>) requests.get(index).getCallback()).onSuccess(result);
    }

    @Test public void bulkGlobalFeedStartsEightSourcesAndDisablesHiddenExtraPages() {
        coordinator.setBulk(true);
        coordinator.refresh(1, bookmarks(9), true, null);
        assertEquals(8, requests.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(Math.min(10, i + 8), requests.size());
            assertTrue(requests.get(i).isBulk());
            assertEquals(false, requests.get(i).getArgs()[5]);
            complete(i, 0, null);
        }
        assertFalse(coordinator.isLoading());
    }

    @Test public void cancellingAnEightSourceRoundKeepsEveryCursorAndRejectsLateResults() {
        coordinator.setBulk(true);
        coordinator.refresh(1, bookmarks(8), false, null);
        for (int i = 0; i < 8; i++) complete(i, 100 - i, "https://e-hentai.org/?next=" + i);
        int before = requests.size();
        coordinator.loadMore(2);
        assertEquals(before + 8, requests.size());
        List<EhRequest> pending = new ArrayList<>(requests.subList(before, requests.size()));
        coordinator.pauseLoading();
        for (EhRequest request : pending) assertTrue(request.isCancelled());
        int emitted = batches.size();
        for (int i = before; i < requests.size(); i++) complete(i, 999, null);
        assertEquals(emitted, batches.size());
        coordinator.loadMore(3);
        assertTrue(requests.size() > before + pending.size());
        assertEquals(pending.get(0).getArgs()[0], requests.get(before + pending.size()).getArgs()[0]);
    }

    @Test public void outOfOrderPrefetchResponsesPreserveUnconsumedRowsAndMergeOrder() {
        coordinator.setBulk(true);
        coordinator.refresh(1, bookmarks(2), false, null);
        complete(0, List.of(100L, 90L), "https://e-hentai.org/?next=90");
        complete(1, List.of(99L, 89L), "https://e-hentai.org/?next=89");
        assertEquals(List.of(List.of(100L, 99L)), batches);
        coordinator.loadMore(2);
        assertEquals(4, requests.size());
        complete(3, 79, null);
        complete(2, 80, null);
        assertEquals(List.of(List.of(100L, 99L), List.of(90L, 89L)), batches);
        coordinator.loadMore(3);
        assertEquals(List.of(List.of(100L, 99L), List.of(90L, 89L), List.of(80L, 79L)), batches);
    }

    @Implements(EhClient.class) public static class ClientShadow {
        @Implementation protected void execute(EhRequest request) { requests.add(request); }
    }
    @Implements(SubscriptionSearchContext.class) public static class ContextShadow {
        @Implementation protected static String key() { return "concurrency-test"; }
    }
    @Implements(EhDB.class) public static class DbShadow {
        @Implementation protected static List<QuickSearch> getSubscribedQuickSearch() { return List.of(); }
    }
}
