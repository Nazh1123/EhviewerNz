package com.hippo.ehviewer.ui;

import java.util.Arrays;

/** Gallery-local speed selection. All access is guarded by GalleryActivity's monitor. */
final class AnimatedWebpLongPressSpeed {
    static final float STEP_DP = 32f;
    private final float[] speeds;
    private final float stepPixels;
    private int index;
    private float anchorX;
    private long deadline;
    private boolean adjustable;

    AnimatedWebpLongPressSpeed(float configuredSpeed, float density) {
        // Use integer tenths so a configured half-step is never duplicated.
        int configuredTenths = Math.round(configuredSpeed * 10);
        boolean extra = configuredTenths % 5 != 0;
        speeds = new float[extra ? 7 : 6];
        for (int i = 0; i < 6; i++) speeds[i] = (i + 1) * 0.5f;
        if (extra) speeds[6] = configuredTenths / 10f;
        Arrays.sort(speeds);
        index = Arrays.binarySearch(speeds, configuredTenths / 10f);
        stepPixels = STEP_DP * density;
    }

    void begin(float x, long now) {
        anchorX = x;
        deadline = now + 3000;
        adjustable = true;
    }

    boolean move(float x, long now) {
        if (!isAdjustable(now)) return false;
        int steps = (int) ((x - anchorX) / stepPixels);
        if (steps == 0) return false;
        int next = Math.max(0, Math.min(speeds.length - 1, index + steps));
        // Do not accumulate overshoot at an endpoint: reversing one step must work.
        anchorX = next == 0 || next == speeds.length - 1
                ? x : anchorX + steps * stepPixels;
        if (next == index) return false;
        index = next;
        deadline = now + 1500;
        return true;
    }

    boolean isAdjustable(long now) {
        if (now >= deadline) adjustable = false;
        return adjustable;
    }

    long getDeadline() {
        return deadline;
    }

    float getSpeed() {
        return speeds[index];
    }
}
