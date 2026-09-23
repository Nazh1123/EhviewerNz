package com.hippo.ehviewer.ui;

/** Visibility thresholds and reversal hysteresis for animated pager pages. */
final class AnimatedWebpVisibilityPolicy {
    private static final float START_FRACTION = 0.5f;
    private static final float STOP_FRACTION = 0.75f;
    // Pager pages span the viewport width, so this is 10% of the screen width.
    private static final float REVERSAL_FRACTION = 0.1f;

    private float visibleFraction;
    // Highest visibility while playing, or lowest visibility while paused.
    private float extremeFraction;
    private boolean playing;

    float getVisibleFraction() {
        return visibleFraction;
    }

    boolean isPlaying() {
        return playing;
    }

    void update(float fraction) {
        if (fraction == visibleFraction) return;

        if (playing) {
            extremeFraction = Math.max(extremeFraction, fraction);
            if (fraction < visibleFraction && fraction <= STOP_FRACTION
                    && fraction <= extremeFraction - REVERSAL_FRACTION) {
                playing = false;
                extremeFraction = fraction;
            }
        } else {
            extremeFraction = Math.min(extremeFraction, fraction);
            if (fraction > visibleFraction && fraction >= START_FRACTION
                    && fraction >= extremeFraction + REVERSAL_FRACTION) {
                playing = true;
                extremeFraction = fraction;
            }
        }
        visibleFraction = fraction;
    }
}
