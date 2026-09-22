package com.hippo.ehviewer.ui;

/** Direction-aware thresholds for animated pages during horizontal swipes. */
final class AnimatedWebpVisibilityPolicy {
    private AnimatedWebpVisibilityPolicy() {}

    static boolean shouldPlay(boolean wasPlaying, float previousFraction,
                              float visibleFraction) {
        // Increasing visibility means the page is entering from either side.
        // Decreasing visibility means one quarter has disappeared at 75% left.
        // Keeping the prior state on equal visibility avoids repeated toggles.
        if (visibleFraction > previousFraction && visibleFraction >= 0.5f) {
            return true;
        }
        if (visibleFraction < previousFraction && visibleFraction <= 0.75f) {
            return false;
        }
        return wasPlaying;
    }
}
