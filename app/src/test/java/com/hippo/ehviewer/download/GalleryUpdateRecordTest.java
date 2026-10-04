package com.hippo.ehviewer.download;

import android.app.Application;
import android.util.SparseArray;

import com.hippo.ehviewer.spider.SpiderInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class GalleryUpdateRecordTest {
    private SpiderInfo info(String... tokens) {
        SpiderInfo info = new SpiderInfo();
        info.pages = tokens.length;
        info.pTokenMap = new SparseArray<>();
        for (int i = 0; i < tokens.length; i++) if (tokens[i] != null) info.pTokenMap.put(i, tokens[i]);
        return info;
    }

    @Test public void insertionDeletionAndReorderKeepActualPageNumbers() {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "B", "C", "D"), info("D", "X", "A", "Y", "C"));
        assertTrue(record.complete);
        assertArrayEquals(new int[]{1, 3}, record.addedPages);
        assertArrayEquals(new int[]{1}, record.deletedPages);
    }

    @Test public void duplicateOccurrencesAreCountedRatherThanCollapsed() {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "A", "B"), info("A", "B", "B"));
        assertArrayEquals(new int[]{2}, record.addedPages);
        assertArrayEquals(new int[]{1}, record.deletedPages);
    }

    @Test public void replacementIsOneAddedPageAndOneDeletedPage() {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "B"), info("A", "X"));
        assertArrayEquals(new int[]{1}, record.addedPages);
        assertArrayEquals(new int[]{1}, record.deletedPages);
    }

    @Test public void incompleteMetadataDoesNotInventChanges() {
        for (SpiderInfo source : new SpiderInfo[]{null, info("A", null), info("A", "failed")}) {
            GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100, source, info("A", "X"));
            assertFalse(record.complete);
            assertEquals(0, record.addedPages.length);
            assertEquals(0, record.deletedPages.length);
        }
        assertFalse(GalleryUpdateRecord.compare(200, 100, info("A"), info((String) null)).complete);
    }

    @Test public void logRangesAndJsonUseDifferentPageBasesCorrectly() throws Exception {
        int[] added = {99, 100, 101, 102, 103, 104, 105, 106, 107, 119, 120};
        GalleryUpdateRecord record = new GalleryUpdateRecord(200, 100, 123, 2, 121,
                true, added, new int[]{0, 1}, 8);
        assertEquals("p100–108, p120–121", GalleryUpdateRecord.formatPages(added));
        assertEquals("p1–2", GalleryUpdateRecord.formatPages(record.deletedPages));
        GalleryUpdateRecord decoded = GalleryUpdateRecord.fromJson(200, 100, 123, 8, record.toJson());
        assertArrayEquals(added, decoded.addedPages);
        assertEquals(8, decoded.readingPage);
    }

    @Test public void corruptStoredIndexesAreRejected() throws Exception {
        for (String array : new String[]{"[-1]", "[2]", "[1,1]", "[1,0]"}) {
            try {
                GalleryUpdateRecord.fromJson(200, 100, 1, 0,
                        "{\"old_pages\":2,\"new_pages\":2,\"complete\":true,\"added\":"
                                + array + ",\"deleted\":[]}");
                fail("Invalid index accepted: " + array);
            } catch (org.json.JSONException expected) {
                // Corruption must never cause a reader to access an arbitrary source page.
            }
        }
    }

    @Test public void failureRoundTripsWithoutInventingPageChangesAndOldRecordsRemainSuccessful() throws Exception {
        GalleryUpdateRecord failure = GalleryUpdateRecord.failure(200, 100, 123,
                "Old gallery", "Missing .ehviewer", new long[]{100, 50});
        GalleryUpdateRecord decoded = GalleryUpdateRecord.fromJson(200, 100, 123, 0, failure.toJson());
        assertTrue(decoded.isFailure());
        assertFalse(decoded.complete);
        assertEquals("Old gallery", decoded.sourceTitle);
        assertEquals("Missing .ehviewer", decoded.errorReason);
        assertArrayEquals(new long[]{100, 50}, decoded.retainedParentGids);
        assertEquals(0, decoded.addedPages.length);
        GalleryUpdateRecord legacy = GalleryUpdateRecord.fromJson(200, 100, 123, 0,
                "{\"old_pages\":2,\"new_pages\":3,\"complete\":false,\"added\":[],\"deleted\":[]}");
        assertFalse(legacy.isFailure());
    }
}
