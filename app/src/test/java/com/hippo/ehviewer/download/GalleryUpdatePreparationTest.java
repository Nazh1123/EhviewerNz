package com.hippo.ehviewer.download;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GalleryUpdatePreparationTest {
    @Test public void unreadableTargetCannotAdvanceToMigrationOrCleanup() {
        assertFalse(GalleryUpdatePreparation.prepare(false, () -> false,
                () -> { fail("Must retain the original reading position"); return true; },
                () -> { fail("Must not checkpoint a failed validation"); return true; }));
    }

    @Test public void migrationFailureKeepsPlanRetryable() {
        assertFalse(GalleryUpdatePreparation.prepare(false, () -> true, () -> false,
                () -> { fail("Migration has not succeeded"); return true; }));
    }

    @Test public void failedDurableCheckpointForbidsCleanup() {
        assertFalse(GalleryUpdatePreparation.prepare(false, () -> true, () -> true, () -> false));
    }

    @Test public void retryDoesNotRequireAnAlreadyDeletedSource() {
        AtomicInteger validations = new AtomicInteger();
        assertTrue(GalleryUpdatePreparation.prepare(true,
                () -> { validations.incrementAndGet(); return true; },
                () -> { fail("Source folder can already be gone"); return false; },
                () -> { fail("Already checkpointed"); return false; }));
        assertEquals(1, validations.get());
        assertFalse(GalleryUpdatePreparation.prepare(true, () -> false, () -> true, () -> true));
    }

    @Test public void successfulPreparationChecksStorageBeforeCheckpoint() {
        StringBuilder order = new StringBuilder();
        assertTrue(GalleryUpdatePreparation.prepare(false,
                () -> { order.append('V'); return true; },
                () -> { order.append('M'); return true; },
                () -> { order.append('C'); return true; }));
        assertEquals("VMC", order.toString());
    }
}
