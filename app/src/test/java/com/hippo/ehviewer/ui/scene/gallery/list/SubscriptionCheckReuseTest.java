package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.GallerySeenState;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.SubscriptionSearchContext;
import com.hippo.ehviewer.client.SubscriptionGallerySnapshot;
import com.hippo.ehviewer.client.SubscriptionHomepageMatcher;
import com.hippo.ehviewer.client.SubscriptionHomepageScan;
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
        context.getSharedPreferences("reuse-test", Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("reuse-test", Context.MODE_PRIVATE));
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK, false);
        Settings.putBoolean(Settings.KEY_SUBSCRIPTION_HOMEPAGE_SCAN, false);
        client = Shadow.newInstanceOf(EhClient.class);
        manager = new SubscriptionUpdateManager(context);
    }

    @Test public void dividerSnapshotMatchesActualSidebarCheckAndSurvivesBadgeClearing() {
        enableBookmark();
        manager.recordGlobalPageViewed(
                GallerySeenState.empty().observe(page(100, null).galleryInfoList),
                GallerySeenState.empty().observe(page(100, null).galleryInfoList));
        start(true);
        succeed(0, page(200, null));
        succeed(1, page(200, null));
        java.util.Set<Long> eh = manager.getUnreadGalleryGids(ListUrlBuilder.MODE_SUBSCRIPTION);
        java.util.Set<Long> bookmark = manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);
        java.util.Set<Long> global = manager.getUnreadGalleryGids(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
        assertEquals(Collections.singleton(200L), eh);
        assertEquals(eh, bookmark);
        assertEquals(eh, global);
        assertEquals(eh.size(), manager.getSnapshot().ehCount);
        assertEquals(bookmark.size(), manager.getSnapshot().bookmarkCount);
        assertEquals(global.size(), manager.getSnapshot().globalCount);
        manager.onSubscriptionOpened(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
        assertTrue(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION).isEmpty());
        assertEquals(Collections.singleton(200L), global);
    }

    @Test public void subscriptionChecksDoNotClaimInteractiveBrowsingPriority() {
        assertFalse(new EhRequest().isBackground());
        assertTrue(manager.checkForUpdates(false));
        assertFalse(requests.isEmpty());
        for (EhRequest request : requests) assertTrue(request.isBackground());
    }

    @Test public void checkFinishedWithoutVisibleActivityIsDeliveredOnceOnReturn() {
        manager.recordEhPageViewed(page(100, null).galleryInfoList);
        List<SubscriptionUpdateManager.CheckResult> delivered = new ArrayList<>();
        SubscriptionUpdateManager.Listener listener = listener(delivered);
        manager.setListener(listener);
        start(false);
        manager.setListener(null);
        succeed(0, page(200, null));
        assertTrue(delivered.isEmpty());
        manager.setListener(listener);
        assertEquals(1, delivered.size());
        assertEquals(1, delivered.get(0).newEhCount);
        assertTrue(delivered.get(0).hasNewGalleries());
        manager.setListener(null);
        manager.setListener(listener);
        assertEquals(1, delivered.size());
    }

    @Test public void returningAfterReadingOrChangingAccountDoesNotReplayStaleCounts() {
        for (boolean changeAccount : new boolean[]{false, true}) {
            manager.recordEhPageViewed(page(100, null).galleryInfoList);
            start(true);
            succeed(requests.size() - 1, page(200, null));
            if (changeAccount) contextKey = "account-b";
            else manager.onSubscriptionOpened(ListUrlBuilder.MODE_SUBSCRIPTION);
            List<SubscriptionUpdateManager.CheckResult> delivered = new ArrayList<>();
            manager.setListener(listener(delivered));
            if (changeAccount) assertTrue(delivered.isEmpty());
            else {
                assertEquals(1, delivered.size());
                assertFalse(delivered.get(0).hasNewGalleries());
                assertEquals(0, delivered.get(0).snapshot.ehCount);
            }
            manager.setListener(null);
        }
    }

    private static SubscriptionUpdateManager.Listener listener(
            List<SubscriptionUpdateManager.CheckResult> delivered) {
        return new SubscriptionUpdateManager.Listener() {
            @Override public void onSubscriptionUpdateStateChanged() { }
            @Override public void onSubscriptionUpdateCheckFinished(
                    SubscriptionUpdateManager.CheckResult result) { delivered.add(result); }
        };
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
        ShadowSystemClock.advanceBy(Duration.ofSeconds(59));
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

    @Test public void bookmarkChecksUseOnlyExactClientRatingAndKeepItWhenReusingResults() {
        enableBookmark();
        bookmarks.get(0).minRatingHundredths = 480;
        start(true);
        for (EhRequest request : requests) assertFalse(((String) request.getArgs()[0]).contains("f_sr"));
        assertEquals(480, requests.get(1).getArgs()[4]);
        succeed(0, page(200, null));
        succeed(1, page(199, null));
        int before = requests.size();
        refresh(false, manager.getRecentCheckResult(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        assertEquals(before, requests.size());
    }

    @Test public void filteredGlobalFeedLoadsExactMetadataAndStripsRatingFromPagination() {
        enableBookmark();
        bookmarks.get(0).minRatingHundredths = 480;
        start(true);
        succeed(0, page(200, null));
        succeed(1, page(199, null));
        List<Boolean> more = new ArrayList<>();
        BookmarkSubscriptionCoordinator coordinator = new BookmarkSubscriptionCoordinator(client,
                new BookmarkSubscriptionCoordinator.Listener() {
                    @Override public void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                            boolean hasMore, boolean refresh, boolean noSubscriptions, boolean partialFailure) {
                        assertTrue(data.isEmpty());
                        more.add(hasMore);
                    }
                    @Override public void onBookmarkSubscriptionFailure(int taskId, Exception error) {
                        throw new AssertionError(error);
                    }
                });
        coordinator.refresh(1, bookmarks, true,
                manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION), 490);
        // Unfiltered cached pages can carry half-star ratings, so the filtered feed requests metadata.
        assertEquals(4, requests.size());
        assertEquals(490, requests.get(2).getArgs()[4]);
        assertEquals(490, requests.get(3).getArgs()[4]);
        for (int i = 2; i < 4; i++) {
            assertFalse(((String) requests.get(i).getArgs()[0]).contains("f_sr"));
            GalleryListParser.Result empty = page(180 - i, "https://e-hentai.org/?next=100&f_sr=on&f_srdd=5");
            empty.galleryInfoList.clear();
            empty.ratingFilteredAll = true;
            succeed(i, empty);
        }
        assertEquals(Collections.singletonList(true), more);
        assertEquals(4, requests.size()); // do not scan indefinitely when both extra pages were empty
        coordinator.loadMore(2);
        assertEquals(5, requests.size());
        assertFalse(((String) requests.get(4).getArgs()[0]).contains("f_sr"));
        coordinator.cancel();
    }

    @Test public void legacyBookmarkSiteOptionBecomesEquivalentClientBoundary() {
        enableBookmark();
        bookmarks.get(0).advanceSearch = ListUrlBuilder.DEFAULT_ADVANCE;
        bookmarks.get(0).minRating = 4;
        start(true);
        assertFalse(((String) requests.get(1).getArgs()[0]).contains("f_sr"));
        assertEquals(375, requests.get(1).getArgs()[4]);
    }

    @Test public void nativeBookmarkRatingStillAppliesWhenPreciseControlIsDisabled() {
        Settings.putPersistentMinRatingHundredths(450);
        Settings.putBoolean(Settings.KEY_ENABLE_PRECISE_RATING_FILTER, false);
        enableBookmark();
        bookmarks.get(0).advanceSearch = ListUrlBuilder.DEFAULT_ADVANCE;
        bookmarks.get(0).minRating = 4;
        bookmarks.get(0).minRatingHundredths = 480;
        start(true);
        assertFalse(((String) requests.get(1).getArgs()[0]).contains("f_sr"));
        assertEquals(0, requests.get(0).getArgs()[4]);
        assertEquals(375, requests.get(1).getArgs()[4]);
    }

    @Test public void defaultRatingAppliesToChecksAndLowerBrowseOverrideRefetches() {
        Settings.putPersistentMinRatingHundredths(400);
        enableBookmark();
        start(true);
        assertEquals(2, requests.size());
        for (EhRequest request : requests) {
            assertFalse(((String) request.getArgs()[0]).contains("f_sr"));
            assertEquals(400, request.getArgs()[4]);
        }
        succeed(0, page(200, null));
        succeed(1, page(199, null));
        var cached = manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
        refresh(true, cached);
        assertEquals(2, requests.size());
        BookmarkSubscriptionCoordinator coordinator = new BookmarkSubscriptionCoordinator(client,
                new BookmarkSubscriptionCoordinator.Listener() {
                    @Override public void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                            boolean hasMore, boolean refresh, boolean noSubscriptions, boolean partialFailure) { }
                    @Override public void onBookmarkSubscriptionFailure(int taskId, Exception error) {
                        throw new AssertionError(error);
                    }
                });
        coordinator.refresh(2, bookmarks, true, cached, 0);
        assertEquals(4, requests.size());
        for (int i = 2; i < 4; i++) assertEquals(0, requests.get(i).getArgs()[4]);
        assertEquals(400, Settings.getPersistentMinRatingHundredths());
        coordinator.cancel();
        assertNotNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        Settings.putPersistentMinRatingHundredths(350);
        assertNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        assertTrue(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION).isEmpty());
        start(true);
        for (int i = 4; i < 6; i++) assertEquals(350, requests.get(i).getArgs()[4]);
    }

    private void start(boolean manual) {
        assertTrue(manager.checkForUpdates(manual));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void scannerDisablesAutomaticExtraPagesAndPassesItsPublicationCursor() {
        manager.recordEhPageViewed(page(100, null).galleryInfoList);
        start(true);
        Object[] args = requests.get(0).getArgs();
        assertEquals(false, args[5]);
        assertTrue(args[7] instanceof GallerySeenState);
        assertTrue(((GallerySeenState) args[7]).initialized());
        assertEquals(true, args[8]);
    }

    @Test public void preparedCheckResultsOpenImmediatelyWithoutAnotherDisplayRequest() {
        enableBookmark();
        start(true);
        for (int i = 0; i < 2; i++) {
            var checked = page(200 - i, null);
            checked.subscriptionRawGalleries = SubscriptionGallerySnapshot.copy(checked.galleryInfoList);
            checked.subscriptionDisplayGalleries = SubscriptionGallerySnapshot.copy(checked.galleryInfoList);
            checked.galleryInfoList.clear(); // No new candidates still leaves a complete display page.
            succeed(i, checked);
        }
        var cached = manager.getRecentCheckResult(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);
        assertNotNull(cached);
        for (var source : cached.sources) assertFalse(source.requiresDisplayPreparation);
        refresh(false, cached);
        refresh(true, manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        assertEquals(2, requests.size());
    }

    @Test public void filteredCacheShowsItsAvailablePrefixBeforeLoadingAnotherPage() {
        manager.recordEhPageViewed(page(100, null).galleryInfoList);
        start(true);
        var checked = datedPage(200, "2026-09-21 00:00", "https://e-hentai.org/watched?next=90");
        checked.rawResultCount = 25;
        checked.rawTailPosted = "2026-09-19 00:00";
        checked.rawTailGid = 90;
        checked.subscriptionRawGalleries = SubscriptionGallerySnapshot.copy(checked.galleryInfoList);
        checked.subscriptionDisplayGalleries = SubscriptionGallerySnapshot.copy(checked.galleryInfoList);
        succeed(0, checked);
        assertFalse(manager.isChecking());
        List<Long> displayed = new ArrayList<>();
        var coordinator = new BookmarkSubscriptionCoordinator(client,
                new BookmarkSubscriptionCoordinator.Listener() {
                    @Override public void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                            boolean hasMore, boolean refresh, boolean noSubscriptions, boolean partialFailure) {
                        assertTrue(hasMore);
                        for (var gallery : data) displayed.add(gallery.gid);
                    }
                    @Override public void onBookmarkSubscriptionFailure(int taskId, Exception error) {
                        throw new AssertionError(error);
                    }
                });
        coordinator.refresh(1, bookmarks, true,
                manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION));
        assertEquals(Collections.singletonList(200L), displayed);
        assertEquals(1, requests.size());
        coordinator.loadMore(2);
        assertEquals(2, requests.size());
        assertEquals("https://e-hentai.org/watched?next=90", requests.get(1).getArgs()[0]);
        coordinator.cancel();
    }

    private SubscriptionUpdateManager.RecentCheckResult lightweightCheck() {
        manager.recordEhPageViewed(page(100, null).galleryInfoList);
        start(true);
        var checked = datedPage(200, "2026-09-21 00:00", "https://e-hentai.org/watched?next=90");
        checked.galleryInfoList.addAll(datedPage(90, "2026-09-19 00:00", null).galleryInfoList);
        checked.subscriptionRawGalleries = SubscriptionGallerySnapshot.copy(checked.galleryInfoList);
        checked.rawSeenState = GallerySeenState.empty().observe(checked.subscriptionRawGalleries);
        checked.rawResultCount = 2;
        checked.rawTailPosted = "2026-09-19 00:00";
        checked.rawTailGid = 90;
        checked.galleryInfoList.clear();
        succeed(0, checked);
        assertFalse(manager.isChecking());
        return manager.getRecentCheckResult(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION);
    }

    @Test public void lightweightCachePreparesFullPageWithoutRepeatingSearchAndRetainsPagination() {
        var cached = lightweightCheck();
        assertTrue(cached.sources.get(0).requiresDisplayPreparation);
        assertEquals(2, cached.sources.get(0).galleryInfoList.size());
        List<Long> displayed = new ArrayList<>();
        BookmarkSubscriptionCoordinator coordinator = new BookmarkSubscriptionCoordinator(client,
                new BookmarkSubscriptionCoordinator.Listener() {
                    @Override public void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                            boolean hasMore, boolean refresh, boolean noSubscriptions, boolean partialFailure) {
                        assertFalse(partialFailure);
                        assertTrue(hasMore);
                        for (GalleryInfo gallery : data) displayed.add(gallery.gid);
                    }
                    @Override public void onBookmarkSubscriptionFailure(int taskId, Exception error) {
                        throw new AssertionError(error);
                    }
                });
        coordinator.refresh(1, bookmarks, true, cached, 0);
        assertEquals(2, requests.size());
        assertEquals(EhClient.METHOD_PREPARE_SUBSCRIPTION_LIST, requests.get(1).getMethod());
        assertEquals(2, ((List<?>) requests.get(1).getArgs()[1]).size());
        var prepared = new GalleryListParser.Result();
        prepared.galleryInfoList = SubscriptionGallerySnapshot.copy(cached.sources.get(0).galleryInfoList);
        succeed(1, prepared);
        assertEquals(java.util.Arrays.asList(200L, 90L), displayed);
        assertEquals(2, requests.size());
        coordinator.loadMore(2);
        assertEquals(3, requests.size());
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(2).getMethod());
        assertEquals("https://e-hentai.org/watched?next=90", requests.get(2).getArgs()[0]);
        coordinator.cancel();
    }

    @Test public void lightweightPreparationRetriesAndCanBeCancelledWithoutLosingRawCache() {
        var cached = lightweightCheck();
        var coordinator = refresh(true, cached);
        requests.get(1).getCallback().onFailure(new java.io.IOException("offline"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(3, requests.size());
        assertEquals(EhClient.METHOD_PREPARE_SUBSCRIPTION_LIST, requests.get(2).getMethod());
        assertEquals(2, ((List<?>) requests.get(2).getArgs()[1]).size());
        coordinator.cancel();
        assertTrue(requests.get(2).isCancelled());
        succeed(2, new GalleryListParser.Result());
        // Opening again starts a separate consumer and keeps the retained raw page intact.
        refresh(true, cached);
        assertEquals(EhClient.METHOD_PREPARE_SUBSCRIPTION_LIST, requests.get(3).getMethod());
        assertEquals(2, cached.sources.get(0).galleryInfoList.size());
    }

    @Test public void scannerUsesPublicationTimeAndScansAcrossSameMinuteAndLowGidTail() {
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH, true);
        manager.recordEhPageViewed(page(100, null).galleryInfoList);
        start(true);
        GalleryListParser.Result first = page(110, "https://e-hentai.org/watched?next=90");
        first.galleryInfoList.get(0).posted = "2026-09-20 00:01";
        first.galleryInfoList.addAll(page(90, null).galleryInfoList);
        first.rawResultCount = 2;
        first.rawTailGid = 90;
        succeed(0, first);
        assertTrue(manager.isChecking());
        assertEquals(2, requests.size());
        GalleryListParser.Result second = page(105, null);
        succeed(1, second);
        assertFalse(manager.isChecking());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(110L, 90L, 105L)),
                manager.getUnreadGalleryGids(ListUrlBuilder.MODE_SUBSCRIPTION));
    }

    @Test public void failedSourceDoesNotSeedPublicationProgress() {
        start(true);
        requests.get(0).getCallback().onFailure(new IllegalArgumentException("invalid query"));
        assertEquals("", Settings.getString("subscription_source_progress_v2", ""));
        assertTrue(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_SUBSCRIPTION).isEmpty());
    }

    @Test public void scannerIncludesOldestRetainedMinuteAndStopsStrictlyBeforeIt() {
        List<GalleryInfo> seed = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            seed.add(datedPage(100 - i, String.format(java.util.Locale.US,
                    "2026-09-20 00:%02d", 10 - i), null).galleryInfoList.get(0));
        }
        manager.recordEhPageViewed(seed);
        start(true);
        GalleryListParser.Result first = datedPage(110, "2026-09-20 00:11", "https://e-hentai.org/watched?next=90");
        first.galleryInfoList.addAll(datedPage(90, "2026-09-20 00:05", null).galleryInfoList);
        first.rawResultCount = 2;
        first.rawTailPosted = "2026-09-20 00:05";
        succeed(0, first);
        assertEquals(2, requests.size());
        assertTrue(manager.isChecking());
        succeed(1, datedPage(80, "2026-09-20 00:04", "https://e-hentai.org/watched?next=80"));
        assertFalse(manager.isChecking());
        assertEquals(2, requests.size());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(110L, 90L)),
                manager.getUnreadGalleryGids(ListUrlBuilder.MODE_SUBSCRIPTION));
    }

    @Test public void legacyCheckedGidDoesNotSuppressTheFirstPublicationSeed() {
        Settings.putLong("subscription_updates_last_checked_eh_gid", 9999999);
        Settings.putBoolean("subscription_updates_eh_baseline_initialized", true);
        start(true);
        succeed(0, page(100, null));
        assertTrue(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_SUBSCRIPTION).isEmpty());
        assertTrue(Settings.getString("subscription_source_progress_v2", "").contains("2;"));
        assertEquals(9999999, Settings.getLong("subscription_updates_last_checked_eh_gid", 0));
    }

    @Test public void firstOpeningCanCompareArrivalsAfterTheInitialBackgroundCheck() {
        start(true);
        succeed(0, page(100, null));
        assertFalse(SubscriptionUpdateManager.getLastSeenEhState().initialized());
        assertTrue(SubscriptionUpdateManager.getEhDisplayBaseline().initialized());
        assertTrue(SubscriptionUpdateManager.getEhDisplayBaseline().isNew(page(200, null).galleryInfoList.get(0)));
    }

    private void enableHomepage() {
        enableBookmark();
        bookmarks.get(0).keyword = "uploader:tester";
        for (int i = 1; i < 9; i++) {
            enableBookmark();
            bookmarks.get(i).keyword = "uploader:tester";
        }
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH, false);
        Settings.putBoolean(Settings.KEY_SUBSCRIPTION_HOMEPAGE_SCAN, true);
        manager.resetCheckTimer();
    }

    private static GalleryListParser.Result homePage(long start, int count, String next) {
        GalleryListParser.Result page = new GalleryListParser.Result();
        page.nextHref = next;
        page.subscriptionRawGalleries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.gid = start + i;
            gallery.posted = "2026-10-23 00:30";
            gallery.uploader = "tester";
            gallery.category = com.hippo.ehviewer.client.EhConfig.MANGA;
            page.subscriptionRawGalleries.add(gallery);
            page.galleryInfoList.add(gallery);
        }
        page.rawResultCount = count;
        page.rawSeenState = GallerySeenState.empty().observe(page.subscriptionRawGalleries);
        return page;
    }

    @Test public void legacyHomepagePreferenceUsesOrdinarySearchForManualAndAutomaticChecks() {
        enableHomepage();
        assertFalse(Settings.getSubscriptionHomepageScan());
        for (boolean manual : new boolean[]{true, false}) {
            int before = requests.size();
            start(manual);
            // The nine identical subscriptions still merge into one ordinary search.
            assertEquals(before + 1, requests.size());
            assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(before).getMethod());
            assertEquals(ListUrlBuilder.MODE_NORMAL, requests.get(before).getArgs()[1]);
            succeed(before, page(manual ? 100 : 200, null));
            assertFalse(manager.isChecking());
            assertNotNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        }
    }

    /* Historical integration cases for the disabled homepage entry points.
    @Test public void homepageModeSeedsThenCountsMatchesOnBothSidesOfAnchorAndExtraPage() {
        enableHomepage();
        start(true);
        assertEquals(EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE, requests.get(0).getMethod());
        succeed(0, homePage(100, 25, null));
        assertFalse(manager.isChecking());
        assertEquals(0, manager.getSnapshot().bookmarkCount);
        assertNull(manager.getRecentCheckResult(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        start(false);
        GalleryListParser.Result first = homePage(200, 24, "https://e-hentai.org/?next=100");
        first.subscriptionRawGalleries.addAll(homePage(100, 1, null).subscriptionRawGalleries);
        first.rawResultCount++;
        succeed(1, first);
        assertTrue(manager.isChecking());
        GalleryListParser.Result second = homePage(300, 25, "https://e-hentai.org/?next=75");
        second.galleryInfoList = new ArrayList<>(second.galleryInfoList.subList(0, 2));
        succeed(2, second);
        assertFalse(manager.isChecking());
        assertEquals(26, manager.getSnapshot().bookmarkCount);
        assertTrue(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION).contains(301L));
        assertFalse(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION).contains(324L));
        start(true);
        succeed(3, homePage(200, 25, "https://e-hentai.org/?next=175"));
        assertFalse(manager.isChecking());
        assertEquals(27, manager.getSnapshot().bookmarkCount);
    }

    @Test public void failedAndCancelledHomepagePassesDoNotCommitUnreadOrCheckpoint() {
        enableHomepage();
        start(true);
        succeed(0, homePage(100, 25, null));
        String checkpoint = Settings.getString("subscription_homepage_checkpoint_v1", "");
        for (boolean cancel : new boolean[]{false, true}) {
            int index = requests.size();
            start(true);
            succeed(index, homePage(200, 25, "https://e-hentai.org/?next=175"));
            assertTrue(manager.isChecking());
            if (cancel) manager.cancelCheck();
            else requests.get(index + 1).getCallback().onFailure(new IllegalArgumentException("failed"));
            assertFalse(manager.isChecking());
            assertEquals(checkpoint, Settings.getString("subscription_homepage_checkpoint_v1", ""));
            assertEquals(0, manager.getSnapshot().bookmarkCount);
        }
    }

    @Test public void homepageModeKeepsComplexSearchesAndEhSubscriptionOnExistingPaths() {
        enableHomepage();
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH, true);
        QuickSearch complex = new QuickSearch();
        complex.subscribed = true;
        complex.mode = ListUrlBuilder.MODE_NORMAL;
        complex.keyword = "free text";
        complex.category = EhUtils.NONE;
        complex.advanceSearch = complex.minRating = complex.pageFrom = complex.pageTo = -1;
        bookmarks.add(complex);
        start(true);
        assertEquals(3, requests.size());
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(0).getMethod());
        assertEquals(ListUrlBuilder.MODE_SUBSCRIPTION, requests.get(0).getArgs()[1]);
        assertEquals(EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE, requests.get(1).getMethod());
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(2).getMethod());
        assertTrue(((String) requests.get(2).getArgs()[0]).contains("free+text"));
        succeed(0, page(100, null));
        succeed(1, homePage(200, 25, null));
        succeed(2, page(100, null));
        assertFalse(manager.isChecking());
    }

    @Test public void changedHomepageBookmarkDefinitionKeepsProgressForUnchangedSearches() {
        enableHomepage();
        start(true);
        succeed(0, homePage(100, 25, null));
        bookmarks.get(0).keyword = "uploader:other";
        start(true);
        succeed(1, homePage(200, 25, null));
        assertEquals(25, manager.getSnapshot().bookmarkCount);
    }

    private void useNativeOnNextCheck() {
        Settings.putLong("subscription_updates_last_check_time", System.currentTimeMillis() - 60 * 60_000L);
    }

    private static GalleryInfo arrival(long gid, int minute) {
        GalleryInfo gallery = homePage(gid, 1, null).galleryInfoList.get(0);
        gallery.posted = String.format(java.util.Locale.US, "2026-10-23 00:%02d", minute);
        return gallery;
    }

    private static GalleryListParser.Result result(String next, GalleryInfo... galleries) {
        GalleryListParser.Result result = new GalleryListParser.Result();
        result.nextHref = next;
        result.galleryInfoList = new ArrayList<>(java.util.Arrays.asList(galleries));
        result.subscriptionRawGalleries = SubscriptionGallerySnapshot.copy(result.galleryInfoList);
        result.rawSeenState = GallerySeenState.empty().observe(result.subscriptionRawGalleries);
        result.rawResultCount = galleries.length;
        if (galleries.length > 0) {
            result.rawTailPosted = galleries[galleries.length - 1].posted;
            result.rawTailGid = galleries[galleries.length - 1].gid;
        }
        return result;
    }

    @Test public void automaticSelectionUsesTheAutomaticTimerAndRawBookmarkCount() {
        enableHomepage();
        // Nine identical bookmarks merge into one source, but N remains nine.
        start(false);
        assertEquals(EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE, requests.get(0).getMethod());
        manager.cancelCheck();
        useNativeOnNextCheck();
        start(true);
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(1).getMethod());
        manager.cancelCheck();
        // Opening a subscription resets exactly the timer used by automatic checks.
        manager.onSubscriptionOpened(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);
        start(true);
        assertEquals(EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE, requests.get(2).getMethod());
        manager.cancelCheck();
        bookmarks.remove(8);
        manager.resetCheckTimer();
        start(true);
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(3).getMethod());
        manager.cancelCheck();
    }

    @Test public void nativeHomepageNativeRoundTripSurvivesBadgeClearingAndManagerRestart() {
        enableHomepage();
        useNativeOnNextCheck();
        start(true);
        succeed(0, result(null, arrival(100, 30)));
        assertEquals(0, manager.getSnapshot().bookmarkCount);
        useNativeOnNextCheck();
        start(true);
        succeed(1, result(null, arrival(200, 31), arrival(100, 30)));
        assertEquals(Collections.singleton(200L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        manager.onSubscriptionOpened(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);

        start(true);
        assertEquals(EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE, requests.get(2).getMethod());
        // No homepage checkpoint exists: the arrival must be reported on this very first pass.
        succeed(2, result(null, arrival(300, 32), arrival(200, 31), arrival(100, 30)));
        assertFalse(manager.isChecking());
        assertEquals(Collections.singleton(300L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        manager.onSubscriptionOpened(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);
        manager = new SubscriptionUpdateManager(RuntimeEnvironment.getApplication());

        useNativeOnNextCheck();
        start(true);
        assertEquals(EhClient.METHOD_GET_GALLERY_LIST, requests.get(3).getMethod());
        GallerySeenState cursor = (GallerySeenState) requests.get(3).getArgs()[7];
        assertFalse(cursor.isNew(arrival(300, 32)));
        assertTrue(cursor.isNew(arrival(400, 33)));
        succeed(3, result(null, arrival(400, 33), arrival(300, 32), arrival(200, 31)));
        assertEquals(Collections.singleton(400L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        manager.onSubscriptionOpened(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION);

        start(true);
        // The old homepage checkpoint knows neither 400 nor 500; native progress excludes 400.
        succeed(4, result(null, arrival(500, 34), arrival(400, 33), arrival(300, 32)));
        assertEquals(Collections.singleton(500L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
    }

    @Test public void failedOrCancelledHandoffDoesNotAdvanceNativeProgress() {
        for (boolean cancel : new boolean[]{false, true}) {
            setUp();
            enableHomepage();
            useNativeOnNextCheck();
            start(true);
            succeed(0, result(null, arrival(100, 30)));
            String progress = Settings.getString("subscription_source_progress_v2", "");
            manager.resetCheckTimer();
            start(true);
            succeed(1, result("https://e-hentai.org/?next=100", arrival(200, 31)));
            assertTrue(manager.isChecking());
            if (cancel) manager.cancelCheck();
            else requests.get(2).getCallback().onFailure(new IllegalArgumentException("failed"));
            assertEquals(progress, Settings.getString("subscription_source_progress_v2", ""));
            assertEquals(0, manager.getSnapshot().bookmarkCount);
            useNativeOnNextCheck();
            start(true);
            succeed(3, result(null, arrival(200, 31), arrival(100, 30)));
            assertEquals(Collections.singleton(200L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        }
    }

    @Test public void continuousHomepagePassesKeepTheCompleteFloorBeyondTheNativeSixIdWindow() {
        enableHomepage();
        GalleryListParser.Result baseline = homePage(100, 25, null);
        baseline.galleryInfoList.get(0).posted = "2026-10-23 00:32";
        baseline.rawSeenState = GallerySeenState.empty().observe(baseline.subscriptionRawGalleries);
        start(true);
        succeed(0, baseline);
        start(true);
        GalleryListParser.Result next = homePage(100, 25, "https://e-hentai.org/?next=75");
        next.subscriptionRawGalleries.get(0).posted = "2026-10-23 00:32";
        next.subscriptionRawGalleries.set(1, arrival(999, 30));
        next.galleryInfoList = SubscriptionGallerySnapshot.copy(next.subscriptionRawGalleries);
        next.rawSeenState = GallerySeenState.empty().observe(next.subscriptionRawGalleries);
        succeed(1, next);
        assertFalse(manager.isChecking());
        assertEquals(Collections.singleton(999L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
    }

    @Test public void handoffPreservesIndependentSourceWindowsInsteadOfUsingGlobalNewestTime() {
        enableHomepage();
        for (int i = 0; i < 8; i++) bookmarks.get(i).keyword = "uploader:alice l:chinese$";
        bookmarks.get(8).keyword = "uploader:bob l:english$";
        useNativeOnNextCheck();
        start(true);
        assertEquals(2, requests.size());
        for (int i = 0; i < 2; i++) {
            boolean alice = ((String) requests.get(i).getArgs()[0]).contains("alice");
            succeed(i, result(null, arrival(alice ? 100 : 200, alice ? 20 : 30)));
        }
        manager.resetCheckTimer();
        start(true);
        GalleryInfo alice = arrival(110, 25);
        alice.uploader = "alice";
        alice.simpleTags = new String[]{"language:chinese"};
        GalleryInfo bob = arrival(210, 31);
        bob.uploader = "bob";
        bob.simpleTags = new String[]{"language:english"};
        succeed(2, result(null, bob, alice));
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(110L, 210L)),
                manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
    }

    @Test public void homepageCommitsOnlyAfterMinuteCompletionAndKeepsSpillIdsAcrossShorterRepeats() {
        enableHomepage();
        start(true);
        assertEquals(3, requests.get(0).getArgs().length);
        succeed(0, homePage(100, 25, "https://e-hentai.org/?next=75"));
        assertTrue(manager.isChecking());
        assertEquals("", Settings.getString("subscription_homepage_checkpoint_v1", ""));
        GalleryListParser.Result closure = homePage(200, 5, "https://e-hentai.org/?next=50");
        List<GalleryInfo> older = homePage(300, 20, null).subscriptionRawGalleries;
        for (GalleryInfo gallery : older) gallery.posted = "2026-10-23 00:29";
        closure.subscriptionRawGalleries.addAll(older);
        closure.rawResultCount = 25;
        succeed(1, closure);
        assertFalse(manager.isChecking());
        assertTrue(Settings.getString("subscription_homepage_checkpoint_v1", "").contains("\"version\":2"));
        assertEquals(0, manager.getSnapshot().bookmarkCount);
        start(true);
        succeed(2, homePage(100, 25, "https://e-hentai.org/?next=75"));
        assertFalse(manager.isChecking());
        start(true);
        GalleryListParser.Result arrivals = homePage(100, 25, "https://e-hentai.org/?next=75");
        GalleryInfo oldSpill = homePage(200, 1, null).galleryInfoList.get(0);
        GalleryInfo arrival = homePage(999, 1, null).galleryInfoList.get(0);
        arrivals.subscriptionRawGalleries.set(1, oldSpill);
        arrivals.subscriptionRawGalleries.set(2, arrival);
        arrivals.galleryInfoList = new ArrayList<>(java.util.Arrays.asList(oldSpill, arrival));
        succeed(3, arrivals);
        assertFalse(manager.isChecking());
        assertEquals(Collections.singleton(999L), manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
    }

    @Test public void unmatchedHomepageStillSetsDisplayBaselineAndContextChangeCannotCommit() {
        enableHomepage();
        start(true);
        GalleryListParser.Result rawOnly = homePage(100, 25, null);
        rawOnly.galleryInfoList.clear();
        succeed(0, rawOnly);
        GalleryInfo older = homePage(90, 1, null).galleryInfoList.get(0);
        older.posted = "2026-10-23 00:20";
        assertFalse(SubscriptionUpdateManager.getBookmarkDisplayBaseline().isNew(older));
        String checkpoint = Settings.getString("subscription_homepage_checkpoint_v1", "");
        start(true);
        contextKey = "account-b";
        succeed(1, homePage(200, 25, null));
        assertEquals(checkpoint, Settings.getString("subscription_homepage_checkpoint_v1", ""));
        assertEquals(0, manager.getSnapshot().bookmarkCount);
    }

    @Test public void repeatedHomepageLinkFailsWithoutCommittingPartialResults() {
        enableHomepage();
        start(true);
        succeed(0, homePage(100, 25, null));
        String checkpoint = Settings.getString("subscription_homepage_checkpoint_v1", "");
        start(true);
        String next = "https://e-hentai.org/?next=175";
        succeed(1, homePage(200, 25, next));
        succeed(2, homePage(300, 25, next));
        assertFalse(manager.isChecking());
        assertEquals(3, requests.size());
        assertEquals(checkpoint, Settings.getString("subscription_homepage_checkpoint_v1", ""));
        assertEquals(0, manager.getSnapshot().bookmarkCount);
    }

    */

    private static GalleryListParser.Result datedPage(long gid, String posted, String next) {
        GalleryListParser.Result result = page(gid, next);
        result.galleryInfoList.get(0).posted = posted;
        result.rawTailPosted = posted;
        return result;
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
        EhRequest request = requests.get(index);
        if (request.getMethod() == EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE) {
            SubscriptionHomepageMatcher matcher = (SubscriptionHomepageMatcher) request.getArgs()[1];
            SubscriptionHomepageScan scan = (SubscriptionHomepageScan) request.getArgs()[2];
            page.galleryInfoList.removeIf(gallery -> !scan.isNew(gallery)
                    || !matcher.mayMatch(gallery) || !matcher.matches(gallery));
        }
        requests.get(index).getCallback().onSuccess(page);
    }

    private BookmarkSubscriptionCoordinator refresh(boolean includeEh, SubscriptionUpdateManager.RecentCheckResult cached) {
        var coordinator = new BookmarkSubscriptionCoordinator(client, new BookmarkSubscriptionCoordinator.Listener() {
            @Override public void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                    boolean hasMore, boolean refresh, boolean noSubscriptions, boolean partialFailure) {
                assertFalse(partialFailure);
            }
            @Override public void onBookmarkSubscriptionFailure(int taskId, Exception error) {
                throw new AssertionError(error);
            }
        });
        coordinator.refresh(1, bookmarks, includeEh, cached);
        return coordinator;
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
