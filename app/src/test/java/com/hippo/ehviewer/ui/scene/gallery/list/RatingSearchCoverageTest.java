package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.*;
import org.junit.Test;

public class RatingSearchCoverageTest {
    @Test public void raisingThenLoweringWithoutLoadingKeepsBroadCoverage() {
        RatingSearchCoverage cache = new RatingSearchCoverage();
        cache.record(275, true);
        assertTrue(cache.contains(380));
        assertTrue(cache.contains(350));
        assertFalse(cache.contains(274));
    }

    @Test public void stricterContinuationCreatesAHoleForLowerThresholdsEvenWhenPageWasEmpty() {
        RatingSearchCoverage cache = new RatingSearchCoverage();
        cache.record(275, true);
        cache.record(375, false);
        assertTrue(cache.contains(375));
        assertFalse(cache.contains(350));
        cache.record(275, false);
        assertFalse(cache.contains(350));
        cache.record(275, true);
        assertTrue(cache.contains(350));
    }

    @Test public void unknownOrLegacyPagesCannotClaimCompleteCoverage() {
        RatingSearchCoverage cache = new RatingSearchCoverage();
        cache.record(0, false);
        assertFalse(cache.contains(500));
        cache.record(0, true);
        assertTrue(cache.contains(0));
        cache.record(-1, false);
        assertFalse(cache.contains(500));
    }
}
