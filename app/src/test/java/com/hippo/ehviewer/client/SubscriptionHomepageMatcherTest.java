package com.hippo.ehviewer.client;

import android.app.Application;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionHomepageMatcherTest {
    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication()).edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
    }
    static QuickSearch bookmark(String keyword) {
        QuickSearch q = new QuickSearch();
        q.mode = ListUrlBuilder.MODE_NORMAL;
        q.category = EhUtils.NONE;
        q.advanceSearch = q.minRating = q.pageFrom = q.pageTo = -1;
        q.keyword = keyword;
        q.subscribed = true;
        return q;
    }
    private GalleryInfo gallery() {
        GalleryInfo g = new GalleryInfo();
        g.uploader = "Alice Smith";
        g.category = EhConfig.MANGA;
        g.pages = 20;
        g.rating = 4.51f;
        g.simpleTags = new String[]{"artist:someone", "language:chinese"};
        return g;
    }
    @Test public void exactUploaderAndNamespaceAliasesAndNegativeTagsIntersect() {
        QuickSearch q = bookmark("uploader:\"Alice Smith\" l:chinese$ -f:anal$");
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(List.of(q));
        assertTrue(matcher.fallbackBookmarks().isEmpty());
        assertTrue(matcher.matches(gallery()));
        GalleryInfo gallery = gallery();
        gallery.simpleTags = new String[]{"language:chinese", "female:anal"};
        assertFalse(matcher.matches(gallery));
        gallery.uploader = "Bob";
        assertFalse(matcher.matches(gallery));
    }
    @Test public void uploaderAndTagPathsIgnoreInactiveBookmarkOptions() {
        QuickSearch uploader = bookmark("Alice Smith");
        uploader.mode = ListUrlBuilder.MODE_UPLOADER;
        uploader.category = 0;
        uploader.pageFrom = 999;
        uploader.advanceSearch = 255;
        assertTrue(new SubscriptionHomepageMatcher(List.of(uploader)).matches(gallery()));
        QuickSearch tag = bookmark("language:chinese");
        tag.mode = ListUrlBuilder.MODE_TAG;
        assertTrue(new SubscriptionHomepageMatcher(List.of(tag)).matches(gallery()));
        tag.keyword = "language:english";
        assertFalse(new SubscriptionHomepageMatcher(List.of(tag)).matches(gallery()));
    }
    @Test public void categoryPageRangeAndExactRatingsAreAppliedPerRule() {
        QuickSearch q = bookmark("uploader:\"Alice Smith\"");
        q.category = EhConfig.MANGA;
        q.advanceSearch = ListUrlBuilder.DEFAULT_ADVANCE;
        q.pageFrom = 20;
        q.pageTo = 30;
        q.ratingOverride = true;
        q.minRatingHundredths = 451;
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(List.of(q));
        assertTrue(matcher.matches(gallery()));
        GalleryInfo gallery = gallery();
        gallery.rating = 4.50f;
        assertFalse(matcher.matches(gallery));
        gallery = gallery(); gallery.pages = 19;
        assertFalse(matcher.matches(gallery));
        gallery = gallery(); gallery.category = EhConfig.DOUJINSHI;
        assertFalse(matcher.matches(gallery));
    }
    @Test public void unsupportedSearchesFallBackWithoutRewritingBookmarks() {
        List<QuickSearch> bookmarks = List.of(bookmark("free text"), bookmark("f:anal"),
                bookmark("~l:chinese$ ~l:english$"), bookmark("title:hello"), bookmark("tag:anal$"));
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(bookmarks);
        assertTrue(matcher.isEmpty());
        assertEquals(bookmarks, matcher.fallbackBookmarks());
        assertEquals("f:anal", bookmarks.get(1).keyword);
        QuickSearch q = bookmark("l:chinese$");
        q.advanceSearch = com.hippo.ehviewer.widget.AdvanceSearchTable.SH;
        assertTrue(new SubscriptionHomepageMatcher(List.of(q)).isEmpty());
    }
    @Test public void fingerprintIgnoresOrderButChangesWithQueryAndRating() {
        QuickSearch a = bookmark("uploader:Alice");
        QuickSearch b = bookmark("uploader:Bob");
        String key = new SubscriptionHomepageMatcher(List.of(a,b)).fingerprint();
        assertEquals(key, new SubscriptionHomepageMatcher(List.of(b,a)).fingerprint());
        b.ratingOverride = true; b.minRatingHundredths = 450;
        assertNotEquals(key, new SubscriptionHomepageMatcher(List.of(a,b)).fingerprint());
    }
    @Test public void defaultCategoriesAndGlobalRatingRemainEffective() {
        Settings.putInt(Settings.KEY_DEFAULT_CATEGORIES, EhConfig.DOUJINSHI);
        QuickSearch q = bookmark("uploader:\"Alice Smith\"");
        assertFalse(new SubscriptionHomepageMatcher(List.of(q)).matches(gallery()));
        q.category = EhConfig.MANGA;
        Settings.putPersistentMinRatingHundredths(452);
        assertFalse(new SubscriptionHomepageMatcher(List.of(q)).matches(gallery()));
    }
    @Test public void negativeOnlyRulesAndSeveralOverlappingRulesProduceOneMatch() {
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(List.of(
                bookmark("-l:english$"), bookmark("uploader:\"Alice Smith\""), bookmark("l:chinese$")));
        assertTrue(matcher.matches(gallery()));
        GalleryInfo gallery = gallery(); gallery.uploader = "Bob"; gallery.simpleTags = new String[]{"language:english"};
        assertFalse(matcher.matches(gallery));
    }

    @Test public void coarseFilterRejectsKnownUploaderButKeepsMissingUploaderAndIncompleteTags() {
        SubscriptionHomepageMatcher uploader = new SubscriptionHomepageMatcher(List.of(bookmark("uploader:Bob")));
        GalleryInfo gallery = gallery();
        assertFalse(uploader.mayMatch(gallery));
        gallery.uploader = null;
        assertTrue(uploader.mayMatch(gallery));
        SubscriptionHomepageMatcher tags = new SubscriptionHomepageMatcher(List.of(bookmark("l:english$")));
        assertTrue(tags.mayMatch(gallery)); // Missing from HTML is not evidence a tag is absent.
        gallery.rating = 0;
        Settings.putPersistentMinRatingHundredths(500);
        assertTrue(tags.mayMatch(gallery)); // HTML ratings are not precise enough for rejection.
    }

    @Test public void coarseFilterPreservesUnknownShapeButRejectsKnownCategoryAndPageCount() {
        QuickSearch q = bookmark("l:chinese$");
        q.category = EhConfig.MANGA;
        q.advanceSearch = ListUrlBuilder.DEFAULT_ADVANCE;
        q.pageFrom = 30;
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(List.of(q));
        GalleryInfo gallery = gallery();
        assertFalse(matcher.mayMatch(gallery));
        gallery.pages = 0;
        assertTrue(matcher.mayMatch(gallery));
        gallery.category = EhConfig.DOUJINSHI;
        assertFalse(matcher.mayMatch(gallery));
        gallery.category = EhUtils.UNKNOWN;
        assertTrue(matcher.mayMatch(gallery));
    }

    @Test public void nativeCursorsRejectKnownMatchesBeforeMetadataAndTrackProgressPerBookmark() {
        QuickSearch alice = bookmark("uploader:\"Alice Smith\"");
        QuickSearch bob = bookmark("uploader:Bob");
        GalleryInfo known = gallery();
        known.gid = 100;
        known.posted = "2026-10-23 00:30";
        GalleryInfo arrival = gallery();
        arrival.gid = 101;
        arrival.posted = known.posted;
        GallerySeenState cursor = GallerySeenState.empty().observe(List.of(known));
        SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(List.of(alice, bob),
                java.util.Map.of(alice, cursor, bob, GallerySeenState.empty()));
        assertFalse(matcher.mayMatch(known));
        assertFalse(matcher.matches(known));
        assertTrue(matcher.mayMatch(arrival));
        assertTrue(matcher.matches(arrival));
        matcher.observe(List.of(arrival));
        // Observations cannot alter the immutable cursors halfway through a retried pass.
        assertTrue(matcher.matches(arrival));
        assertFalse(matcher.observed(List.of(alice)).isNew(arrival));
        assertTrue(matcher.observed(List.of(alice)).initialized());
        assertFalse(matcher.observed(List.of(bob)).initialized());
        arrival.uploader = "Bob";
        assertFalse(matcher.matches(arrival)); // A source without a baseline seeds silently.
    }
}
