package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.SubscriptionSearchContext;
import com.hippo.ehviewer.client.SubscriptionUpdateManager;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, shadows = {
        SubscriptionCheckReuseTest.AppShadow.class,
        SubscriptionCheckReuseTest.ClientShadow.class,
        SubscriptionCheckReuseTest.ContextShadow.class,
        SubscriptionCheckReuseTest.DbShadow.class})
public class SubscriptionCheckReuseTest {
    private static final List<EhRequest> requests = new ArrayList<>();
    private static final List<QuickSearch> bookmarks = new ArrayList<>();
    private static String contextKey;
    private static EhClient client;
    private SubscriptionUpdateManager manager;

    @Before public void setUp() {
        requests.clear();
        bookmarks.clear();
        contextKey = "account-a";
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("reuse-test", Context.MODE_PRIVATE));
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK, false);
        client = Shadow.newInstanceOf(EhClient.class);
        manager = new SubscriptionUpdateManager(context);
    }

    @Test public void manualAndAutomaticChecksAreReusableByGlobalEntryOnly() {
        for (boolean manual : new boolean[]{false, true}) {
            assertTrue(manager.checkForUpdates(manual));
            succeed(requests.size() - 1, page(200, null));
            assertFalse(manager.isChecking());
            manager.onSubscriptionOpened(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
            assertNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_SUBSCRIPTION));
            SubscriptionUpdateManager.RecentCheckResult cached =
                    manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
            assertNotNull(cached);
            assertEquals(200, cached.sources.get(0).galleryInfoList.get(0).gid);
            int before = requests.size();
            refresh(true, cached);
            refresh(true, manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
            assertEquals(before, requests.size());
        }
    }

    @Test public void bookmarkAndGlobalEntriesShareManualResultsButExplicitRefreshRequestsAgain() {
        enableBookmark();
        start(true);
        succeed(0, page(200, null));
        succeed(1, page(199, null));
        refresh(false, manager.getRecentCheckResult(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        refresh(true, manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        assertEquals(2, requests.size());
        refresh(true, null);
        assertEquals(4, requests.size());
    }

    @Test public void openingDuringEitherCheckRetainsSuccessfulSourcesAndRetriesMissingOnes() {
        enableBookmark();
        for (boolean manual : new boolean[]{false, true}) {
            int before = requests.size();
            start(manual);
            succeed(before, page(200, null));
            assertTrue(manager.isChecking());
            manager.onSubscriptionOpened(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
            assertTrue(requests.get(before + 1).isCancelled());
            refresh(true, manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
            assertEquals(before + 3, requests.size());
            assertEquals(ListUrlBuilder.MODE_NORMAL, requests.get(before + 2).getArgs()[1]);
        }
    }

    @Test public void failureDoesNotDiscardOtherSuccessfulSources() {
        enableBookmark();
        start(true);
        succeed(0, page(200, null));
        requests.get(1).getCallback().onFailure(new IllegalArgumentException("invalid query"));
        refresh(true, manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        assertEquals(3, requests.size());
        assertEquals(ListUrlBuilder.MODE_NORMAL, requests.get(2).getArgs()[1]);
    }

    @Test public void freshnessStartsAtRequestAndIsRecheckedByConsumers() {
        start(true);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(29));
        succeed(0, page(200, null));
        SubscriptionUpdateManager.RecentCheckResult cached =
                manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        refresh(true, cached);
        assertEquals(1, requests.size());
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        assertNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        refresh(true, cached);
        assertEquals(2, requests.size());
    }

    @Test public void changedContextCannotReuseCachedResults() {
        start(true);
        succeed(0, page(200, null));
        SubscriptionUpdateManager.RecentCheckResult cached =
                manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
        contextKey = "account-b";
        assertNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        refresh(true, cached);
        assertEquals(2, requests.size());
    }

    private void start(boolean manual) {
        assertTrue(manager.checkForUpdates(manual));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void enableBookmark() {
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK, true);
        QuickSearch bookmark = new QuickSearch();
        bookmark.subscribed = true;
        bookmark.mode = ListUrlBuilder.MODE_NORMAL;
        bookmark.category = EhUtils.NONE;
        bookmark.advanceSearch = bookmark.minRating = bookmark.pageFrom = bookmark.pageTo = -1;
        bookmark.keyword = "test";
        bookmarks.add(bookmark);
    }

    private static GalleryListParser.Result page(long gid, String nextHref) {
        GalleryListParser.Result result = new GalleryListParser.Result();
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        gallery.title = "gallery";
        gallery.posted = "2026-09-20 00:00";
        result.galleryInfoList.add(gallery);
        result.rawResultCount = 1;
        result.rawHeadGid = result.rawTailGid = gid;
        result.rawTailPosted = gallery.posted;
        result.nextHref = nextHref;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void succeed(int index, GalleryListParser.Result page) {
        requests.get(index).getCallback().onSuccess(page);
    }

    private void refresh(boolean includeEh, SubscriptionUpdateManager.RecentCheckResult cached) {
        new BookmarkSubscriptionCoordinator(client, new BookmarkSubscriptionCoordinator.Listener() {
            @Override public void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                    boolean hasMore, boolean refresh, boolean noSubscriptions, boolean partialFailure) {
                assertFalse(partialFailure);
            }
            @Override public void onBookmarkSubscriptionFailure(int taskId, Exception error) {
                throw new AssertionError(error);
            }
        }).refresh(1, bookmarks, includeEh, cached);
    }

    @Implements(EhApplication.class)
    public static class AppShadow {
        @Implementation protected static EhClient getEhClient(Context context) { return client; }
        @Implementation protected static ExecutorService getExecutorService(Context context) {
            return new AbstractExecutorService() {
                @Override public void execute(Runnable command) { command.run(); }
                @Override public void shutdown() { }
                @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
                @Override public boolean isShutdown() { return false; }
                @Override public boolean isTerminated() { return false; }
                @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
            };
        }
    }

    @Implements(EhClient.class)
    public static class ClientShadow {
        @Implementation protected void execute(EhRequest request) { requests.add(request); }
    }

    @Implements(SubscriptionSearchContext.class)
    public static class ContextShadow {
        @Implementation protected static String key() { return contextKey; }
    }

    @Implements(EhDB.class)
    public static class DbShadow {
        @Implementation protected static List<QuickSearch> getSubscribedQuickSearch() { return bookmarks; }
    }
}
