package com.hippo.ehviewer.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.ehviewer.R;
import com.hippo.lib.yorozuya.AnimationUtils;

import java.util.Arrays;

/** Positions the gallery actions as one stack anchored to the main FAB. */
public final class GalleryListActions extends FrameLayout {
    private static final long ANIMATION_DURATION = 300L;
    private static final int HIDDEN = -1;
    private final int[] mParentLocation = new int[2];
    private final int[] mLocation = new int[2];
    private final Rect mContentBounds = new Rect();
    private final Paint mNoticePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] mSlots;
    private int[] mTargetSlots;
    private int mRows = 1;
    private FloatingActionButton mAnchor;
    private FloatingActionButton mMenuUpdates;
    private ValueAnimator mTransition;
    private long mPrimaryClearAt;
    private ValueAnimator mNoticeAnimator;
    private float mNoticeRadiusFactor;
    private final ViewTreeObserver.OnPreDrawListener mPositionListener = () -> {
        positionButtons();
        if (mNoticeAnimator != null) invalidate();
        return true;
    };

    public GalleryListActions(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        mSlots = new float[getChildCount()];
        mTargetSlots = new int[getChildCount()];
        Arrays.fill(mTargetSlots, HIDDEN);
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            button.setEnabled(false);
            setButtonScale(button, 0f);
        }
    }

    public void update(FloatingActionButton anchor, FloatingActionButton menuUpdates,
                       boolean primaryShown, boolean animate, boolean waitForOtherActions,
                       boolean sort, boolean downloaded, boolean refresh, boolean previous, boolean updates) {
        mAnchor = anchor;
        mMenuUpdates = menuUpdates;
        // Child order in scene_gallery_list is the display order, starting nearest the primary FAB.
        boolean[] shown = {sort, downloaded, refresh, previous, updates};
        int[] targets = new int[shown.length];
        int slot = primaryShown ? 1 : 0;
        for (int i = 0; i < shown.length; i++) targets[i] = shown[i] ? slot++ : HIDDEN;
        if (Arrays.equals(targets, mTargetSlots) && animate) return;

        // An interrupted handoff must still clear the slot before the scheduled primary FAB.
        boolean interruptedPrimaryShow = primaryShown && primaryShowDelay() > 0L;
        cancelTransition();
        mTargetSlots = targets;
        for (int i = 0; i < targets.length; i++) getChildAt(i).setEnabled(shown[i]);
        if (!isLaidOut() || !animate || interruptedPrimaryShow) {
            applyTargets();
            return;
        }

        // Shrink outgoing buttons in place, then move the remaining stack as a group.
        // Only reveal new buttons once their slots are free.
        float[] startSlots = mSlots.clone();
        float[] startScales = new float[targets.length];
        boolean exiting = false;
        boolean moving = false;
        boolean entering = false;
        boolean occupiesPrimaryOnExit = false;
        boolean movesAwayFromPrimary = false;
        for (int i = 0; i < targets.length; i++) {
            float scale = getChildAt(i).getScaleY();
            startScales[i] = scale;
            if (targets[i] == HIDDEN) {
                exiting |= scale > 0f;
                occupiesPrimaryOnExit |= scale > 0f && startSlots[i] < 1f;
            } else {
                entering |= scale < 1f;
                moving |= scale > 0f && startSlots[i] != targets[i];
                movesAwayFromPrimary |= scale > 0f && startSlots[i] < 1f && targets[i] >= 1;
            }
        }
        long exitEnd = exiting ? ANIMATION_DURATION : 0L;
        long primaryHide = !primaryShown && slot > 0 && anchor.getVisibility() == VISIBLE && anchor.getScaleY() > 0f
                ? ANIMATION_DURATION : 0L;
        long moveStart = Math.max(exitEnd, primaryHide);
        long moveEnd = moveStart + (moving ? ANIMATION_DURATION : 0L);
        long enterStart = Math.max(moveEnd, waitForOtherActions ? ANIMATION_DURATION : 0L);
        long duration = entering ? enterStart + ANIMATION_DURATION : moveEnd;
        mPrimaryClearAt = Math.max(occupiesPrimaryOnExit ? exitEnd : 0L, movesAwayFromPrimary ? moveEnd : 0L);
        if (duration == 0L) {
            applyTargets();
            return;
        }

        mTransition = ValueAnimator.ofFloat(0f, duration);
        mTransition.setInterpolator(new LinearInterpolator());
        mTransition.setDuration(duration);
        mTransition.addUpdateListener(animation -> {
            float elapsed = (float) animation.getAnimatedValue();
            float exit = AnimationUtils.SLOW_FAST_INTERPOLATOR.getInterpolation(progress(elapsed, 0L));
            float move = (primaryShown ? AnimationUtils.FAST_SLOW_INTERPOLATOR
                    : AnimationUtils.SLOW_FAST_INTERPOLATOR).getInterpolation(progress(elapsed, moveStart));
            // Crossing a column boundary is a layout change. Move the stack together on one frame.
            if (move < 1f && crossesColumn(startSlots, startScales, targets)) move = 0f;
            float enter = AnimationUtils.FAST_SLOW_INTERPOLATOR.getInterpolation(progress(elapsed, enterStart));
            for (int i = 0; i < targets.length; i++) {
                View button = getChildAt(i);
                if (targets[i] == HIDDEN) {
                    setButtonScale(button, startScales[i] * (1f - exit));
                } else {
                    mSlots[i] = startScales[i] == 0f ? targets[i]
                            : startSlots[i] + (targets[i] - startSlots[i]) * move;
                    setButtonScale(button, startScales[i] + (1f - startScales[i]) * enter);
                }
            }
            positionButtons();
            updateVisibility();
        });
        mTransition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                mTransition = null;
                applyTargets();
            }
        });
        mTransition.start();
    }

    private static float progress(float elapsed, long start) {
        return Math.min(1f, Math.max(0f, (elapsed - start) / ANIMATION_DURATION));
    }

    private boolean crossesColumn(float[] starts, float[] scales, int[] targets) {
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != HIDDEN && scales[i] > 0f
                    && (int) (starts[i] / mRows) != targets[i] / mRows) return true;
        }
        return false;
    }

    /** Lets the primary FAB wait until the stack has cleared its position. */
    public long primaryShowDelay() {
        return mTransition == null ? 0L : Math.max(0L, mPrimaryClearAt - mTransition.getCurrentPlayTime());
    }

    private void applyTargets() {
        for (int i = 0; i < mTargetSlots.length; i++) {
            boolean shown = mTargetSlots[i] != HIDDEN;
            if (shown) mSlots[i] = mTargetSlots[i];
            setButtonScale(getChildAt(i), shown ? 1f : 0f);
        }
        positionButtons();
        updateVisibility();
    }

    private static void setButtonScale(View button, float scale) {
        button.setScaleX(scale);
        button.setScaleY(scale);
        button.setRotation(-45f * (1f - scale));
        button.setVisibility(scale > 0f ? VISIBLE : INVISIBLE);
    }

    private void updateVisibility() {
        boolean visible = mNoticeAnimator != null;
        for (int i = 0; i < getChildCount(); i++) visible |= getChildAt(i).getVisibility() == VISIBLE;
        setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        }
        positionButtons();
    }

    private void positionButtons() {
        if (mAnchor == null || !(mAnchor.getParent() instanceof View) || getWidth() == 0) return;
        ((View) mAnchor.getParent()).getLocationInWindow(mParentLocation);
        getLocationInWindow(mLocation);
        float centerX = mParentLocation[0] - mLocation[0] + mAnchor.getX() + mAnchor.getWidth() / 2f;
        float centerY = mParentLocation[1] - mLocation[1] + mAnchor.getY() + mAnchor.getHeight() / 2f;
        float diameter = getResources().getDimensionPixelSize(R.dimen.fab_size);
        if (mAnchor.getContentRect(mContentBounds)) diameter = mContentBounds.height();
        float step = diameter + getResources().getDimensionPixelSize(R.dimen.fab_layout_secondary_margin);
        mRows = Math.max(1, (int) ((centerY - getChildAt(0).getHeight() / 2f) / step) + 1);
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            float slot = mSlots[i];
            int column = (int) (slot / mRows);
            button.setX(centerX - column * step - button.getWidth() / 2f);
            button.setY(centerY - (slot - column * mRows) * step - button.getHeight() / 2f);
        }
    }

    public void showNoUpdatesNotice() {
        cancelNoUpdatesNotice();
        mNoticeAnimator = ValueAnimator.ofFloat(2.5f, 1f);
        mNoticeAnimator.setDuration(1500L);
        mNoticeAnimator.setInterpolator(new LinearInterpolator());
        mNoticeAnimator.addUpdateListener(animation -> {
            mNoticeRadiusFactor = (float) animation.getAnimatedValue();
            invalidate();
        });
        mNoticeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                mNoticeAnimator = null;
                mNoticeRadiusFactor = 0f;
                invalidate();
                updateVisibility();
            }
        });
        setVisibility(VISIBLE);
        mNoticeAnimator.start();
    }

    public void cancelNoUpdatesNotice() {
        if (mNoticeAnimator != null) mNoticeAnimator.cancel();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mNoticeRadiusFactor == 0f) return;
        FloatingActionButton button = findViewById(R.id.popular_updates);
        if (!button.isEnabled() || button.getVisibility() != VISIBLE) button = mMenuUpdates;
        if (button == null || button.getVisibility() != VISIBLE || button.getBackgroundTintList() == null
                || !button.getContentRect(mContentBounds)) return;
        ((View) button.getParent()).getLocationInWindow(mParentLocation);
        getLocationInWindow(mLocation);
        float x = mParentLocation[0] - mLocation[0] + button.getX() + button.getWidth() / 2f;
        float y = mParentLocation[1] - mLocation[1] + button.getY() + button.getHeight() / 2f;
        mNoticePaint.setColor(button.getBackgroundTintList().getColorForState(
                button.getDrawableState(), button.getBackgroundTintList().getDefaultColor()));
        mNoticePaint.setAlpha(65);
        canvas.drawCircle(x, y, mContentBounds.width() / 2f * mNoticeRadiusFactor, mNoticePaint);
    }

    private void cancelTransition() {
        if (mTransition != null) {
            mTransition.removeAllListeners();
            mTransition.cancel();
            mTransition = null;
        }
    }

    public void destroy() {
        cancelNoUpdatesNotice();
        cancelTransition();
        Arrays.fill(mTargetSlots, HIDDEN);
        mAnchor = null;
        mMenuUpdates = null;
        applyTargets();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(mPositionListener);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mPositionListener);
        destroy();
        super.onDetachedFromWindow();
    }
}
