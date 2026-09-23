package com.hippo.ehviewer.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnimatedWebpVisibilityPolicyTest {
    @Test
    public void incomingPageStartsAtHalf() {
        AnimatedWebpVisibilityPolicy page = new AnimatedWebpVisibilityPolicy();
        page.update(0.49f);
        assertFalse(page.isPlaying());
        page.update(0.5f);
        assertTrue(page.isPlaying());
    }

    @Test
    public void outgoingPageStopsWhenOneQuarterHasDisappeared() {
        AnimatedWebpVisibilityPolicy page = new AnimatedWebpVisibilityPolicy();
        page.update(1f);
        assertTrue(page.isPlaying());
        page.update(0.76f);
        assertTrue(page.isPlaying());
        page.update(0.75f);
        assertFalse(page.isPlaying());
        page.update(0.4f);
        assertFalse(page.isPlaying());
    }

    @Test
    public void smallReversalAfterStartingDoesNotImmediatelyPause() {
        AnimatedWebpVisibilityPolicy page = new AnimatedWebpVisibilityPolicy();
        page.update(0.5f);
        assertTrue(page.isPlaying());
        page.update(0.49f);
        assertTrue(page.isPlaying());
        page.update(0.41f);
        assertTrue(page.isPlaying());
        page.update(0.4f);
        assertFalse(page.isPlaying());
    }

    @Test
    public void reversalDistanceIsMeasuredFromFarthestVisibility() {
        AnimatedWebpVisibilityPolicy page = new AnimatedWebpVisibilityPolicy();
        page.update(0.5f);
        page.update(0.7f);
        page.update(0.65f);
        assertTrue(page.isPlaying());
        page.update(0.67f);
        assertTrue(page.isPlaying());
        page.update(0.59f);
        assertFalse(page.isPlaying());

        page.update(0.68f);
        assertFalse(page.isPlaying());
        page.update(0.71f);
        assertTrue(page.isPlaying());
    }

    @Test
    public void returningPageWaitsForTenPercentBeforeRestarting() {
        AnimatedWebpVisibilityPolicy page = new AnimatedWebpVisibilityPolicy();
        page.update(1f);
        page.update(0.74f);
        assertFalse(page.isPlaying());
        page.update(0.83f);
        assertFalse(page.isPlaying());
        page.update(0.85f);
        assertTrue(page.isPlaying());
    }
}
