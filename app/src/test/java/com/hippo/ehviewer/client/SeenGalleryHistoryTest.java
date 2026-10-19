package com.hippo.ehviewer.client;

import android.app.Application;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, shadows = SeenGalleryHistoryTest.DbShadow.class)
public class SeenGalleryHistoryTest {
    private static final List<QuickSearch> bookmarks = new ArrayList<>();
    private ListUrlBuilder builder;

    @Before public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString("seen_gallery_searches_v1", "");
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        Settings.putGallerySite(0);
        bookmarks.clear();
        builder = new ListUrlBuilder();
        builder.setKeyword("test");
    }

    @Test public void defaultOffAndDisabledRequestsDoNotRecord() {
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, false);
        assertNull(SeenGalleryHistory.open(builder));
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, true);
        SeenGalleryHistory.Search search = SeenGalleryHistory.open(builder);
        Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES, false);
        SeenGalleryHistory.record(search, 100);
        assertEquals("", Settings.getString("seen_gallery_searches_v1", ""));
    }

    @Test public void differentExactDefaultsDoNotShareViewedHistoryWithinTheSameSiteOption() {
        Settings.putPersistentMinRatingHundredths(400);
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), 100);
        Settings.putPersistentMinRatingHundredths(450);
        assertTrue(builder.build().contains("f_srdd=4"));
        assertEquals(0, SeenGalleryHistory.open(builder).baseline());
        Settings.putPersistentMinRatingHundredths(400);
        assertEquals(100, SeenGalleryHistory.open(builder).baseline());
        builder.setMinRatingHundredths(0);
        assertEquals(0, SeenGalleryHistory.open(builder).baseline());
    }

    @Test public void sameQueryIgnoresPaginationButIncludesSearchFiltersAndSite() {
        SeenGalleryHistory.Search search = SeenGalleryHistory.open(builder);
        SeenGalleryHistory.record(search, 100);
        builder.setPageIndex(5);
        assertEquals(100, SeenGalleryHistory.open(builder).baseline());
        builder.setCategory(1);
        assertEquals(0, SeenGalleryHistory.open(builder).baseline());
        builder.setCategory(-1);
        builder.setAdvanceSearch(3);
        builder.setMinRating(4);
        assertEquals(0, SeenGalleryHistory.open(builder).baseline());
        builder.setAdvanceSearch(-1);
        builder.setMinRating(-1);
        Settings.putGallerySite(1);
        assertEquals(0, SeenGalleryHistory.open(builder).baseline());
        Settings.putGallerySite(0);
        assertEquals(100, SeenGalleryHistory.open(builder).baseline());
    }

    @Test public void editingAndDeletingBookmarkDemotesOldConditionsAndKeepsThemSearchable() {
        QuickSearch bookmark = builder.toQuickSearch();
        bookmarks.add(bookmark);
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), 100);
        assertTrue(SeenGalleryHistory.open(builder).bookmarked());
        bookmark.keyword = "edited";
        SeenGalleryHistory.onBookmarksChanged(bookmarks);
        SeenGalleryHistory.Search old = SeenGalleryHistory.open(builder);
        assertFalse(old.bookmarked());
        assertEquals(100, old.baseline());
        builder.set(bookmark);
        assertEquals(0, SeenGalleryHistory.open(builder).baseline());
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), 200);
        bookmarks.clear();
        SeenGalleryHistory.onBookmarksChanged(bookmarks);
        assertFalse(SeenGalleryHistory.open(builder).bookmarked());
        assertEquals(200, SeenGalleryHistory.open(builder).baseline());
    }

    @Test public void editedSubscribedBookmarkDemotesTheLatestSharedBaseline() {
        QuickSearch bookmark = builder.toQuickSearch();
        bookmark.subscribed = true;
        bookmarks.add(bookmark);
        Settings.putLong("subscription_updates_last_seen_bookmark_gid", 100);
        assertEquals(100, SeenGalleryHistory.open(builder).baseline());
        Settings.putLong("subscription_updates_last_seen_bookmark_gid", 200);
        bookmark.keyword = "changed-subscription";
        SeenGalleryHistory.onBookmarksChanged(bookmarks);
        assertFalse(SeenGalleryHistory.open(builder).subscribed());
        assertEquals(200, SeenGalleryHistory.open(builder).baseline());
        builder.set(bookmark);
        assertEquals(200, SeenGalleryHistory.open(builder).baseline());
        SeenGalleryHistory.Search inFlight = SeenGalleryHistory.open(builder);
        bookmarks.clear();
        assertFalse(SeenGalleryHistory.record(inFlight, 250));
        assertEquals(250, SeenGalleryHistory.open(builder).baseline());
    }

    @Test public void reducingHistorySizeImmediatelyTrimsOrdinaryRecordsButKeepsBookmarks() {
        SeenGalleryStore store = new SeenGalleryStore("");
        for (int i = 0; i < 100; i++) {
            store.record("0/" + SubscriptionProgressStore.key("search", "q" + i), i + 1, false, 500);
        }
        store.record("0/" + SubscriptionProgressStore.key("search", "bookmark"), 1000, true, 500);
        Settings.putString("seen_gallery_searches_v1", store.serialize());
        Settings.setHistoryInfoSize(100);
        String serialized = Settings.getString("seen_gallery_searches_v1", "");
        assertEquals(51, serialized.lines().count());
        assertEquals(1000, new SeenGalleryStore(serialized)
                .get("0/" + SubscriptionProgressStore.key("search", "bookmark")));
    }

    @Implements(EhDB.class)
    public static class DbShadow {
        @Implementation protected static List<QuickSearch> getAllQuickSearch() { return bookmarks; }
    }
}
