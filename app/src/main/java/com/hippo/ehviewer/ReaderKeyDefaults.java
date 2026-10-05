package com.hippo.ehviewer;

import com.hippo.lib.glgallery.ReaderKeyMap;

/** Default labels use the same actions as the reader. */
public final class ReaderKeyDefaults {
    private ReaderKeyDefaults() { }

    /** Fixed defaults for key profiles after retiring the global switches. */
    public static int action(int region, int gesture) {
        return ReaderKeyMap.defaultAction(region, gesture);
    }
}
