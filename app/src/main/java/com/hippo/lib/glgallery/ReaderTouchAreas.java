package com.hippo.lib.glgallery;

/** Immutable, normalized boundaries shared by the editor and all reader gestures. */
public final class ReaderTouchAreas {
    public static final int LEFT_EDGE = 0, RIGHT_EDGE = 1, LEFT_SPLIT = 2,
            RIGHT_SPLIT = 3, CENTER_TOP_SPLIT = 4, CENTER_BOTTOM_SPLIT = 5;
    public static final int LINE_COUNT = 6;
    public static final float MIN_GAP = .05f;
    private final float[] lines;

    private ReaderTouchAreas(float[] lines) { this.lines = lines.clone(); }

    public static ReaderTouchAreas defaults() {
        return new ReaderTouchAreas(new float[]{1f / 3f, 2f / 3f, .5f, .5f, .15f, .5f});
    }

    public static ReaderTouchAreas recommended() {
        return new ReaderTouchAreas(new float[]{.36f, .64f, .5f, .5f, .15f, .5f});
    }

    public static ReaderTouchAreas from(float[] values, ReaderTouchAreas fallback) {
        if (values == null || values.length != LINE_COUNT) return fallback;
        for (float value : values) if (!Float.isFinite(value)) return fallback;
        ReaderTouchAreas candidate = new ReaderTouchAreas(values);
        for (int line = 0; line < LINE_COUNT; line++) {
            if (values[line] < candidate.min(line) - .000001f
                    || values[line] > candidate.max(line) + .000001f) return fallback;
        }
        return candidate;
    }

    public float position(int line) { return lines[line]; }
    public float[] positions() { return lines.clone(); }
    public float min(int line) {
        return switch (line) {
            case RIGHT_EDGE -> lines[LEFT_EDGE] + MIN_GAP;
            case CENTER_BOTTOM_SPLIT -> lines[CENTER_TOP_SPLIT] + MIN_GAP;
            default -> MIN_GAP;
        };
    }
    public float max(int line) {
        return switch (line) {
            case LEFT_EDGE -> lines[RIGHT_EDGE] - MIN_GAP;
            case CENTER_TOP_SPLIT -> lines[CENTER_BOTTOM_SPLIT] - MIN_GAP;
            default -> 1f - MIN_GAP;
        };
    }
    public ReaderTouchAreas withPosition(int line, float value) {
        if (!Float.isFinite(value)) return this;
        float[] updated = positions();
        updated[line] = Math.max(min(line), Math.min(max(line), value));
        return new ReaderTouchAreas(updated);
    }

    public int region(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0 || !Float.isFinite(x) || !Float.isFinite(y)
                || x < 0 || y < 0 || x >= width || y >= height) return -1;
        float nx = x / width, ny = y / height;
        if (nx < lines[LEFT_EDGE]) return ny < lines[LEFT_SPLIT] ? ReaderKeyMap.LEFT_TOP : ReaderKeyMap.LEFT_BOTTOM;
        if (nx >= lines[RIGHT_EDGE]) return ny < lines[RIGHT_SPLIT] ? ReaderKeyMap.RIGHT_TOP : ReaderKeyMap.RIGHT_BOTTOM;
        if (ny < lines[CENTER_TOP_SPLIT]) return ReaderKeyMap.CENTER_TOP;
        return ny < lines[CENTER_BOTTOM_SPLIT] ? ReaderKeyMap.CENTER_MENU : ReaderKeyMap.CENTER_BOTTOM;
    }

    public float[] bounds(int region) {
        float left = lines[LEFT_EDGE], right = lines[RIGHT_EDGE];
        return switch (region) {
            case ReaderKeyMap.LEFT_TOP -> new float[]{0, 0, left, lines[LEFT_SPLIT]};
            case ReaderKeyMap.LEFT_BOTTOM -> new float[]{0, lines[LEFT_SPLIT], left, 1};
            case ReaderKeyMap.RIGHT_TOP -> new float[]{right, 0, 1, lines[RIGHT_SPLIT]};
            case ReaderKeyMap.RIGHT_BOTTOM -> new float[]{right, lines[RIGHT_SPLIT], 1, 1};
            case ReaderKeyMap.CENTER_TOP -> new float[]{left, 0, right, lines[CENTER_TOP_SPLIT]};
            case ReaderKeyMap.CENTER_MENU -> new float[]{left, lines[CENTER_TOP_SPLIT], right, lines[CENTER_BOTTOM_SPLIT]};
            case ReaderKeyMap.CENTER_BOTTOM -> new float[]{left, lines[CENTER_BOTTOM_SPLIT], right, 1};
            default -> throw new IllegalArgumentException("Invalid region");
        };
    }

    /** A line segment in normalized viewport coordinates. */
    public float[] segment(int line) {
        return switch (line) {
            case LEFT_EDGE, RIGHT_EDGE -> new float[]{lines[line], 0, lines[line], 1};
            case LEFT_SPLIT -> new float[]{0, lines[line], lines[LEFT_EDGE], lines[line]};
            case RIGHT_SPLIT -> new float[]{lines[RIGHT_EDGE], lines[line], 1, lines[line]};
            case CENTER_TOP_SPLIT, CENTER_BOTTOM_SPLIT -> new float[]{lines[LEFT_EDGE], lines[line], lines[RIGHT_EDGE], lines[line]};
            default -> throw new IllegalArgumentException("Invalid line");
        };
    }

    public boolean isAnimatedControlArea(float x, float y, float width, float height, int percent) {
        int area = region(x, y, width, height);
        return percent > 0 && area >= 0 && area <= ReaderKeyMap.RIGHT_BOTTOM
                && y >= height * ((100 - percent) / 100f);
    }
}
