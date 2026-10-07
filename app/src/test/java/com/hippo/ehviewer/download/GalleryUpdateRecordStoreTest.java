package com.hippo.ehviewer.download;

import android.app.Application;
import android.content.Context;

import com.hippo.ehviewer.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class GalleryUpdateRecordStoreTest {
    private Context context;
    private GalleryUpdateRecordStore store;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("gallery_update_records.db");
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("update-record-tests", Context.MODE_PRIVATE));
        Settings.putIntToStr(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT, 500);
        store = new GalleryUpdateRecordStore(context);
    }

    @After public void close() { store.close(); }

    private void stage(long gid, long source) {
        GalleryUpdateManager.UpdatePlan plan = new GalleryUpdateManager.UpdatePlan(gid, source, List.of(source));
        assertTrue(store.stage(plan, () -> new GalleryUpdateRecord(gid, source, 0,
                2, 5, true, new int[]{1, 3, 4}, new int[0], 0)));
    }

    @Test public void draftIsInvisibleAndSurvivesReopeningBeforeParentCleanupRetry() {
        stage(200, 100);
        assertNull(store.find(200));
        store.close();
        store = new GalleryUpdateRecordStore(context);
        GalleryUpdateManager.UpdatePlan plan = new GalleryUpdateManager.UpdatePlan(200, 100, List.of(100L));
        assertTrue(store.stage(plan, () -> { throw new AssertionError("Old directory was already deleted"); }));
        assertTrue(store.complete(200, 100));
        assertArrayEquals(new int[]{1, 3, 4}, store.find(200).addedPages);
    }

    @Test public void historyGroupsByFirstGidAndIncludesConnectedLegacyRecords() {
        stage(200, 100); assertTrue(store.complete(200, 100));
        GalleryUpdateManager.UpdatePlan plan = new GalleryUpdateManager.UpdatePlan(300, 200, List.of(200L));
        assertTrue(store.stage(plan, () -> new GalleryUpdateRecord(300, 200, 0,
                1, 2, true, new int[]{1}, new int[0], 0).withFirstGid(50)));
        assertTrue(store.complete(300, 200));
        // An update can skip local versions; first_gid still joins its history.
        plan = new GalleryUpdateManager.UpdatePlan(500, 450, List.of(450L));
        assertTrue(store.stage(plan, () -> new GalleryUpdateRecord(500, 450, 0,
                1, 2, true, new int[]{1}, new int[0], 0).withFirstGid(50)));
        assertTrue(store.complete(500, 450));
        stage(900, 800); assertTrue(store.complete(900, 800));
        for (long gid : new long[]{200, 300, 500, 900}) {
            store.getWritableDatabase().execSQL("UPDATE records SET completed_at=? WHERE gid=?",
                    new Object[]{gid, gid});
        }
        assertEquals(List.of(500L, 300L, 200L), store.findHistory(500, 50).stream()
                .map(record -> record.targetGid).toList());
        assertEquals(50, store.find(500).firstGid);
        stage(600, 500); assertTrue(store.complete(600, 500));
        assertEquals(50, store.find(600).firstGid);
        assertEquals(List.of(600L, 500L, 300L, 200L), store.findHistory(300, 0).stream()
                .map(record -> record.targetGid).toList());
    }

    @Test public void databaseUpgradeKeepsOriginalLogsAndResumePositions() throws Exception {
        store.close();
        context.deleteDatabase("gallery_update_records.db");
        GalleryUpdateRecord legacy = new GalleryUpdateRecord(200, 100, 123,
                2, 5, true, new int[]{1, 3, 4}, new int[0], 2);
        try (android.database.sqlite.SQLiteDatabase db = context.openOrCreateDatabase(
                "gallery_update_records.db", Context.MODE_PRIVATE, null)) {
            for (String table : new String[]{"records", "pending"}) {
                db.execSQL("CREATE TABLE " + table + " (gid INTEGER PRIMARY KEY, source_gid INTEGER NOT NULL, "
                        + "completed_at INTEGER NOT NULL, reading_page INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL)");
            }
            db.execSQL("INSERT INTO records VALUES(200,100,123,2,?)", new Object[]{legacy.toJson()});
            db.setVersion(1);
        }
        store = new GalleryUpdateRecordStore(context);
        assertEquals(2, store.getReadableDatabase().getVersion());
        assertEquals(123, store.find(200).completedAt);
        assertEquals(2, store.find(200).readingPage);
        assertEquals(0, store.find(200).firstGid);
        assertArrayEquals(new int[]{1, 3, 4}, store.find(200).addedPages);
        assertEquals(1, store.findHistory(200, 0).size());
    }

    @Test public void successfulRetryDoesNotResetTimeOrIndependentReadingProgress() {
        stage(200, 100);
        assertTrue(store.complete(200, 100));
        long time = store.find(200).completedAt;
        store.saveReadingPage(200, time, 1);
        assertTrue(store.complete(200, 100));
        assertEquals(time, store.find(200).completedAt);
        assertEquals(1, store.find(200).readingPage);
        store.discardPending(200);
        assertNotNull(store.find(200));
    }

    @Test public void eachGidHasOneRecordAndRelatedVersionsRemainIndependent() {
        stage(200, 100); assertTrue(store.complete(200, 100));
        stage(300, 200); assertTrue(store.complete(300, 200));
        assertNotNull(store.find(200));
        assertNotNull(store.find(300));
        stage(200, 50); assertTrue(store.complete(200, 50));
        assertEquals(50, store.find(200).sourceGid);
        try (android.database.Cursor c = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM records", null)) {
            assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0));
        }
    }

    @Test public void capEvictsOldestCompletionAndReadingDoesNotRefreshRecency() {
        Settings.putIntToStr(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT, 100);
        for (long gid = 1; gid <= 101; gid++) {
            stage(gid, 1000 + gid);
            assertTrue(store.complete(gid, 1000 + gid));
            store.getWritableDatabase().execSQL("UPDATE records SET completed_at=? WHERE gid=?",
                    new Object[]{gid, gid});
        }
        assertNull(store.find(1));
        assertNotNull(store.find(2));
        store.saveReadingPage(2, 2, 0);
        assertEquals(2, store.find(2).completedAt);
        store.trim(50);
        assertNull(store.find(51));
        assertNotNull(store.find(52));
    }

    @Test public void failedOrCancelledDraftDoesNotReplaceSuccessfulLog() {
        stage(200, 100); assertTrue(store.complete(200, 100));
        stage(200, 50);
        assertFalse(store.complete(200, 999));
        store.discardPending(200);
        assertEquals(100, store.find(200).sourceGid);
    }

    @Test public void configuredLimitsHaveDefaultAndRejectInvalidValues() {
        context.getSharedPreferences("update-record-tests", Context.MODE_PRIVATE)
                .edit().remove(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT).commit();
        assertEquals(200, Settings.getGalleryUpdateRecordLimit());
        for (int limit : new int[]{50, 100, 200, 500, 1000}) {
            Settings.putIntToStr(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT, limit);
            assertEquals(limit, Settings.getGalleryUpdateRecordLimit());
        }
        for (int limit : new int[]{-1, 2000}) {
            Settings.putIntToStr(Settings.KEY_GALLERY_UPDATE_RECORD_LIMIT, limit);
            assertEquals(200, Settings.getGalleryUpdateRecordLimit());
        }
    }

    @Test public void failedAttemptIsVisibleAfterReopenAndSuccessfulRetryUsesOriginalDraft() {
        stage(200, 100);
        assertTrue(store.saveFailure(GalleryUpdateRecord.failure(200, 100, 123,
                "Original title", "Cleanup failed", new long[]{100})));
        store.close();
        store = new GalleryUpdateRecordStore(context);
        GalleryUpdateRecord failure = store.find(200);
        assertTrue(failure.isFailure());
        assertEquals(123, failure.completedAt);
        assertEquals("Original title", failure.sourceTitle);
        GalleryUpdateManager.UpdatePlan plan = new GalleryUpdateManager.UpdatePlan(200, 100, List.of(100L));
        assertTrue(store.stage(plan, () -> { throw new AssertionError("Must keep original comparison"); }));
        assertTrue(store.complete(200, 100));
        assertFalse(store.find(200).isFailure());
        assertArrayEquals(new int[]{1, 3, 4}, store.find(200).addedPages);
    }

    @Test public void failureReplacesPreviousAttemptAndSharesRecordLimit() {
        stage(200, 100); assertTrue(store.complete(200, 100));
        assertTrue(store.saveFailure(GalleryUpdateRecord.failure(200, 50, 999,
                "Another source", "Download failed", new long[]{50})));
        assertTrue(store.find(200).isFailure());
        assertEquals(50, store.find(200).sourceGid);
        assertTrue(store.saveFailure(GalleryUpdateRecord.failure(300, 200, 1000,
                "", "Preparation failed", new long[0])));
        store.trim(1);
        assertNull(store.find(200));
        assertNotNull(store.find(300));
    }
}
