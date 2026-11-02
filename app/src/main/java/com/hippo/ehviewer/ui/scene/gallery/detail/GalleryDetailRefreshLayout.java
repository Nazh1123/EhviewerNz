package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.hippo.refreshlayout.RefreshLayout;

import java.util.function.BooleanSupplier;

/** Uses the list footer indicator while leaving detail scrolling and left swipes intact. */
public final class GalleryDetailRefreshLayout extends RefreshLayout {
    private final int mTouchSlop;
    private final Matrix mPullTransform = new Matrix();
    private BooleanSupplier mAllPreviewsDisplayed = () -> false;
    private float mDownX;
    private float mDownY;
    private boolean mPullFromBottom;
    private boolean mHorizontalGesture;

    public GalleryDetailRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setAllPreviewsDisplayedSupplier(BooleanSupplier supplier) {
        mAllPreviewsDisplayed = supplier;
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
            // The footer has no threshold setter. Scale only the events passed to it,
            // keeping its progress and release threshold in sync and child scrolling intact.
            float distanceMultiplier = mAllPreviewsDisplayed.getAsBoolean() ? 8f / 3f : 4f / 3f;
            mPullTransform.setScale(1f, 1f / distanceMultiplier, mDownX, mDownY);
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float distanceX = Math.abs(event.getX() - mDownX);
            float distanceY = Math.abs(event.getY() - mDownY);
            if (distanceX > mTouchSlop && distanceX > distanceY) {
                mHorizontalGesture = true;
            }
        }
        if (!mPullFromBottom || mHorizontalGesture) return false;
        MotionEvent scaledEvent = MotionEvent.obtain(event);
        scaledEvent.transform(mPullTransform);
        try {
            return super.onInterceptTouchEvent(scaledEvent);
        } finally {
            scaledEvent.recycle();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mPullFromBottom || mHorizontalGesture) return false;
        MotionEvent scaledEvent = MotionEvent.obtain(event);
        scaledEvent.transform(mPullTransform);
        try {
            return super.onTouchEvent(scaledEvent);
        } finally {
            scaledEvent.recycle();
        }
    }
}
