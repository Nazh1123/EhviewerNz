package com.hippo.ehviewer.download;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongPredicate;

import static org.junit.Assert.*;

public class GalleryVersionIndexTest {
    @Test public void olderQueriesStayWithinFamilyAndExcludeTarget() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        index.update(180, 100L, false);
        index.update(100, 100L, false);
        index.update(130, 100L, false);
        index.update(120, 110L, false);

        assertEquals(List.of(100L, 130L), index.getOlder(100, 180));
        assertFalse(index.hasOlder(100, 100));
        assertTrue(index.hasOlder(100, 101));
        assertFalse(index.hasOlder(999, Long.MAX_VALUE));
    }

    @Test public void metadataCorrectionRemovesPreviousFamilyWithoutNeedingOldValue() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        index.update(180, 100L, false);
        index.update(180, 120L, false);
        assertFalse(index.hasOlder(100, 200));
        assertEquals(List.of(180L), index.getOlder(120, 200));

        index.remove(180);
        index.update(180, 100L, false);
        assertFalse(index.hasOlder(120, 200));
        assertEquals(List.of(180L), index.getOlder(100, 200));
    }

    @Test public void unknownUnavailableAndImportedRecordsNeverEstablishFamilies() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        for (Long firstGid : new Long[]{null, 0L, -1L}) {
            index.update(180, 100L, false);
            index.update(180, firstGid, false);
            assertFalse(index.hasOlder(100, 200));
        }
        index.update(180, 100L, false);
        index.update(180, 100L, true);
        assertFalse(index.hasOlder(100, 200));
        index.update(180, 100L, false);
        assertTrue(index.hasOlder(100, 200));
    }

    @Test public void repeatedUpdatesAndRemovalsDoNotDisturbSiblingVersions() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        index.update(130, 100L, false);
        index.update(180, 100L, false);
        index.update(180, 100L, false);
        assertEquals(List.of(130L, 180L), index.getOlder(100, 200));
        index.remove(180);
        index.remove(180);
        index.remove(999);
        assertEquals(List.of(130L), index.getOlder(100, 200));
    }

    @Test public void closestOlderChecksCurrentEligibilityWithoutReindexing() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        for (long gid : new long[]{100, 130, 180, 200}) index.update(gid, 100L, false);
        List<Long> active = new ArrayList<>(List.of(180L));
        LongPredicate eligible = gid -> !active.contains(gid);
        assertEquals(Long.valueOf(130), index.findClosestOlder(100, 200, eligible));
        active.clear();
        assertEquals(Long.valueOf(180), index.findClosestOlder(100, 200, eligible));
        assertNull(index.findClosestOlder(100, 100, eligible));
        assertNull(index.findClosestOlder(100, 200, gid -> false));
    }

    @Test public void queryResultsAreSnapshotsAndCannotModifyMembership() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        index.update(130, 100L, false);
        List<Long> result = index.getOlder(100, 200);
        index.update(180, 100L, false);
        assertEquals(List.of(130L), result);
        result.clear();
        assertEquals(List.of(130L, 180L), index.getOlder(100, 200));
    }

    @Test public void rebuildClearsReverseMembershipAsWellAsFamilies() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        index.update(130, 100L, false);
        index.update(180, 100L, false);
        index.clear();
        index.update(130, 100L, false);
        assertEquals(List.of(130L), index.getOlder(100, 200));
        index.remove(130);
        assertFalse(index.hasOlder(100, 200));
    }

    @Test public void randomChangesMatchFullScanReference() {
        GalleryVersionIndex index = new GalleryVersionIndex();
        Map<Long, Record> records = new HashMap<>();
        Random random = new Random(0x46474944L);
        for (int step = 0; step < 2500; step++) {
            long gid = 10 + random.nextInt(90);
            if (step % 127 == 0) {
                // Simulate restoring all records after a restart/metadata maintenance pass.
                index.clear();
                records.forEach((key, value) ->
                        index.update(key, value.firstGid, value.imported));
            } else if (random.nextInt(4) == 0) {
                records.remove(gid);
                index.remove(gid);
            } else {
                int family = random.nextInt(8) - 2;
                Record value = new Record(family == -2 ? null : (long) family,
                        random.nextInt(5) == 0);
                records.put(gid, value);
                index.update(gid, value.firstGid, value.imported);
            }
            long target = 10 + random.nextInt(100);
            LongPredicate eligible = value -> (value + target) % 3 != 0;
            for (long family = 1; family <= 5; family++) {
                List<Long> expected = new ArrayList<>();
                for (Map.Entry<Long, Record> entry : records.entrySet()) {
                    Record value = entry.getValue();
                    if (!value.imported && value.firstGid != null
                            && value.firstGid == family && entry.getKey() < target) {
                        expected.add(entry.getKey());
                    }
                }
                expected.sort(Long::compare);
                assertEquals("step " + step, expected, index.getOlder(family, target));
                assertEquals(!expected.isEmpty(), index.hasOlder(family, target));
                Long closest = null;
                for (Long candidate : expected) {
                    if (eligible.test(candidate)) closest = candidate;
                }
                assertEquals(closest, index.findClosestOlder(family, target, eligible));
            }
        }
    }

    private record Record(Long firstGid, boolean imported) {}
}
