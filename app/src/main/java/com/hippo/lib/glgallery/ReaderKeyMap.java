package com.hippo.lib.glgallery;

import java.util.Arrays;

/** Immutable gesture mapping shared by the settings preview and the reader. */
public final class ReaderKeyMap {
    public static final int LEGACY = -1, NONE = 0;
    public static final int LEFT = 1, RIGHT = 2, NEXT = 3, PREVIOUS = 4;
    public static final int MENU = 5, CONTROLS = 6, ZOOM = 7, PAGE_MENU = 8;
    public static final int SAVE = 9, SAVE_NEXT = 10, SAVE_PREVIOUS = 11, SAVE_PREVIOUS_SEQUENTIAL = 12;
    public static final int TAP = 0, DOUBLE_TAP = 1, LONG_PRESS = 2;
    public static final int REGION_COUNT = 7, GESTURE_COUNT = 3;
    public static final int DIRECTION_COUNT = 3;
    public static final int LEFT_TOP = 0, LEFT_BOTTOM = 1, RIGHT_TOP = 2,
            RIGHT_BOTTOM = 3, CENTER_TOP = 4, CENTER_MENU = 5, CENTER_BOTTOM = 6;
    private final int[][] directions;
    private final int[][] animatedDirections;
    private final ReaderTouchAreas[] areas;
    public ReaderKeyMap(int[] normal) {
        this(new int[][]{normal, normal, normal});
    }

    public ReaderKeyMap(int[][] directions) {
        this(directions, (ReaderTouchAreas[]) null);
    }

    public ReaderKeyMap(int[][] directions, ReaderTouchAreas[] areas) {
        this(directions, directions, areas);
    }

    public ReaderKeyMap(int[][] directions, int[][] animatedDirections, ReaderTouchAreas[] areas) {
        int count = REGION_COUNT * GESTURE_COUNT;
        this.directions = new int[DIRECTION_COUNT][count];
        this.animatedDirections = new int[DIRECTION_COUNT][count];
        this.areas = new ReaderTouchAreas[DIRECTION_COUNT];
        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            this.areas[direction] = areas != null && direction < areas.length && areas[direction] != null
                    ? areas[direction] : ReaderTouchAreas.defaults();
            int[] target = this.directions[direction];
            Arrays.fill(target, LEGACY);
            int[] source = directions != null && direction < directions.length ? directions[direction] : null;
            for (int i = 0; i < count; i++) {
                if (source != null && i < source.length && valid(source[i])) target[i] = source[i];
            }
            int[] animated = animatedDirections != null && direction < animatedDirections.length
                    ? animatedDirections[direction] : null;
            this.animatedDirections[direction] = target.clone();
            for (int i = 0; i < count; i++) {
                if (animated != null && i < animated.length && valid(animated[i])) this.animatedDirections[direction][i] = animated[i];
            }
        }
    }

    public static boolean valid(int action) {
        return action >= LEGACY && action <= SAVE_PREVIOUS_SEQUENTIAL;
    }

    public int action(int region, int gesture, int direction) {
        return action(region, gesture, direction, false);
    }

    public int action(int region, int gesture, int direction, boolean animated) {
        if (region < 0 || region >= REGION_COUNT || gesture < 0 || gesture >= GESTURE_COUNT) {
            return NONE;
        }
        int index = region * GESTURE_COUNT + gesture;
        return (animated ? animatedDirections : directions)[GalleryView.sanitizeLayoutMode(direction)][index];
    }

    /** Coordinates are relative to the reader viewport, not the image bounds. */
    public static int region(float x, float y, float width, float height) {
        return ReaderTouchAreas.defaults().region(x, y, width, height);
    }

    public static float[] bounds(int region) {
        return ReaderTouchAreas.defaults().bounds(region);
    }

    public ReaderTouchAreas areas(int direction) {
        return areas[GalleryView.sanitizeLayoutMode(direction)];
    }

    public int resolvedAction(int region, int gesture, int direction) {
        return resolvedAction(region, gesture, direction, false);
    }

    public int resolvedAction(int region, int gesture, int direction, boolean animated) {
        int action = action(region, gesture, direction, animated);
        return action == LEGACY ? defaultAction(region, gesture) : action;
    }

    public static int defaultAction(int region, int gesture) {
        if (region < 0 || region >= REGION_COUNT || gesture < 0 || gesture >= GESTURE_COUNT) return NONE;
        if (gesture == TAP) {
            if (region <= RIGHT_BOTTOM) return region <= LEFT_BOTTOM ? LEFT : RIGHT;
            return region == CENTER_MENU ? MENU : CONTROLS;
        }
        return gesture == DOUBLE_TAP ? ZOOM : PAGE_MENU;
    }
}
