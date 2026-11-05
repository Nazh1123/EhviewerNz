package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.ehviewer.R;

/** Independent active modes, anchored to the same FAB as the popular history actions. */
public final class GalleryListModeActions extends FrameLayout {
    private final int[] anchorLocation = new int[2];
    private final int[] ownLocation = new int[2];
    private FloatingActionButton anchor;
    private boolean primaryShown = true;
    private final ViewTreeObserver.OnPreDrawListener positionListener = () -> { position(); return true; };

    public GalleryListModeActions(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void update(FloatingActionButton primary, boolean visible, boolean sort, boolean downloaded,
                       boolean showPrimary) {
        anchor = primary;
        primaryShown = showPrimary;
        getChildAt(0).setVisibility(visible && sort ? VISIBLE : GONE);
        getChildAt(1).setVisibility(visible && downloaded ? VISIBLE : GONE);
        setVisibility(visible && (sort || downloaded) ? VISIBLE : INVISIBLE);
        position();
    }

    public long primaryShowDelay() { return !primaryShown && getVisibility() == VISIBLE ? 300L : 0L; }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
        position();
    }

    private void position() {
        if (anchor == null || !(anchor.getParent() instanceof View) || getWidth() == 0) return;
        ((View) anchor.getParent()).getLocationInWindow(anchorLocation);
        getLocationInWindow(ownLocation);
        float x = anchorLocation[0] - ownLocation[0] + anchor.getX() + anchor.getWidth() / 2f;
        float y = anchorLocation[1] - ownLocation[1] + anchor.getY() + anchor.getHeight() / 2f;
        float step = getResources().getDimension(R.dimen.fab_size)
                + getResources().getDimension(R.dimen.fab_layout_secondary_margin);
        int slot = primaryShown ? 1 : 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            place(child, x, y, step, slot++);
        }
    }

    static void place(View button, float x, float y, float step, float slot) {
        int rows = Math.max(1, (int) ((y - button.getHeight() / 2f) / step) + 1);
        int column = (int) (slot / rows);
        button.setX(x - column * step - button.getWidth() / 2f);
        button.setY(y - (slot - column * rows) * step - button.getHeight() / 2f);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(positionListener);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(positionListener);
        anchor = null;
        super.onDetachedFromWindow();
    }
}
