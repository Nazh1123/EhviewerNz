package com.hippo.ehviewer.client;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.util.ArrayList;
import java.util.HashSet;
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

    @Test public void tenBrowsingPagesWriteEachNewGalleryOnceEvenWithOverlaps() {
        auditGalleryWrites();
        SearchUpdateStore.Pass browsing = pass("a");
        for (int page = 0; page < 10; page++) {
            List<GalleryInfo> rows = new ArrayList<>();
            if (page > 0) {
                long overlap = 1001 - page * 25L;
                rows.add(gallery(overlap, overlap));
            }
            for (int i = 0; i < 25; i++) {
                long gid = 1000 - page * 25L - i;
                rows.add(gallery(gid, gid));
            }
            browsing.observe(rows, rows, rows.get(rows.size() - 1).posted, page < 9);
            store.commit(browsing, true);
            assertEquals("Rows written after page " + (page + 1), (page + 1) * 25, galleryWrites().size());
        }
        var written = galleryWrites();
        assertEquals(250, new HashSet<>(written).size());
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT count(*) FROM galleries", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(250, cursor.getInt(0));
        }
        assertTrue(read("a").complete);
        assertTrue(read("a").exhausted);
    }

    @Test public void repeatedGalleryIsWrittenWhenItBecomesEligible() {
        auditGalleryWrites();
        SearchUpdateStore.Pass browsing = pass("a");
        GalleryInfo laterMatch = gallery(100, 100);
        browsing.observe(List.of(laterMatch), List.of(), laterMatch.posted, true);
        store.commit(browsing, true);
        assertEquals(List.of(100L), galleryWrites());

        browsing.observe(List.of(laterMatch), List.of(laterMatch), laterMatch.posted, false);
        store.commit(browsing, true);
        assertEquals(List.of(100L, 100L), galleryWrites());
        assertFalse(read("a").isNew(laterMatch));
        assertFalse(read("a").isUnconfirmed(laterMatch));
    }

    @Test public void completedBrowsingReconcilesOnlyMissingRowsInItsCoverage() {
        SearchUpdateStore.Pass initial = pass("a");
        List<GalleryInfo> head = List.of(gallery(120, 120), gallery(110, 110), gallery(100, 100));
        initial.observe(head, head, head.get(2).posted, true);
        List<GalleryInfo> tail = List.of(gallery(90, 90), gallery(80, 80));
        initial.observe(tail, tail, tail.get(1).posted, false);
        store.commit(initial, false);

        SearchUpdateStore.Pass browsing = pass("a");
        browsing.observe(head.subList(0, 2), head.subList(0, 2), head.get(1).posted, true);
        store.commit(browsing, true);
        assertFalse(browsing.complete());
        auditGalleryWrites();

        List<GalleryInfo> next = List.of(gallery(97, 97), gallery(95, 95));
        browsing.observe(next, next, next.get(1).posted, true);
        store.commit(browsing, true);
        assertTrue(read("a").complete);
        assertFalse(read("a").exhausted);
        List<Long> written = galleryWrites();
        assertEquals(3, written.size());
        assertEquals(Set.of(100L, 97L, 95L), new HashSet<>(written));
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT gid,active,snapshot FROM galleries WHERE gid IN (80,90,100) ORDER BY gid", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(80, cursor.getLong(0));
            assertEquals(1, cursor.getInt(1));
            assertTrue(cursor.moveToNext());
            assertEquals(90, cursor.getLong(0));
            assertEquals(1, cursor.getInt(1));
            assertTrue(cursor.moveToNext());
            assertEquals(100, cursor.getLong(0));
            assertEquals(0, cursor.getInt(1));
            assertEquals(0, cursor.getLong(2));
        }
    }

    @Test public void nextQueuedPageRetriesRowsFromARolledBackWrite() {
        SearchUpdateStore.Pass browsing = pass("a");
        List<GalleryInfo> first = List.of(gallery(100, 100));
        browsing.observe(first, first, first.get(0).posted, true);
        SearchUpdateStore.Pass firstWrite = browsing.snapshotForCommit();
        List<GalleryInfo> next = List.of(gallery(90, 90));
        browsing.observe(next, next, next.get(0).posted, false);
        SearchUpdateStore.Pass nextWrite = browsing.snapshotForCommit();

        var db = store.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_gallery BEFORE INSERT ON galleries WHEN NEW.gid=100 "
                + "BEGIN SELECT RAISE(ABORT, 'test rollback'); END");
        assertThrows(SQLiteException.class, () -> store.commitCapturedPage(firstWrite));
        assertFalse(read("a").initialized);
        db.execSQL("DROP TRIGGER reject_gallery");

        auditGalleryWrites();
        store.commitCapturedPage(nextWrite);
        store.commitCapturedPage(firstWrite); // Retrying an older, already included page is harmless.
        assertEquals(2, galleryWrites().size());
        assertEquals(Set.of(100L, 90L), new HashSet<>(galleryWrites()));
        assertEquals(2, read("a").displayWatermark().retainedCount());
        assertTrue(read("a").complete);
    }

    private void auditGalleryWrites() {
        var db = store.getWritableDatabase();
        db.execSQL("CREATE TABLE gallery_writes (gid INTEGER NOT NULL)");
        db.execSQL("CREATE TRIGGER gallery_insert AFTER INSERT ON galleries "
                + "BEGIN INSERT INTO gallery_writes VALUES (NEW.gid); END");
        db.execSQL("CREATE TRIGGER gallery_update AFTER UPDATE ON galleries "
                + "BEGIN INSERT INTO gallery_writes VALUES (NEW.gid); END");
    }

    private List<Long> galleryWrites() {
        List<Long> gids = new ArrayList<>();
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT gid FROM gallery_writes ORDER BY rowid", null)) {
            while (cursor.moveToNext()) gids.add(cursor.getLong(0));
        }
        return gids;
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
    @Test public void laterPagesOfAnOlderBrowseCannotDeactivateANewerUnreadGallery() {
        seed("a");
        SearchUpdateStore.Pass browsing = pass("a");
        List<GalleryInfo> first = List.of(gallery(110, 110), gallery(100, 100));
        browsing.observe(first, first, first.get(1).posted, true);
        store.commit(browsing, true);

        store.commit(page("a", List.of(gallery(120, 120), gallery(110, 110), gallery(90, 90))), false);
        long checked = read("a").checked;
        List<GalleryInfo> next = List.of(gallery(95, 95));
        browsing.observe(next, next, next.get(0).posted, true);
        store.commit(browsing, true);
        List<GalleryInfo> last = List.of(gallery(90, 90));
        browsing.observe(last, last, last.get(0).posted, false);
        store.commit(browsing, true);

        assertEquals(checked, read("a").checked);
        assertEquals(Set.of(120L), read("a").unread());
        assertEquals(Set.of(120L), pass("a").queryDisplayNewGids());
    }

    @Test public void queriesHaveIndependentProgressButExplicitConfirmationDeduplicatesIdentity() {
        seed("a");seed("b");
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        assertEquals(1,read("b").version);
        assertFalse(page("b",List.of(gallery(110,110),gallery(90,90))).newGids().contains(110L));
        assertTrue(read("b").isNew(gallery(105,105)));
    }

    @Test public void anotherQueryConfirmationDoesNotMoveTheLocalViewedBoundary() {
        seed("a");seed("b");
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        SearchUpdateStore.Pass browsing=page("b",List.of(gallery(120,120),gallery(110,110),gallery(90,90)));
        assertEquals(Set.of(120L),browsing.newGids()); // Notifications still deduplicate 110.
        assertEquals(Set.of(120L,110L),browsing.queryDisplayNewGids());
        store.commit(browsing,true);
        assertTrue(page("b",List.of(gallery(120,120),gallery(110,110),gallery(90,90)))
                .queryDisplayNewGids().isEmpty());
    }

    @Test public void backgroundCheckKeepsQueryLocalUpdatesEvenWhenAnotherQueryConfirmedThem() {
        seed("a");seed("b");
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        store.commit(page("b",List.of(gallery(110,110),gallery(90,90))),false);
        assertTrue(read("b").unread().isEmpty());
        store.close();store=new SearchUpdateStore(RuntimeEnvironment.getApplication());
        SearchUpdateStore.Pass browsing=page("b",List.of(gallery(110,110),gallery(90,90)));
        assertTrue(browsing.displayNewGids().isEmpty());
        assertEquals(Set.of(110L),browsing.queryDisplayNewGids());
        store.commit(browsing,true);
        assertTrue(page("b",List.of(gallery(110,110),gallery(90,90))).queryDisplayNewGids().isEmpty());
    }

    @Test public void laterCrossQueryConfirmationCannotClearCapturedLocalUpdates() {
        seed("a");seed("b");
        store.commit(page("b",List.of(gallery(110,110),gallery(90,90))),false);
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        assertTrue(read("b").unread().isEmpty());
        assertEquals(Set.of(110L),page("b",List.of(gallery(110,110),gallery(90,90))).queryDisplayNewGids());
    }

    @Test public void staleBrowsingCannotConsumeNewerLocalUpdates() {
        seed("a");
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),false);
        SearchUpdateStore.Pass browsing=page("a",List.of(gallery(110,110)));
        store.commit(page("a",List.of(gallery(120,120),gallery(110,110),gallery(90,90))),false);
        store.commit(browsing,true);
        assertEquals(Set.of(120L),page("a",List.of(gallery(120,120),gallery(110,110),gallery(90,90)))
                .queryDisplayNewGids());
    }

    @Test public void upgradeRecoversOnlyTheQueryLocalViewedFrontier() {
        seed("a");seed("b");
        store.commit(page("b",List.of(gallery(110,110),gallery(90,90))),false);
        store.commit(page("a",List.of(gallery(110,110),gallery(90,90))),true);
        // Rebuild the galleries table as version 1, retaining real checkpoint/acknowledgement data.
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("ALTER TABLE galleries RENAME TO galleries_v2");
        db.execSQL("CREATE TABLE galleries (query_id TEXT NOT NULL, gid INTEGER NOT NULL, "
                + "posted INTEGER NOT NULL, qualified INTEGER NOT NULL DEFAULT 0, "
                + "active INTEGER NOT NULL DEFAULT 0, detected INTEGER NOT NULL DEFAULT 0, "
                + "acknowledged INTEGER NOT NULL DEFAULT 0, snapshot INTEGER NOT NULL DEFAULT 0, "
                + "touched INTEGER NOT NULL, PRIMARY KEY(query_id,gid)) WITHOUT ROWID");
        db.execSQL("INSERT INTO galleries SELECT query_id,gid,posted,qualified,active,detected,"
                + "acknowledged,snapshot,touched FROM galleries_v2");
        db.execSQL("DROP TABLE galleries_v2");
        db.execSQL("CREATE INDEX galleries_identity ON galleries(gid,acknowledged)");
        db.setVersion(1);
        store.close();store=new SearchUpdateStore(RuntimeEnvironment.getApplication());
        assertEquals(2,store.getReadableDatabase().getVersion());
        assertTrue(page("a",List.of(gallery(110,110),gallery(90,90))).queryDisplayNewGids().isEmpty());
        assertEquals(Set.of(110L),page("b",List.of(gallery(110,110),gallery(90,90))).queryDisplayNewGids());
        assertTrue(read("b").unread().isEmpty());
        store.commit(page("b",List.of(gallery(120,120),gallery(110,110),gallery(90,90))),true);
        assertTrue(page("b",List.of(gallery(120,120),gallery(110,110),gallery(90,90)))
                .queryDisplayNewGids().isEmpty());
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

    @Test public void sharedConfirmationCannotEvictUpdatesStillUnviewedInAnotherQuery() {
        seed("a");seed("b");
        List<GalleryInfo> rows=new ArrayList<>();
        for(int i=0;i<1100;i++)rows.add(gallery(10000+i,3000-i));
        store.commit(page("a",rows),true);
        SearchUpdateStore.Pass check=pass("b");
        check.observe(rows.subList(0,25),rows.subList(0,25),rows.get(24).posted,true);
        check.observe(rows.subList(25,rows.size()),rows.subList(25,rows.size()),rows.get(1099).posted,false);
        store.commit(check,false);
        SearchUpdateStore.Pass next=pass("b");
        next.observe(rows.subList(0,25),rows.subList(0,25),rows.get(24).posted,true);
        next.observe(rows.subList(25,26),rows.subList(25,26),rows.get(25).posted,true);
        store.commit(next,false);
        assertTrue(read("b").unread().isEmpty());
        assertEquals(1100,pass("b").queryDisplayNewGids().size());
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
