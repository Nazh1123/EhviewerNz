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
import static com.hippo.ehviewer.client.GallerySeenStateTest.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, shadows = {SeenGalleryHistoryTest.DbShadow.class, SeenGalleryHistoryTest.ContextShadow.class})
public class SeenGalleryHistoryTest {
    private static final List<QuickSearch> bookmarks = new ArrayList<>();
    private ListUrlBuilder builder;

    @Before public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        RuntimeEnvironment.getApplication().deleteDatabase(SearchUpdateStore.DATABASE);
        SearchUpdateStore.initialize(RuntimeEnvironment.getApplication());
        Settings.putString("seen_gallery_searches_v2", "");
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
        SeenGalleryHistory.record(search, List.of(gallery(100, 100)));
        assertEquals("", Settings.getString("seen_gallery_searches_v2", ""));
    }

    @Test public void differentExactDefaultsDoNotShareViewedHistoryWithinTheSameSiteOption() {
        Settings.putPersistentMinRatingHundredths(400);
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), List.of(gallery(100, 100)));
        Settings.putPersistentMinRatingHundredths(450);
        assertTrue(builder.build().contains("f_srdd=4"));
        assertEquals(0, marker(SeenGalleryHistory.open(builder).baseline()));
        Settings.putPersistentMinRatingHundredths(400);
        assertEquals(100, marker(SeenGalleryHistory.open(builder).baseline()));
        builder.setMinRatingHundredths(0);
        assertEquals(0, marker(SeenGalleryHistory.open(builder).baseline()));
    }

    @Test public void sameQueryIgnoresPaginationButIncludesSearchFiltersAndSite() {
        SeenGalleryHistory.Search search = SeenGalleryHistory.open(builder);
        SeenGalleryHistory.record(search, List.of(gallery(100, 100)));
        builder.setPageIndex(5);
        assertEquals(100, marker(SeenGalleryHistory.open(builder).baseline()));
        builder.setCategory(1);
        assertEquals(0, marker(SeenGalleryHistory.open(builder).baseline()));
        builder.setCategory(-1);
        builder.setAdvanceSearch(3);
        builder.setMinRating(4);
        assertEquals(0, marker(SeenGalleryHistory.open(builder).baseline()));
        builder.setAdvanceSearch(-1);
        builder.setMinRating(-1);
        Settings.putGallerySite(1);
        assertEquals(0, marker(SeenGalleryHistory.open(builder).baseline()));
        Settings.putGallerySite(0);
        assertEquals(100, marker(SeenGalleryHistory.open(builder).baseline()));
    }

    @Test public void editingAndDeletingBookmarkDemotesOldConditionsAndKeepsThemSearchable() {
        QuickSearch bookmark = builder.toQuickSearch();
        bookmarks.add(bookmark);
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), List.of(gallery(100, 100)));
        assertTrue(SeenGalleryHistory.open(builder).bookmarked());
        bookmark.keyword = "edited";
        SeenGalleryHistory.onBookmarksChanged(bookmarks);
        SeenGalleryHistory.Search old = SeenGalleryHistory.open(builder);
        assertFalse(old.bookmarked());
        assertEquals(100, marker(old.baseline()));
        builder.set(bookmark);
        assertEquals(0, marker(SeenGalleryHistory.open(builder).baseline()));
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), List.of(gallery(200, 200)));
        bookmarks.clear();
        SeenGalleryHistory.onBookmarksChanged(bookmarks);
        assertFalse(SeenGalleryHistory.open(builder).bookmarked());
        assertEquals(200, marker(SeenGalleryHistory.open(builder).baseline()));
    }

    @Test public void subscribedBookmarksUseIndependentQueriesAndEditsDoNotInheritAnotherBaseline() {
        QuickSearch bookmark = builder.toQuickSearch();
        bookmark.subscribed = true; bookmarks.add(bookmark);
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), List.of(gallery(100,100)));
        assertEquals(100, marker(SeenGalleryHistory.open(builder).baseline()));
        bookmark.keyword="changed-subscription";
        SeenGalleryHistory.onBookmarksChanged(bookmarks);
        assertFalse(SeenGalleryHistory.open(builder).subscribed());
        assertEquals(100,marker(SeenGalleryHistory.open(builder).baseline()));
        builder.set(bookmark);
        assertFalse(SeenGalleryHistory.open(builder).baseline().initialized());
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder),List.of(gallery(250,250)));
        assertEquals(250,marker(SeenGalleryHistory.open(builder).baseline()));
    }

    @Test public void reducingHistorySizeImmediatelyTrimsOrdinaryRecordsButKeepsBookmarks() {
        QuickSearch bookmark=builder.toQuickSearch();bookmarks.add(bookmark);
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder),List.of(gallery(1000,1000)));
        for(int i=0;i<100;i++) {
            builder.setKeyword("query"+i);
            SeenGalleryHistory.record(SeenGalleryHistory.open(builder),List.of(gallery(i+1,i+1)));
        }
        Settings.setHistoryInfoSize(100);
        try(SearchUpdateStore store=SearchUpdateStore.open();
            android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM queries",null)) {
            c.moveToFirst();assertEquals(51,c.getInt(0));
        }
        builder.set(bookmark);
        assertEquals(1000,marker(SeenGalleryHistory.open(builder).baseline()));
    }

    @Test public void successfulSearchesContinuouslyEnforceTheOrdinaryHistoryBudget() {
        Settings.setHistoryInfoSize(100);
        for(int i=0;i<60;i++) {
            builder.setKeyword("bounded-query-"+i);
            SeenGalleryHistory.record(SeenGalleryHistory.open(builder),List.of(gallery(i+1,i+1)));
        }
        try(SearchUpdateStore store=SearchUpdateStore.open();
            android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM queries",null)) {
            c.moveToFirst();assertEquals(50,c.getInt(0));
        }
    }

    @Test public void legacyGidRecordsArePreservedButFirstSuccessSeedsV2() {
        String old = "S:0/" + SeenGalleryHistory.queryKey(builder) + ":9999999\n";
        Settings.putString("seen_gallery_searches_v1", old);
        assertFalse(SeenGalleryHistory.open(builder).baseline().initialized());
        SeenGalleryHistory.record(SeenGalleryHistory.open(builder), List.of(gallery(100, 100)));
        assertEquals(100, marker(SeenGalleryHistory.open(builder).baseline()));
        assertEquals(old, Settings.getString("seen_gallery_searches_v1", ""));
    }

    @Implements(SubscriptionSearchContext.class)
    public static class ContextShadow {
        @Implementation protected static String key() { return "account/site-"+Settings.getGallerySite(); }
    }

    @Implements(EhDB.class)
    public static class DbShadow {
        @Implementation protected static List<QuickSearch> getAllQuickSearch() { return bookmarks; }
        @Implementation protected static List<QuickSearch> getSubscribedQuickSearch() { return bookmarks.stream().filter(q->q.subscribed).toList(); }
    }
}
