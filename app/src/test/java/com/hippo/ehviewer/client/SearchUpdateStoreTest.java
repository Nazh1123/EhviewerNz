package com.hippo.ehviewer.client;

import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;
import static com.hippo.ehviewer.client.GallerySeenStateTest.gallery;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class SearchUpdateStoreTest {
    private SearchUpdateStore store;
    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(SearchUpdateStore.DATABASE);
        SearchUpdateStore.initialize(context);
        store = new SearchUpdateStore(context);
    }
    @After public void tearDown() { store.close(); }
    private SearchUpdateStore.Snapshot read(String q) { return store.read("account/site", q); }
    private SearchUpdateStore.Pass pass(String q) { return new SearchUpdateStore.Pass(read(q)); }
    private SearchUpdateStore.Pass page(String q, List<GalleryInfo> rows) {
        SearchUpdateStore.Pass p = pass(q);
        p.observe(rows, rows, rows.isEmpty() ? null : rows.get(rows.size()-1).posted, false);
        return p;
    }
    private void seed(String q) { store.commit(page(q, List.of(gallery(100,100),gallery(90,90))), false); }

    @Test public void checksAdvanceWithoutAcknowledgingAndBrowsingConfirmsWholeCapturedBatch() {
        seed("a");
        SearchUpdateStore.Pass check = page("a",List.of(gallery(110,110),gallery(95,95),gallery(90,90)));
        assertEquals(Set.of(110L,95L),check.newGids());
        store.commit(check,false);
        assertEquals(0,read("a").viewed);
        assertEquals(Set.of(110L,95L),read("a").unread());
        SearchUpdateStore.Pass browse = page("a",List.of(gallery(110,110)));
        store.commit(browse,true);
        assertTrue(read("a").unread().isEmpty()); // 95 was not displayed, overall acknowledgement.
        assertTrue(read("a").viewed>0);
    }
    @Test public void partialBrowsingHasItsOwnFrontierWithoutAdvancingCompletedCheck() {
        seed("a");long checked=read("a").checked;
        SearchUpdateStore.Pass p=pass("a");
        p.observe(List.of(gallery(110,110)),List.of(gallery(110,110)),gallery(110,110).posted,true);
        assertFalse(p.complete());store.commit(p,true);
        assertEquals(checked,read("a").checked);
        assertTrue(read("a").viewed>checked);
        assertTrue(read("a").unread().isEmpty());
        assertEquals(GallerySeenState.postedMinute(gallery(100,100).posted),read("a").head);
    }

    @Test public void lateTagWithinCoverageIsNewButOutsideCoverageIsNot() {
        seed("a");
        assertTrue(read("a").isNew(gallery(99,95)));
        assertFalse(read("a").isNew(gallery(999,89)));
    }
    @Test public void qualifiedHistoryPreventsTagRemovalAndReadditionDuplicates() {
        seed("a");
        store.commit(page("a",List.of(gallery(95,95),gallery(100,100),gallery(90,90))),false);
        store.commit(page("a",List.of(gallery(100,100),gallery(90,90))),false);
        assertTrue(read("a").unread().isEmpty());
        SearchUpdateStore.Pass returning = page("a",List.of(gallery(95,95),gallery(100,100),gallery(90,90)));
        assertTrue(returning.newGids().isEmpty());
        store.commit(returning,false);
        assertEquals(Set.of(95L),read("a").unread());
    }
    @Test public void observingRawFilteredGalleryDoesNotConsumeFutureEligibility() {
        SearchUpdateStore.Pass p = pass("a");
        p.observe(List.of(gallery(100,100),gallery(95,95),gallery(90,90)),
                List.of(gallery(100,100),gallery(90,90)),gallery(90,90).posted,false);
        store.commit(p,false);
        assertTrue(read("a").isNew(gallery(95,95)));
        store.commit(page("a",List.of(gallery(100,100),gallery(95,95),gallery(90,90))),false);
        assertEquals(Set.of(95L),read("a").unread());
    }
    @Test public void allSameMinuteIdsSurvivePageBoundary() {
        List<GalleryInfo> first = new ArrayList<>(), second = new ArrayList<>();
        for(int i=1;i<=25;i++)first.add(gallery(i,100));
        for(int i=26;i<=35;i++)second.add(gallery(i,100));
        second.add(gallery(50,99));
        SearchUpdateStore.Pass p = pass("a");
        p.observe(first,first,gallery(25,100).posted,true);
        assertFalse(p.complete());
        p.observe(second,second,gallery(50,99).posted,true);
        assertTrue(p.complete());
        store.commit(p,false);
        for(int i=1;i<=35;i++)assertFalse(read("a").isNew(gallery(i,100)));
        assertTrue(read("a").isNew(gallery(36,100)));
    }
    @Test public void fullPagesOf25_50_100AreRetainedInsteadOfSubsampled() {
        for(int size:new int[]{25,50,100}) {
            String q="q"+size;
            List<GalleryInfo> rows=new ArrayList<>();
            for(int i=0;i<size;i++)rows.add(gallery(1000+i,500-i));
            store.commit(page(q,rows),false);
            for(GalleryInfo g:rows)assertFalse(read(q).isNew(g));
            assertTrue(read(q).isNew(gallery(9999,500-size+1)));
        }
    }
    @Test public void pageSizeExpansionSeedsPreviouslyUncoveredHistory() {
        seed("a");
        SearchUpdateStore.Pass bigger=page("a",List.of(gallery(100,100),gallery(90,90),gallery(70,70)));
        assertFalse(bigger.newGids().contains(70L));
        store.commit(bigger,false);
        assertTrue(read("a").isNew(gallery(75,75)));
        assertFalse(read("a").isNew(gallery(70,70)));
    }
    @Test public void unknownDatesAreRememberedAndDoNotRepeat() {
        seed("a"); GalleryInfo unknown=new GalleryInfo();unknown.gid=77;
        SearchUpdateStore.Pass p=page("a",List.of(gallery(100,100),unknown,gallery(90,90)));
        assertTrue(p.newGids().contains(77L));store.commit(p,false);
        assertFalse(read("a").isNew(unknown));
    }
    @Test public void initializedEmptySearchDetectsLaterOldMatchingResults() {
        store.commit(page("a",List.of()),false);
        assertTrue(read("a").initialized);
        SearchUpdateStore.Pass p=pass("a");
        p.observe(List.of(gallery(5,5)),List.of(gallery(5,5)),gallery(5,5).posted,true);
        assertFalse(p.complete());
        p.observe(List.of(gallery(4,4)),List.of(gallery(4,4)),gallery(4,4).posted,false);
        assertEquals(Set.of(5L,4L),p.newGids());
    }
    @Test public void incompleteCheckDoesNotCommitOrLoseDiscoveries() {
        seed("a");long version=read("a").version;
        SearchUpdateStore.Pass p=pass("a");
        p.observe(List.of(gallery(110,110)),List.of(gallery(110,110)),gallery(110,110).posted,true);
        assertThrows(IllegalStateException.class,()->store.commit(p,false));
        assertEquals(version,read("a").version);
        assertTrue(read("a").isNew(gallery(110,110)));
    }
    @Test public void browsingDoesNotConfirmNewerConcurrentChecks() {
        seed("a");
        store.commit(page("a",List.of(gallery(110,110),gallery(100,100),gallery(90,90))),false);
        SearchUpdateStore.Pass browsing=page("a",List.of(gallery(110,110)));
        store.commit(page("a",List.of(gallery(120,120),gallery(110,110),gallery(90,90))),false);
        store.commit(browsing,true);
        assertEquals(Set.of(120L),read("a").unread());
        assertEquals(GallerySeenState.postedMinute(gallery(120,120).posted),read("a").head);
    }
    @Test public void queriesHaveIndependentProgressButExplicitConfirmationDeduplicatesIdentity() {
        seed("a");seed("b");
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        assertEquals(1,read("b").version);
        assertFalse(page("b",List.of(gallery(110,110),gallery(90,90))).newGids().contains(110L));
        assertTrue(read("b").isNew(gallery(105,105)));
    }
    @Test public void accountAndSiteScopesDoNotShareConfirmation() {
        seed("a");store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        assertFalse(store.read("other-account/site","a").initialized);
        assertFalse(store.read("account/other-site","a").initialized);
    }
    @Test public void repeatedChecksDoNotAccumulateBatchRowsAndCapConfirmedHistory() {
        seed("a");
        for(int step=0;step<5;step++) {
            List<GalleryInfo> rows=new ArrayList<>();
            for(int i=0;i<300;i++)rows.add(gallery(10000+step*300+i,1000+step*300+i));
            store.commit(page("a",rows),true);
        }
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT count(*) FROM galleries WHERE snapshot=0",null)) {
            assertTrue(c.moveToFirst());assertTrue(c.getInt(0)<=1000);
        }
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM queries",null)) {
            c.moveToFirst();assertEquals(1,c.getInt(0));
        }
    }
    @Test public void unreadOutsideCurrentSnapshotIsNeverEvictedByHistoricalLimit() {
        seed("a");
        List<GalleryInfo> rows=new ArrayList<>();
        for(int i=0;i<2000;i++)rows.add(gallery(10000+i,3000-i));
        SearchUpdateStore.Pass p=pass("a");
        p.observe(rows.subList(0,25),rows.subList(0,25),rows.get(24).posted,true);
        p.observe(rows.subList(25,rows.size()),rows.subList(25,rows.size()),rows.get(1999).posted,false);
        store.commit(p,false);
        assertEquals(2000,read("a").unread().size());
        SearchUpdateStore.Pass next=pass("a");
        next.observe(rows.subList(0,25),rows.subList(0,25),rows.get(24).posted,true);
        next.observe(rows.subList(25,26),rows.subList(25,26),rows.get(25).posted,true);
        store.commit(next,false);
        assertEquals(2000,read("a").unread().size());
        assertEquals(2000,store.unread("account/site",List.of("a")).size());
    }

    @Test public void absenceOutsideSuccessfullyScannedWindowDoesNotClearUnread() {
        seed("a");
        store.commit(page("a",List.of(gallery(110,110),gallery(95,95),gallery(90,90))),false);
        SearchUpdateStore.Pass p=pass("a");
        p.observe(List.of(gallery(110,110)),List.of(gallery(110,110)),gallery(110,110).posted,true);
        store.commit(p,true);
        // This is overall browsing confirmation, but an incomplete background pass cannot clear 95.
        // Use a separate query to verify absence correction only covers the scanned time interval.
        seed("b");
        store.commit(page("b",List.of(gallery(120,120),gallery(96,96),gallery(90,90))),false);
        SearchUpdateStore.Pass newer=pass("b");
        newer.observe(List.of(gallery(120,120)),List.of(gallery(120,120)),gallery(120,120).posted,true);
        assertThrows(IllegalStateException.class,()->store.commit(newer,false));
        assertEquals(Set.of(120L,96L),read("b").unread());
    }

    @Test public void restoredOlderUnreadAfterCaptureIsNotAcknowledgedByUnrelatedBrowsing() {
        seed("a");
        store.commit(page("a",List.of(gallery(100,100),gallery(95,95),gallery(90,90))),false);
        store.commit(page("a",List.of(gallery(100,100),gallery(90,90))),false);
        assertTrue(read("a").unread().isEmpty());
        SearchUpdateStore.Pass browsing=page("a",List.of(gallery(100,100)));
        store.commit(page("a",List.of(gallery(100,100),gallery(95,95),gallery(90,90))),false);
        store.commit(browsing,true);
        assertEquals(Set.of(95L),read("a").unread());
    }

    @Test public void inactiveUnconfirmedHistoryIsBoundedEvenIfRemovedItemsNeverReturn() {
        seed("a");List<GalleryInfo> rows=new ArrayList<>();
        for(int i=0;i<1500;i++)rows.add(gallery(10000+i,3000-i));
        store.commit(page("a",rows),false);
        assertEquals(1500,read("a").unread().size());
        store.commit(page("a",List.of()),false);
        assertTrue(read("a").unread().isEmpty());
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM galleries",null)) {
            c.moveToFirst();assertTrue(c.getInt(0)<=1000);
        }
    }

    @Test public void persistedProgressAndUnreadSurviveReopening() {
        seed("a");store.commit(page("a",List.of(gallery(95,95),gallery(100,100),gallery(90,90))),false);
        store.close();store=new SearchUpdateStore(RuntimeEnvironment.getApplication());
        assertEquals(Set.of(95L),read("a").unread());
        assertFalse(read("a").isNew(gallery(95,95)));
    }
    @Test public void legacyUnreadMigrationIsIdempotentAndSurvivesBaselineSeeding() {
        store.migrateUnread("account/site","bookmark",Set.of(55L));
        store.migrateUnread("account/site","bookmark",Set.of(66L));
        assertEquals(Set.of(55L),read("legacy-unread:bookmark").unread());
        seed("a");assertEquals(Set.of(55L),read("legacy-unread:bookmark").unread());
        store.acknowledgeLegacy("account/site","bookmark");
        assertTrue(read("legacy-unread:bookmark").unread().isEmpty());
    }
}
