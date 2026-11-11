package com.hippo.ehviewer.ui;

import com.hippo.ehviewer.R;

import java.util.List;
import java.util.Set;

/** Selects an inclusive range in the displayed gallery order, skipping non-gallery rows. */
public final class GalleryRangeSelection {
    private boolean pending;
    private Long anchorGid;

    public boolean isPending() {
        return pending;
    }

    public Long getAnchorGid() {
        return anchorGid;
    }

    public void clear() {
        pending = false;
        anchorGid = null;
    }

    public int getHint() {
        return anchorGid == null ? R.string.select_label_range_start
                : R.string.select_label_range_end;
    }

    public void start(List<Long> displayedGids, Set<Long> selectedGids) {
        clear();
        int first = -1;
        int last = -1;
        for (int i = 0; i < displayedGids.size(); i++) {
            Long gid = displayedGids.get(i);
            if (gid != null && selectedGids.contains(gid)) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first >= 0 && last > first) {
            select(displayedGids, selectedGids, first, last);
        } else if (displayedGids.stream().anyMatch(gid -> gid != null)) {
            pending = true;
            anchorGid = first >= 0 ? displayedGids.get(first) : null;
        }
    }

    public void onGalleryClick(long gid, List<Long> displayedGids, Set<Long> selectedGids) {
        int position = displayedGids.indexOf(gid);
        if (!pending || position < 0) return;
        int anchor = anchorGid != null ? displayedGids.indexOf(anchorGid) : -1;
        if (anchor < 0) {
            // A removed/filtered anchor is replaced by the next explicitly clicked gallery.
            anchorGid = gid;
            selectedGids.add(gid);
        } else {
            select(displayedGids, selectedGids, anchor, position);
            clear();
        }
    }

    private static void select(List<Long> displayedGids, Set<Long> selectedGids,
                               int first, int last) {
        for (int i = Math.min(first, last); i <= Math.max(first, last); i++) {
            Long gid = displayedGids.get(i);
            if (gid != null) selectedGids.add(gid);
        }
    }
}
