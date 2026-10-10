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

/** Popular actions share the main FAB's actual position, including snackbar/inset movement. */
public class PopularGalleryActions extends FrameLayout {
    private static final long ANIMATE_TIME = 300L;
    private final boolean[] mShown = new boolean[2];
    private final float[] mSlots = {2f, 1f};
    private final float[] mTargetSlots = {2f, 1f};
    private final int[] mParentLocation = new int[2];
    private final int[] mLocation = new int[2];
    private final Rect mContentBounds = new Rect();
    private final Paint mNoticePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private FloatingActionButton mAnchor;
    private FloatingActionButton mMenuUpdates;
    private int mActiveIndex = -1;
    private ValueAnimator mPositionAnimator;
    private ValueAnimator mNoticeAnimator;
    private float mNoticeRadiusFactor;
    private final ViewTreeObserver.OnPreDrawListener mPositionListener = () -> {
        positionButtons();
        if (mNoticeAnimator != null) invalidate();
        return true;
    };

    public PopularGalleryActions(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(INVISIBLE);
            getChildAt(i).setScaleX(0f);
            getChildAt(i).setScaleY(0f);
        }
    }

    public void update(FloatingActionButton anchor, FloatingActionButton menuUpdates,
                       boolean visible, int activeIndex, boolean primaryShown) {
        mAnchor = anchor;
        mMenuUpdates = menuUpdates;
        boolean animate = isLaidOut();
        boolean modeChanged = mActiveIndex != activeIndex;
        boolean replacingModeButton = modeChanged && activeIndex >= 0 && !mShown[activeIndex]
                && mShown[1 - activeIndex];
        if (replacingModeButton) mSlots[activeIndex] = 2f;
        mActiveIndex = activeIndex;
        float[] targets = {2f, 1f};
        if (activeIndex >= 0) targets[activeIndex] = primaryShown ? 1f : 0f;
        if (mTargetSlots[0] != targets[0] || mTargetSlots[1] != targets[1]) {
            cancelPositionAnimation();
            System.arraycopy(targets, 0, mTargetSlots, 0, 2);
            if (animate && visible) {
                float[] starts = mSlots.clone();
                mPositionAnimator = ValueAnimator.ofFloat(0f, 1f);
                mPositionAnimator.setDuration(ANIMATE_TIME);
                // Let a departing button disappear before occupying its slot.
                boolean movingDown = activeIndex >= 0 && !primaryShown;
                mPositionAnimator.setStartDelay((modeChanged && activeIndex >= 0)
                        || (movingDown && anchor.getVisibility() == VISIBLE && anchor.getScaleY() > 0f)
                        ? ANIMATE_TIME : 0L);
                mPositionAnimator.setInterpolator(movingDown
                        ? AnimationUtils.SLOW_FAST_INTERPOLATOR : AnimationUtils.FAST_SLOW_INTERPOLATOR);
                mPositionAnimator.addUpdateListener(animation -> {
                    float fraction = (float) animation.getAnimatedValue();
                    for (int i = 0; i < 2; i++) mSlots[i] = starts[i] + (targets[i] - starts[i]) * fraction;
                    positionButtons();
                });
                mPositionAnimator.start();
            } else {
                System.arraycopy(targets, 0, mSlots, 0, 2);
            }
        }
        for (int i = 0; i < 2; i++) {
            setButtonShown(i, visible && (activeIndex < 0 || activeIndex == i), animate,
                    (modeChanged && activeIndex < 0) || (replacingModeButton && i == activeIndex)
                            ? ANIMATE_TIME : 0L);
        }
        positionButtons();
        updateVisibility();
    }

    /** Move the retained mode button up before the primary FAB reappears underneath it. */
    public long primaryShowDelay() {
        for (int i = 0; i < 2; i++) {
            View button = getChildAt(i);
            if (mSlots[i] < 1f && button.getVisibility() == VISIBLE && button.getScaleY() > 0f) {
                return ANIMATE_TIME;
            }
        }
        return 0L;
    }

    public void snapAbovePrimary() {
        cancelPositionAnimation();
        for (int i = 0; i < 2; i++) {
            mTargetSlots[i] = mSlots[i] = mActiveIndex == i ? 1f : 2f - i;
        }
        positionButtons();
    }

    private void setButtonShown(int index, boolean shown, boolean animate, long delay) {
        View button = getChildAt(index);
        if (mShown[index] == shown) return;
        mShown[index] = shown;
        button.animate().setListener(null);
        button.animate().cancel();
        if (!animate) {
            button.setVisibility(shown ? VISIBLE : INVISIBLE);
            button.setScaleX(shown ? 1f : 0f);
            button.setScaleY(shown ? 1f : 0f);
            button.setRotation(0f);
            return;
        }
        if (shown) {
            if (button.getVisibility() != VISIBLE || delay > 0L) {
                button.setScaleX(0f);
                button.setScaleY(0f);
            }
            button.setRotation(-45f);
            button.setVisibility(VISIBLE);
        }
        button.animate().scaleX(shown ? 1f : 0f).scaleY(shown ? 1f : 0f)
                .rotation(shown ? 0f : button.getRotation())
                .setDuration(ANIMATE_TIME).setStartDelay(shown ? delay : 0L)
                .setInterpolator(shown ? AnimationUtils.FAST_SLOW_INTERPOLATOR
                        : AnimationUtils.SLOW_FAST_INTERPOLATOR)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        if (!mShown[index]) button.setVisibility(INVISIBLE);
                        updateVisibility();
                    }
                }).start();
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
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            button.setX(centerX - button.getWidth() / 2f);
            button.setY(centerY - step * mSlots[i] - button.getHeight() / 2f);
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
        FloatingActionButton button = (FloatingActionButton) getChildAt(0);
        if (!mShown[0] || button.getVisibility() != VISIBLE) button = mMenuUpdates;
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

    private void cancelPositionAnimation() {
        if (mPositionAnimator != null) {
            mPositionAnimator.cancel();
            mPositionAnimator = null;
        }
    }

    public void destroy() {
        cancelNoUpdatesNotice();
        cancelPositionAnimation();
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).animate().setListener(null);
            getChildAt(i).animate().cancel();
        }
        mAnchor = null;
        mMenuUpdates = null;
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
