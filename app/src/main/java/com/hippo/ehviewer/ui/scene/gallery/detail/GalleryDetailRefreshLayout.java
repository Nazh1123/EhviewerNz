package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.hippo.refreshlayout.RefreshLayout;

/** Uses the list footer indicator while leaving detail scrolling and left swipes intact. */
public final class GalleryDetailRefreshLayout extends RefreshLayout {
    private final int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private boolean mPullFromBottom;
    private boolean mHorizontalGesture;

    public GalleryDetailRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean canChildScrollUp() {
        // Detail pages have no pull-down refresh action.
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDownX = event.getX();
            mDownY = event.getY();
            mPullFromBottom = !canChildScrollDown();
            mHorizontalGesture = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float distanceX = Math.abs(event.getX() - mDownX);
            float distanceY = Math.abs(event.getY() - mDownY);
            if (distanceX > mTouchSlop && distanceX > distanceY) {
                mHorizontalGesture = true;
            }
        }
        return mPullFromBottom && !mHorizontalGesture && super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mPullFromBottom && !mHorizontalGesture && super.onTouchEvent(event);
    }
}
