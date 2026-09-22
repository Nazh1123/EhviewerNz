package com.hippo.ehviewer.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnimatedWebpVisibilityPolicyTest {
    @Test
    public void incomingPageStartsAtHalfFromEitherSide() {
        assertFalse(AnimatedWebpVisibilityPolicy.shouldPlay(false, 0f, 0.49f));
        assertTrue(AnimatedWebpVisibilityPolicy.shouldPlay(false, 0.49f, 0.5f));
        assertTrue(AnimatedWebpVisibilityPolicy.shouldPlay(false, 0.4f, 0.6f));
    }

    @Test
    public void outgoingPageStopsWhenOneQuarterHasDisappeared() {
        assertTrue(AnimatedWebpVisibilityPolicy.shouldPlay(true, 1f, 0.76f));
        assertFalse(AnimatedWebpVisibilityPolicy.shouldPlay(true, 0.76f, 0.75f));
        assertFalse(AnimatedWebpVisibilityPolicy.shouldPlay(false, 0.75f, 0.4f));
    }

    @Test
    public void reversingSwipeAppliesTheOppositeDirectionRules() {
        assertTrue(AnimatedWebpVisibilityPolicy.shouldPlay(false, 0.7f, 0.71f));
        assertFalse(AnimatedWebpVisibilityPolicy.shouldPlay(true, 0.6f, 0.59f));
        assertTrue(AnimatedWebpVisibilityPolicy.shouldPlay(true, 0.59f, 0.59f));
    }
}
