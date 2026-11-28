package com.hippo.ehviewer.download;

import android.app.Application;
import android.util.SparseArray;

import com.hippo.ehviewer.spider.SpiderInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

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

    @Test public void previewSeedsOnlyMissingPageTokensAndPreservesReadingPosition() {
        var record = GalleryUpdateRecord.compare(200, 100, info("A"), info("A", "B")).withTargetToken("target");
        SpiderInfo seeded = record.seedDownload(null);
        assertEquals(200, seeded.gid);
        assertEquals("target", seeded.token);
        assertEquals("A", seeded.pTokenMap.get(0));
        assertEquals("B", seeded.pTokenMap.get(1));
        seeded.startPage = 1;
        seeded.pTokenMap.put(0, "another");
        seeded.pTokenMap.put(1, "failed");
        assertSame(seeded, record.seedDownload(seeded));
        assertEquals(1, seeded.startPage);
        assertEquals("another", seeded.pTokenMap.get(0));
        assertEquals("B", seeded.pTokenMap.get(1));
        seeded.token = "other-version";
        seeded.pTokenMap.remove(1);
        record.seedDownload(seeded);
        assertNull(seeded.pTokenMap.get(1));
    }

    @Test public void insertionDeletionAndReorderKeepActualPageNumbers() {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "B", "C", "D"), info("D", "X", "A", "Y", "C"));
        assertTrue(record.complete);
        assertArrayEquals(new int[]{1, 3}, record.addedPages);
        assertArrayEquals(new int[]{1}, record.deletedPages);
    }

    @Test public void historicalAdditionsFollowTokensAfterDeletionAndReordering() throws Exception {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "B"), info("A", "X", "Y", "B", "Z")).withFirstGid(50);
        GalleryUpdateRecord saved = GalleryUpdateRecord.fromJson(200, 100, 123, 1, record.toJson());
        assertTrue(saved.hasTokenSnapshot());
        SpiderInfo latest = info("Z", "B", "NEW", "X", "A");
        latest.gid = 400;
        assertEquals(50, saved.firstGid);
        assertArrayEquals(new int[]{0, 3}, saved.resolveAddedPages(latest));
        // Maps each original addition to its current page, retaining the missing Y slot.
        assertArrayEquals(new int[]{3, -1, 0}, saved.resolveAddedPageMap(latest));
        assertArrayEquals(new int[]{1, 2, 4}, saved.addedPages);
    }

    @Test public void duplicateHistoricalAdditionsDoNotReadAnUnchangedOccurrence() {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "B"), info("A", "B", "B", "X"));
        assertArrayEquals(new int[]{2, 3}, record.addedPages);
        assertArrayEquals(new int[]{0}, record.resolveAddedPages(info("X", "B", "A")));
        assertArrayEquals(new int[]{0, 2}, record.resolveAddedPages(info("X", "B", "B", "A")));
    }

    @Test public void legacyHistoryNeverAppliesOldPageNumbersToAnotherVersion() {
        GalleryUpdateRecord legacy = new GalleryUpdateRecord(200, 100, 123,
                1, 2, true, new int[]{1}, new int[0], 0);
        assertFalse(legacy.hasTokenSnapshot());
        SpiderInfo current = info("A", "X");
        current.gid = 300;
        assertArrayEquals(new int[0], legacy.resolveAddedPages(current));
        current.gid = 200;
        assertArrayEquals(new int[]{1}, legacy.resolveAddedPages(current));
        assertArrayEquals(new int[0], legacy.resolveAddedPages(info("A", null)));
    }

    @Test public void corruptTokenSnapshotsCannotSelectWrongPages() throws Exception {
        for (String tokens : new String[]{"[\"A\"]", "[\"A\",\"failed\"]", "[\"A\",\"\"]"}) {
            try {
                GalleryUpdateRecord.fromJson(200, 100, 1, 0,
                        "{\"old_pages\":1,\"new_pages\":2,\"complete\":true,\"added\":[1],"
                                + "\"deleted\":[],\"target_tokens\":" + tokens + "}");
                fail("Invalid token snapshot accepted");
            } catch (org.json.JSONException expected) { }
        }
    }

    @Test public void duplicateOccurrencesAreCountedRatherThanCollapsed() {
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(200, 100,
                info("A", "A", "B"), info("A", "B", "B"));
        assertArrayEquals(new int[]{2}, record.addedPages);
        assertArrayEquals(new int[]{1}, record.deletedPages);
    }

    @Test public void groupedDeletionsCountCopiesIndependentlyAndKeepOriginalIndexes() throws Exception {
        var compared = GalleryUpdateRecord.compareSources(200, 150, List.of(
                new GalleryUpdateRecord.Source(150, 5, GalleryPageRange.parse("2,4-5", 5),
                        info(null, "A", null, "A", "X")),
                new GalleryUpdateRecord.Source(120, 3, null, info("A", "X", "A")),
                new GalleryUpdateRecord.Source(100, 2, null, info("A", null))), info("A", "B"));
        var saved = GalleryUpdateRecord.fromJson(200, 150, 123, 0, compared.toJson());
        assertTrue(saved.hasTokenSnapshot());
        assertFalse(saved.hasKnownAdditions());
        assertArrayEquals(new int[0], saved.addedPages);
        assertArrayEquals(new int[]{3, 4}, saved.deletedPages);
        assertArrayEquals(new int[]{3, 4}, saved.sourceDeletions.get(0).pages());
        assertArrayEquals(new int[]{1, 2}, saved.sourceDeletions.get(1).pages());
        assertFalse(saved.sourceDeletions.get(2).complete());
        assertArrayEquals(new int[0], saved.sourceDeletions.get(2).pages());
    }

    @Test public void latestRangeUsesWholeGalleryForAdditionsAndItsSelectionForDeletions() throws Exception {
        var compared = GalleryUpdateRecord.compareSources(200, 150, List.of(
                new GalleryUpdateRecord.Source(150, 6, GalleryPageRange.parse("2,4", 6),
                        info("A", "A", "B", "X", "Y", "Z")),
                new GalleryUpdateRecord.Source(120, 2, null, info("N", "M"))),
                info("A", "A", "A", "B", "Y", "N"));
        var saved = GalleryUpdateRecord.fromJson(200, 150, 123, 0, compared.toJson());
        assertTrue(saved.hasKnownAdditions());
        assertArrayEquals(new int[]{2, 5}, saved.addedPages);
        assertArrayEquals(new int[]{2, 5}, saved.resolveAddedPages(info("A", "A", "A", "B", "Y", "N")));
        assertArrayEquals(new int[]{3}, saved.sourceDeletions.get(0).pages());
        assertArrayEquals(new int[]{1}, saved.sourceDeletions.get(1).pages());
    }

    @Test public void localCoverageListsPagesOutsideFirstHundredAndPersistsTheirMeaning() throws Exception {
        String[] older = java.util.stream.IntStream.range(0, 1159).mapToObj(i -> "old" + i).toArray(String[]::new);
        String[] newer = new String[1165];
        for (int i = 0; i < 6; i++) newer[i] = "new" + i;
        System.arraycopy(older, 0, newer, 6, older.length);
        var covered = new java.util.HashSet<>(java.util.Arrays.asList(older).subList(0, 100));
        var record = GalleryUpdateRecord.compareSources(200, 150, List.of(
                new GalleryUpdateRecord.Source(150, older.length, GalleryPageRange.parse("1-100", older.length),
                        info(older))), info(newer), covered);
        var saved = GalleryUpdateRecord.fromJson(200, 150, 123, 0, record.toJson());
        assertTrue(saved.showsLocalCoverage());
        assertEquals(1065, saved.addedPages.length);
        assertEquals("p1–6, p107–1165", GalleryUpdateRecord.formatPages(saved.addedPages));
        assertEquals(0, saved.sourceDeletions.get(0).pages().length);
        assertEquals(1159, saved.sourceDeletions.get(0).totalPages());
        assertEquals(100, saved.sourceDeletions.get(0).comparisonPages());
    }

    @Test public void olderGroupedRecordsKeepFullCountsAndMarkUnsavedRangeCountsUnknown() throws Exception {
        var record = GalleryUpdateRecord.compareSources(200, 150, List.of(
                new GalleryUpdateRecord.Source(150, 3, GalleryPageRange.parse("2", 3), info("A", "B", "C")),
                new GalleryUpdateRecord.Source(120, 2, null, info("A", "B"))), info("A"));
        var payload = record.toPayload();
        var groups = payload.getJSONArray("source_deletions");
        for (int i = 0; i < groups.length(); i++) groups.getJSONObject(i).remove("comparison_pages");
        var saved = GalleryUpdateRecord.fromPayload(200, 150, 123, 0, payload);
        assertEquals(-1, saved.sourceDeletions.get(0).comparisonPages());
        assertEquals(2, saved.sourceDeletions.get(1).comparisonPages());
        assertArrayEquals(new int[]{1}, saved.sourceDeletions.get(0).pages());
    }

    @Test public void localCoverageIsAvailableEvenWhenAnOldDeletionGroupIsUnknown() throws Exception {
        var record = GalleryUpdateRecord.compareSources(200, 150, List.of(
                new GalleryUpdateRecord.Source(150, 2, GalleryPageRange.parse("1", 2), info(null, "A"))),
                info("B", "B", "A"), java.util.Set.of("B"));
        var saved = GalleryUpdateRecord.fromJson(200, 150, 123, 0, record.toJson());
        assertTrue(saved.hasKnownAdditions());
        assertFalse(saved.hasKnownDeletions());
        assertArrayEquals(new int[]{2}, saved.addedPages);
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
