/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

/**
 * Observes touch events without taking them away from vertical scrolling and child views.
 * A left swipe is reported only after the finger is lifted and the full distance threshold
 * has been reached.
 */
public class GalleryDetailScrollView extends ScrollView {

    private static final float MIN_DISTANCE_FRACTION = 0.25f;
    private static final float HORIZONTAL_BIAS = 1.5f;
    private static final float PULL_UP_TRIGGER_DP = 64f;
    private static final float PULL_UP_CANCEL_DP = 16f;
    private static final float PULL_UP_REARM_DP = 20f;
    private static final float PULL_UP_MAX_OFFSET_DP = 48f;
    private static final float PULL_UP_RESISTANCE = 0.5f;
    private static final float VERTICAL_BIAS = 1.25f;
    private static final long PULL_UP_SETTLE_DURATION_MS = 180L;

    private final int mTouchSlop;
    private final float mPullUpTriggerDistance;
    private final float mPullUpCancelDistance;
    private final float mPullUpRearmDistance;
    private final float mPullUpMaxOffset;

    @Nullable
    private OnSwipeLeftListener mOnSwipeLeftListener;
    @Nullable
    private OnPullUpPreviewListener mOnPullUpPreviewListener;
    @Nullable
    private View mSwipeExclusionView;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private float mDownX;
    private float mDownY;
    private boolean mTrackingSwipe;
    private boolean mHadMultiplePointers;
    private boolean mSwipeReady;
    private boolean mSwipeReadyChanged;
    private float mPullUpDownRawX;
    private float mPullUpDownRawY;
    private boolean mPullUpEnabled;
    private boolean mTrackingPullUp;
    private boolean mPullUpHadMultiplePointers;
    private boolean mPullUpReady;
    private boolean mPullUpReadyChanged;
    private boolean mPullUpHasArmed;
    private float mPullUpStateAnchorRawY;

    public GalleryDetailScrollView(Context context) {
        this(context, null);
    }

