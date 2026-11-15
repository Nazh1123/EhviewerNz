package com.hippo.ehviewer.ui.scene.gallery.list;

/** Conservative coverage of every committed page, including pages with no visible matches. */
final class RatingSearchCoverage {
    private int minimum = -1;

    int minimum() { return minimum; }
    void restore(int value) { minimum = value; }
    void clear() { minimum = -1; }

    void record(int serverFloor, boolean replace) {
        if (replace) minimum = serverFloor;
        else if (minimum < 0 || serverFloor < 0) minimum = -1;
        else minimum = Math.max(minimum, serverFloor);
    }

    boolean contains(int threshold) { return minimum >= 0 && threshold >= minimum; }
}
