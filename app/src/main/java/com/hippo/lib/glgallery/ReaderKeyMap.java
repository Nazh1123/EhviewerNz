package com.hippo.lib.glgallery;

import java.util.Arrays;

/** Immutable gesture mapping shared by the settings preview and the reader. */
public final class ReaderKeyMap {
    public static final int LEGACY = -1, NONE = 0;
    public static final int LEFT = 1, RIGHT = 2, NEXT = 3, PREVIOUS = 4;
    public static final int MENU = 5, CONTROLS = 6, ZOOM = 7, PAGE_MENU = 8;
    public static final int SAVE = 9, SAVE_NEXT = 10;
    public static final int TAP = 0, DOUBLE_TAP = 1, LONG_PRESS = 2;
    public static final int REGION_COUNT = 7, GESTURE_COUNT = 3;
    public static final int DIRECTION_COUNT = 3;
    public static final int LEFT_TOP = 0, LEFT_BOTTOM = 1, RIGHT_TOP = 2,
            RIGHT_BOTTOM = 3, CENTER_TOP = 4, CENTER_MENU = 5, CENTER_BOTTOM = 6;
    private final int[][] directions;
    public final int animatedControlPercent;

    public ReaderKeyMap(int[] normal, int animatedControlPercent) {
        this(new int[][]{normal, normal, normal}, animatedControlPercent);
    }

    public ReaderKeyMap(int[][] directions, int animatedControlPercent) {
        int count = REGION_COUNT * GESTURE_COUNT;
        this.directions = new int[DIRECTION_COUNT][count];
        this.animatedControlPercent = animatedControlPercent >= 0 && animatedControlPercent <= 100
                ? animatedControlPercent : 30;
        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            int[] target = this.directions[direction];
            Arrays.fill(target, LEGACY);
            int[] source = directions != null && direction < directions.length ? directions[direction] : null;
            for (int i = 0; i < count; i++) {
                if (source != null && i < source.length && valid(source[i])) target[i] = source[i];
            }
        }
    }

    public static boolean valid(int action) {
        return action >= LEGACY && action <= SAVE_NEXT;
    }

    public int action(int region, int gesture, int direction) {
        if (region < 0 || region >= REGION_COUNT || gesture < 0 || gesture >= GESTURE_COUNT) {
            return NONE;
        }
        int index = region * GESTURE_COUNT + gesture;
        return directions[GalleryView.sanitizeLayoutMode(direction)][index];
    }

    public static boolean isAnimatedControlArea(float x, float y, float width, float height, int percent) {
        int area = region(x, y, width, height);
        return percent > 0 && area >= 0 && area <= RIGHT_BOTTOM
                && y >= height * ((100 - percent) / 100f);
    }

    /** Coordinates are relative to the reader viewport, not the image bounds. */
    public static int region(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) return -1;
        float nx = x / width, ny = y / height;
        if (nx < 9f / 25f) return ny < .5f ? LEFT_TOP : LEFT_BOTTOM;
        if (nx >= 16f / 25f) return ny < .5f ? RIGHT_TOP : RIGHT_BOTTOM;
        if (ny < .15f) return CENTER_TOP;
        return ny < .5f ? CENTER_MENU : CENTER_BOTTOM;
    }

    public static float[] bounds(int region) {
        return switch (region) {
            case LEFT_TOP -> new float[]{0, 0, .36f, .5f};
            case LEFT_BOTTOM -> new float[]{0, .5f, .36f, 1};
            case RIGHT_TOP -> new float[]{.64f, 0, 1, .5f};
            case RIGHT_BOTTOM -> new float[]{.64f, .5f, 1, 1};
            case CENTER_TOP -> new float[]{.36f, 0, .64f, .15f};
            case CENTER_MENU -> new float[]{.36f, .15f, .64f, .5f};
            case CENTER_BOTTOM -> new float[]{.36f, .5f, .64f, 1};
            default -> throw new IllegalArgumentException("Invalid region");
        };
    }
}
