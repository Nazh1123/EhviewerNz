package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

/** Independent active modes share the popular buttons' animations and primary-FAB handoff. */
public final class GalleryListModeActions extends PopularGalleryActions {
    public GalleryListModeActions(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void update(FloatingActionButton primary, boolean visible, boolean sort, boolean downloaded,
                       boolean showPrimary) {
        updateIndependentModes(primary, visible, sort, downloaded, showPrimary);
    }

    @Override protected boolean wrapsSlots() { return true; }

    static void place(View button, float x, float y, float step, float slot) {
        int rows = Math.max(1, (int) ((y - button.getHeight() / 2f) / step) + 1);
        int column = (int) (slot / rows);
        button.setX(x - column * step - button.getWidth() / 2f);
        button.setY(y - (slot - column * rows) * step - button.getHeight() / 2f);
    }

}