    public GalleryDetailScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GalleryDetailScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        mPullUpTriggerDistance = PULL_UP_TRIGGER_DP * density;
        mPullUpCancelDistance = PULL_UP_CANCEL_DP * density;
        mPullUpRearmDistance = PULL_UP_REARM_DP * density;
        mPullUpMaxOffset = PULL_UP_MAX_OFFSET_DP * density;
    }

    public void setOnSwipeLeftListener(@Nullable OnSwipeLeftListener listener) {
        mOnSwipeLeftListener = listener;
    }

    public void setSwipeExclusionView(@Nullable View view) {
        mSwipeExclusionView = view;
    }

    public void setOnPullUpPreviewListener(@Nullable OnPullUpPreviewListener listener) {
        mOnPullUpPreviewListener = listener;
    }

    public void setPullUpPreviewEnabled(boolean enabled) {
        if (mPullUpEnabled == enabled) {
            return;
        }
        mPullUpEnabled = enabled;
        if (!enabled) {
            boolean wasReady = mPullUpReady;
            mPullUpReady = false;
            mPullUpReadyChanged = false;
            resetPullUpTracking();
            settlePullUpTranslation();
            if (wasReady && mOnPullUpPreviewListener != null) {
                mOnPullUpPreviewListener.onPullUpPreviewReadyChanged(false);
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        boolean completedSwipe = trackSwipe(event);
        boolean swipeReadyChanged = mSwipeReadyChanged;
        boolean swipeReady = mSwipeReady;
        boolean completedPullUp = trackPullUp(event);
        boolean pullUpReadyChanged = mPullUpReadyChanged;
        boolean pullUpReady = mPullUpReady;
        boolean handled = super.dispatchTouchEvent(event);
        if (mOnSwipeLeftListener != null) {
            if (swipeReadyChanged) {
                mOnSwipeLeftListener.onSwipeLeftReadyChanged(swipeReady);
            }
            if (completedSwipe) {
                mOnSwipeLeftListener.onSwipeLeft();
            }
        }
        if (mOnPullUpPreviewListener != null) {
            if (pullUpReadyChanged) {
                mOnPullUpPreviewListener.onPullUpPreviewReadyChanged(pullUpReady);
            }
            if (completedPullUp) {
                mOnPullUpPreviewListener.onPullUpPreview();
            }
        }
        return handled;
    }

    private boolean trackPullUp(MotionEvent event) {
        mPullUpReadyChanged = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().cancel();
                setTranslationY(0f);
                mPullUpDownRawX = event.getRawX();
                mPullUpDownRawY = event.getRawY();
                mTrackingPullUp = mPullUpEnabled && isAtBottom();
                mPullUpHadMultiplePointers = false;
                setPullUpReady(false);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mPullUpHadMultiplePointers = true;
                setPullUpReady(false);
                settlePullUpTranslation();
                break;
            case MotionEvent.ACTION_MOVE:
                updatePullUp(event);
                break;
            case MotionEvent.ACTION_UP:
                updatePullUp(event);
                boolean completed = mPullUpReady;
                if (completed) {
                    // Completion owns the hint fade-out, so don't emit a separate retreat.
                    mPullUpReady = false;
                    mPullUpReadyChanged = false;
                } else {
                    setPullUpReady(false);
                }
                resetPullUpTracking();
                settlePullUpTranslation();
                return completed;
            case MotionEvent.ACTION_CANCEL:
                setPullUpReady(false);
                resetPullUpTracking();
                settlePullUpTranslation();
                break;
            default:
                break;
        }
        return false;
    }

    private void updatePullUp(MotionEvent event) {
        if (!mTrackingPullUp || mPullUpHadMultiplePointers) {
            setPullUpReady(false);
            return;
        }

        float distanceX = event.getRawX() - mPullUpDownRawX;
        float distanceY = mPullUpDownRawY - event.getRawY();
        boolean verticalPull = distanceY > mTouchSlop
                && distanceY > Math.abs(distanceX) * VERTICAL_BIAS;
        if (!verticalPull) {
            animate().cancel();
            setTranslationY(0f);
            if (mPullUpReady) {
                setPullUpReady(false);
                mPullUpStateAnchorRawY = event.getRawY();
            } else if (mPullUpHasArmed) {
                mPullUpStateAnchorRawY = Math.max(
                        mPullUpStateAnchorRawY, event.getRawY());
            }
            return;
        }

        float offset = Math.min(mPullUpMaxOffset,
                (distanceY - mTouchSlop) * PULL_UP_RESISTANCE);
        animate().cancel();
        setTranslationY(-offset);
        updatePullUpReadyState(event.getRawY(), distanceY);
    }

    private void updatePullUpReadyState(float rawY, float distanceY) {
        if (!mPullUpHasArmed) {
            if (distanceY >= mPullUpTriggerDistance) {
                mPullUpHasArmed = true;
                mPullUpStateAnchorRawY = rawY;
                setPullUpReady(true);
            }
            return;
        }

        if (mPullUpReady) {
            // Track the furthest upward point. A short reversal always cancels, even if the
            // user pulled far beyond the initial activation distance.
            mPullUpStateAnchorRawY = Math.min(mPullUpStateAnchorRawY, rawY);
            if (rawY - mPullUpStateAnchorRawY >= mPullUpCancelDistance) {
                mPullUpStateAnchorRawY = rawY;
                setPullUpReady(false);
            }
        } else {
            // After cancellation, track the furthest downward point and re-arm on an upward
            // reversal without requiring the finger to return to the original activation line.
            mPullUpStateAnchorRawY = Math.max(mPullUpStateAnchorRawY, rawY);
            if (mPullUpStateAnchorRawY - rawY >= mPullUpRearmDistance) {
                mPullUpStateAnchorRawY = rawY;
                setPullUpReady(true);
            }
        }
    }

    private boolean isAtBottom() {
        if (!canScrollVertically(1)) {
            return true;
        }
        View child = getChildAt(0);
        if (child == null) {
            return true;
        }
        int viewportBottom = getScrollY() + getHeight() - getPaddingBottom();
        return viewportBottom >= child.getHeight() - mTouchSlop;
    }

    private void setPullUpReady(boolean ready) {
        if (mPullUpReady != ready) {
            mPullUpReady = ready;
            mPullUpReadyChanged = true;
        }
    }

    private void resetPullUpTracking() {
        mTrackingPullUp = false;
        mPullUpHadMultiplePointers = false;
        mPullUpHasArmed = false;
        mPullUpStateAnchorRawY = 0f;
    }

    private void settlePullUpTranslation() {
        animate().cancel();
        if (getTranslationY() != 0f) {
            animate()
                    .translationY(0f)
                    .setDuration(PULL_UP_SETTLE_DURATION_MS)
                    .start();
        }
    }

    private boolean trackSwipe(MotionEvent event) {
        mSwipeReadyChanged = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = event.getPointerId(0);
                mDownX = event.getX(0);
                mDownY = event.getY(0);
                mTrackingSwipe = !isPointInsideExclusionView(mDownX, mDownY);
                mHadMultiplePointers = false;
                setSwipeReady(false);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mHadMultiplePointers = true;
                setSwipeReady(false);
                break;
            case MotionEvent.ACTION_MOVE:
                updateSwipeReady(event);
                break;
            case MotionEvent.ACTION_UP:
                updateSwipeReady(event);
                boolean completed = mSwipeReady;
                if (completed) {
                    // Completion owns the indicator fade-out, so don't emit a separate retreat.
                    mSwipeReady = false;
                    mSwipeReadyChanged = false;
                } else {
                    setSwipeReady(false);
                }
                resetSwipeTracking();
                return completed;
            case MotionEvent.ACTION_CANCEL:
                setSwipeReady(false);
                resetSwipeTracking();
                break;
            default:
                break;
        }
        return false;
    }

    private void updateSwipeReady(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mActivePointerId);
        if (!mTrackingSwipe || mHadMultiplePointers || pointerIndex < 0) {
            setSwipeReady(false);
            return;
        }

        float distanceX = event.getX(pointerIndex) - mDownX;
        float distanceY = event.getY(pointerIndex) - mDownY;
        float requiredDistance = Math.max(
                getWidth() * MIN_DISTANCE_FRACTION, mTouchSlop * 8f);
        setSwipeReady(distanceX <= -requiredDistance
                && Math.abs(distanceX) > Math.abs(distanceY) * HORIZONTAL_BIAS);
    }

    private void setSwipeReady(boolean ready) {
        if (mSwipeReady != ready) {
            mSwipeReady = ready;
            mSwipeReadyChanged = true;
        }
    }

    private boolean isPointInsideExclusionView(float x, float y) {
        if (mSwipeExclusionView == null || !mSwipeExclusionView.isShown()) {
            return false;
        }

        int[] scrollLocation = new int[2];
        int[] exclusionLocation = new int[2];
        getLocationOnScreen(scrollLocation);
        mSwipeExclusionView.getLocationOnScreen(exclusionLocation);
        float screenX = scrollLocation[0] + x;
        float screenY = scrollLocation[1] + y;
        return screenX >= exclusionLocation[0]
                && screenX < exclusionLocation[0] + mSwipeExclusionView.getWidth()
                && screenY >= exclusionLocation[1]
                && screenY < exclusionLocation[1] + mSwipeExclusionView.getHeight();
    }

    private void resetSwipeTracking() {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mTrackingSwipe = false;
        mHadMultiplePointers = false;
    }

    public interface OnSwipeLeftListener {
        void onSwipeLeftReadyChanged(boolean ready);

        void onSwipeLeft();
    }

    public interface OnPullUpPreviewListener {
        void onPullUpPreviewReadyChanged(boolean ready);

        void onPullUpPreview();
    }
}
