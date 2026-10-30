package com.hippo.ehviewer.client;

import android.app.Application;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;
import static com.hippo.ehviewer.client.GallerySeenStateTest.gallery;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=28,shadows={SearchUpdateSessionTest.ContextShadow.class,SearchUpdateSessionTest.DbShadow.class})
public class SearchUpdateSessionTest {
    private static final List<QuickSearch> bookmarks=new ArrayList<>();
    private static String scope;
    private QuickSearch a,b;
    @Before public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        RuntimeEnvironment.getApplication().deleteDatabase(SearchUpdateStore.DATABASE);
        SearchUpdateStore.initialize(RuntimeEnvironment.getApplication());
        bookmarks.clear();scope="account/site";
        a=bookmark("uploader:alice");b=bookmark("uploader:bob");
        seed(a);seed(b);
    }
    private QuickSearch bookmark(String keyword) {
        ListUrlBuilder builder=new ListUrlBuilder();builder.setKeyword(keyword);
        QuickSearch q=builder.toQuickSearch();q.subscribed=true;bookmarks.add(q);return q;
    }
    private String key(QuickSearch q){ListUrlBuilder b=new ListUrlBuilder();b.set(q);return SearchUpdateStore.queryKey(b);}
    private void seed(QuickSearch q) {
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            var p=new SearchUpdateStore.Pass(store.read(scope,key(q)));
            var rows=List.of(gallery(100,100),gallery(90,90));
            p.observe(rows,rows,rows.get(1).posted,false);store.commit(p,false);
        }
    }
    private GalleryInfo item(long gid,int minute,String uploader){GalleryInfo g=gallery(gid,minute);g.uploader=uploader;return g;}
    private SearchUpdateSession merged(){var plans=BookmarkSubscriptionPlanner.planExact(bookmarks);assertEquals(1,plans.size());return new SearchUpdateSession(plans.get(0).createBuilder(),plans.get(0));}
    private GalleryListParser.Result result(GalleryInfo...rows){var r=new GalleryListParser.Result();r.galleryInfoList=List.of(rows);r.rawGalleries=r.galleryInfoList;r.rawTailPosted=rows[rows.length-1].posted;return r;}

    @Test public void mergedRequestKeepsIndependentWindowsAndLaterIndividualBrowsingSuppressesOnlyConfirmedGids() {
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            var p=new SearchUpdateStore.Pass(store.read(scope,key(a)));
            var rows=List.of(item(200,200,"alice"),item(100,100,"alice"),item(90,90,"alice"));
            p.observe(rows,rows,rows.get(2).posted,false);store.commit(p,true);
        }
        SearchUpdateSession s=merged();
        s.observe(result(item(200,200,"alice"),item(150,150,"bob"),item(90,90,"bob")),false);
        assertEquals(Set.of(150L),s.newGids());s.commit(false);
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            assertTrue(store.read(scope,key(a)).unread().isEmpty());
            assertEquals(Set.of(150L),store.read(scope,key(b)).unread());
        }
    }
    @Test public void rawButFilteredRowsAreNotMistakenlyQualifiedOrAssignedToEveryChild() {
        SearchUpdateSession s=merged();
        GalleryInfo x=item(95,95,"alice"),y=item(96,96,"bob");
        var r=result(item(100,100,"alice"),x,y,item(90,90,"bob"));
        r.galleryInfoList=List.of(y);s.observe(r,false);s.commit(false);
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            assertTrue(store.read(scope,key(a)).isNew(x));
            assertEquals(Set.of(96L),store.read(scope,key(b)).unread());
            assertTrue(store.read(scope,key(a)).unread().isEmpty());
        }
    }
    @Test public void editedOrUnsubscribedConditionCannotCommitAnInFlightOldDefinition() {
        String oldKey=key(a);SearchUpdateSession s=merged();
        s.observe(result(item(110,110,"alice"),item(95,95,"bob"),item(90,90,"bob")),false);
        a.keyword="uploader:changed";s.commit(false);
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            assertEquals(1,store.read(scope,oldKey).version);
            assertFalse(store.read(scope,key(a)).initialized);
            assertEquals(Set.of(95L),store.read(scope,key(b)).unread());
        }
    }
    @Test public void changedScopeCannotCommitOrAcknowledgeCapturedUpdates() {
        SearchUpdateSession s=merged();s.observe(result(item(110,110,"alice"),item(90,90,"bob")),false);
        scope="another-account/site";s.commit(true);
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            assertFalse(store.read(scope,key(a)).initialized);
            assertEquals(1,store.read("account/site",key(a)).version);
        }
    }
    @Test public void ambiguousTagUnionsAreNotUsedByPersistentQueryState() {
        bookmarks.clear();bookmark("artist:alice");bookmark("artist:bob");
        assertEquals(1,BookmarkSubscriptionPlanner.plan(bookmarks).size());
        var exact=BookmarkSubscriptionPlanner.planExact(bookmarks);
        assertEquals(2,exact.size());assertTrue(exact.stream().noneMatch(BookmarkSubscriptionPlanner.Source::requiresVerification));
    }
    @Test public void emptyChildAtMergedEofCanDetectLaterOlderMatchingGallery() {
        RuntimeEnvironment.getApplication().deleteDatabase(SearchUpdateStore.DATABASE);
        SearchUpdateSession s=merged();
        s.observe(result(item(200,200,"bob"),item(90,90,"bob")),false);s.commit(false);
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            assertEquals(0,store.read(scope,key(a)).floor);
        }
        SearchUpdateSession next=merged();
        next.observe(result(item(200,200,"bob"),item(5,5,"alice")),false);
        assertEquals(Set.of(5L),next.newGids());
    }

    @Test public void nativeRatingConditionsRemainDistinctWhenPreciseControlIsDisabled() {
        Settings.putBoolean(Settings.KEY_ENABLE_PRECISE_RATING_FILTER,false);
        ListUrlBuilder first=new ListUrlBuilder();first.setKeyword("same");first.setAdvanceSearch(0);first.setMinRating(3);
        ListUrlBuilder second=first.clone();second.setMinRating(4);
        assertNotEquals(SearchUpdateStore.queryKey(first),SearchUpdateStore.queryKey(second));
    }

    @Test public void subscriptionRatingOverridesDoNotAcknowledgeTheDefaultQueryAsAWhole() {
        ListUrlBuilder builder=new ListUrlBuilder();builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
        String normal=SearchUpdateStore.queryKey(builder);
        builder.setMinRatingHundredths(450);
        assertNotEquals(normal,SearchUpdateStore.queryKey(builder));
    }

    @Test public void apiEnrichmentCannotMoveCandidateOutsideItsHtmlPublicationWindow() {
        SearchUpdateSession s=merged();
        GalleryInfo x=item(95,1,"alice"),tail=item(90,1,"bob");
        var page=result(x,tail);
        page.rawPublicationTimes.put(95L,gallery(95,95).posted);
        page.rawPublicationTimes.put(90L,gallery(90,90).posted);
        page.rawTailPosted=gallery(90,90).posted;
        s.observe(page,false);
        assertEquals(Set.of(95L),s.newGids());s.commit(false);
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            assertEquals(Set.of(95L),store.read(scope,key(a)).unread());
        }
    }

    @Test public void emptyQueriesDoNotForcePopulatedMergedSourcesToScanAllHistory() {
        try(SearchUpdateStore store=SearchUpdateStore.open()) {
            var p=new SearchUpdateStore.Pass(store.read(scope,key(a)));
            p.observe(List.of(),List.of(),null,false);store.commit(p,false);
        }
        assertEquals(2,SearchUpdateSession.plan(bookmarks).size());
        QuickSearch duplicate=bookmark("uploader:alice");
        assertEquals(2,SearchUpdateSession.plan(bookmarks).size()); // identical empty queries still share one request
    }

    @Implements(SubscriptionSearchContext.class) public static class ContextShadow {
        @Implementation protected static String key(){return scope;}
    }
    @Implements(EhDB.class) public static class DbShadow {
        @Implementation protected static List<QuickSearch> getSubscribedQuickSearch(){return bookmarks.stream().filter(q->q.subscribed).toList();}
    }
}
