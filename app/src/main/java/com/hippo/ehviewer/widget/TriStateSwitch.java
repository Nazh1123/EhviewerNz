package com.hippo.ehviewer.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;

import java.util.function.IntConsumer;

/** Physical left excludes, center is neutral, and right includes. */
public class TriStateSwitch extends View {
    private static final float TRACK_HEIGHT_DP = 18;
    private static final float THUMB_DIAMETER_DP = 24;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path thumb = new Path();
    private final int accent;
    private final int neutral;
    private final int touchSlop;
    private int state;
    private float position;
    private float downX;
    private float downY;
    private float downPosition;
    private boolean dragging;
    private boolean verticalGesture;
    private int clickTarget = 2;
    private ValueAnimator animator;
    private IntConsumer listener;
    private boolean notifyOnReselect;

    public TriStateSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        accent = AttrResources.getAttrColor(context, androidx.appcompat.R.attr.colorAccent);
        neutral = AttrResources.getAttrColor(context, androidx.appcompat.R.attr.colorControlNormal);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setMinimumWidth(Math.round(dp(88)));
        setMinimumHeight(Math.round(dp(48)));
        setClickable(true);
        setFocusable(true);
        setSaveEnabled(false);
        updateStateDescription();
    }

    public int getState() {
        return state;
    }

    public void setState(int value) {
        setState(value, false);
    }

    public void setOnStateChangedListener(IntConsumer listener) {
        this.listener = listener;
    }

    public void setNotifyOnReselect(boolean enabled) {
        notifyOnReselect = enabled;
    }

    private void setState(int value, boolean fromUser) {
        int next = Math.max(-1, Math.min(1, value));
        boolean changed = next != state;
        state = next;
        if (animator != null) animator.cancel();
        if (isLaidOut() && Math.abs(position - next) > 0.001f) {
            animator = ValueAnimator.ofFloat(position, next);
            animator.setDuration(160);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                position = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            position = next;
            invalidate();
        }
        updateStateDescription();
        if ((changed || notifyOnReselect) && fromUser) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            if (listener != null) listener.accept(state);
        }
    }

    private void updateStateDescription() {
        int text = state < 0 ? R.string.search_preset_exclude
                : state > 0 ? R.string.search_preset_include : R.string.search_preset_normal;
        ViewCompat.setStateDescription(this, getContext().getString(text));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float trackHeight() {
        return Math.max(0, Math.min(dp(TRACK_HEIGHT_DP),
                getHeight() - getPaddingTop() - getPaddingBottom()));
    }

    private float thumbDiameter() {
        return Math.max(0, Math.min(dp(THUMB_DIAMETER_DP),
                getHeight() - getPaddingTop() - getPaddingBottom()));
    }

    private float trackWidth() {
        return Math.max(0, Math.min(dp(76), getWidth() - getPaddingLeft() - getPaddingRight()));
    }

    private float centerX() {
        return (getPaddingLeft() + getWidth() - getPaddingRight()) / 2f;
    }

    private float travel() {
        return Math.max(1, (trackWidth() - dp(28)) / 2f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = (getPaddingTop() + getHeight() - getPaddingBottom()) / 2f;
        float cx = centerX();
        float height = trackHeight();
        float width = trackWidth();
        float thumbX = cx + position * travel();
        float radius = thumbDiameter() / 2f;
        float enabledAlpha = isEnabled() ? 1 : 0.38f;
        float active = Math.abs(position);
        int trackColor = ColorUtils.blendARGB(ColorUtils.setAlphaComponent(neutral, 45),
                ColorUtils.setAlphaComponent(accent, 90), Math.max(0, position));
        int thumbColor = ColorUtils.blendARGB(neutral, accent, active);
        if (isPressed() || isFocused()) {
            paint.setColor(ColorUtils.setAlphaComponent(thumbColor, 35));
            canvas.drawCircle(thumbX, cy, dp(22), paint);
        }
        paint.setColor(trackColor);
        paint.setAlpha(Math.round(Color.alpha(trackColor) * enabledAlpha));
        canvas.drawRoundRect(cx - width / 2f, cy - height / 2f,
                cx + width / 2f, cy + height / 2f, height / 2f, height / 2f, paint);
        thumb.reset();
        thumb.setFillType(Path.FillType.EVEN_ODD);
        thumb.addCircle(thumbX, cy, radius, Path.Direction.CW);
        if (position < -0.5f) {
            // A real cutout exposes the tinted track beneath the thumb.
            thumb.addRoundRect(thumbX - dp(5), cy - dp(1), thumbX + dp(5), cy + dp(1),
                    dp(1), dp(1), Path.Direction.CW);
        }
        paint.setColor(thumbColor);
        paint.setAlpha(Math.round(Color.alpha(thumbColor) * enabledAlpha));
        canvas.drawPath(thumb, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animator != null) animator.cancel();
                downX = event.getX();
                downY = event.getY();
                downPosition = position;
                dragging = false;
                verticalGesture = false;
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging && !verticalGesture && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
                    dragging = Math.abs(dx) > Math.abs(dy);
                    verticalGesture = !dragging;
                    if (dragging && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (verticalGesture) setPressed(false);
                }
                if (dragging) {
                    position = Math.max(-1, Math.min(1, downPosition + dx / travel()));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (verticalGesture) {
                    setState(state);
                    return true;
                }
                clickTarget = dragging ? Math.round(position)
                        : Math.max(-1, Math.min(1, Math.round((event.getX() - centerX()) / travel())));
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                setState(state);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        if (!isEnabled()) return false;
        super.performClick();
        int next = clickTarget == 2 ? (state == 1 ? -1 : state + 1) : clickTarget;
        clickTarget = 2;
        setState(next, true);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            setState(state + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, -1, 1, state));
        if (isEnabled()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            if (state < 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (state > -1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (isEnabled()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && state < 1) {
                setState(state + 1, true);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && state > -1) {
                setState(state - 1, true);
                return true;
            }
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                    && arguments != null && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                setState(Math.round(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)), true);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        position = state;
        super.onDetachedFromWindow();
    }
}
