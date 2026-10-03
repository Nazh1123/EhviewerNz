package com.hippo.ehviewer;

import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderKeyMap;

/** Resolves the existing reading behavior for the default labels in the editor. */
public final class ReaderKeyDefaults {
    private ReaderKeyDefaults() { }

    public static int action(int region, int gesture, int direction, boolean quick,
            boolean directSave, boolean saveTurn) {
        boolean left = region == ReaderKeyMap.LEFT_TOP || region == ReaderKeyMap.LEFT_BOTTOM;
        boolean side = region >= 0 && region <= ReaderKeyMap.RIGHT_BOTTOM;
        if (gesture == ReaderKeyMap.TAP) {
            if (side) return left ? ReaderKeyMap.LEFT : ReaderKeyMap.RIGHT;
            return region == ReaderKeyMap.CENTER_MENU ? ReaderKeyMap.MENU : ReaderKeyMap.CONTROLS;
        }
        if (gesture == ReaderKeyMap.DOUBLE_TAP) return side && quick ? ReaderKeyMap.NONE : ReaderKeyMap.ZOOM;
        boolean nextArea = direction == GalleryView.LAYOUT_RIGHT_TO_LEFT ? left : !left;
        if (side && nextArea && directSave) return saveTurn ? ReaderKeyMap.SAVE_NEXT : ReaderKeyMap.SAVE;
        return ReaderKeyMap.PAGE_MENU;
    }
}
