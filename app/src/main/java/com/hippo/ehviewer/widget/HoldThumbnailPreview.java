package com.hippo.ehviewer.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.widget.LoadImageView;
import com.hippo.widget.LoadImageViewNew;
import com.hippo.drawable.PreciselyClipDrawable;

/** A window overlay that keeps the original touch target until the finger is released. */
public final class HoldThumbnailPreview extends RecyclerView.SimpleOnItemTouchListener
        implements View.OnTouchListener, View.OnAttachStateChangeListener {
    private static final long DURATION_MS = 110;
    private RecyclerView mRecyclerView;
    private View mTouchHost;
    private View mPendingView;
    private ImageView mSource;
    private ViewGroup mRoot;
    private PreviewOverlay mOverlay;
    private ValueAnimator mAnimator;
    private boolean mEligible;
    private boolean mHolding;
    private float mDownX;
    private float mDownY;
    private final Runnable mShowPending = () -> {
        View pending = mPendingView;
        mPendingView = null;
        if (pending instanceof ImageView image && show(image)) {
            // Cancel the ordinary click/long-click without changing the touch target.
            MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            pending.onTouchEvent(cancel);
            cancel.recycle();
            pending.setPressed(false);
        }
    };

    public void attachToRecyclerView(RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
        recyclerView.addOnItemTouchListener(this);
        recyclerView.addOnAttachStateChangeListener(this);
    }

    /** Detail thumbnails have click handlers, so observe their touch stream directly. */
    public void bind(ImageView image) {
        image.setOnTouchListener(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouch(View view, MotionEvent event) {
        boolean consumed = observe(view, event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mPendingView = view;
            view.postDelayed(mShowPending, ViewConfiguration.getLongPressTimeout());
        }
        return consumed;
    }

    @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView view,
                                                  @NonNull MotionEvent event) {
        return observe(view, event);
    }

    @Override public void onTouchEvent(@NonNull RecyclerView view, @NonNull MotionEvent event) {
        observe(view, event);
    }

    private boolean observe(View host, MotionEvent event) {
        boolean consumed = mHolding;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dismiss();
                mTouchHost = host;
                mEligible = true;
                mDownX = event.getX();
                mDownY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                int slop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
                if (!mHolding && (Math.abs(event.getX() - mDownX) > slop
                        || Math.abs(event.getY() - mDownY) > slop)) {
                    mEligible = false;
                    cancelPending();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mEligible = false;
                cancelPending();
                release();
                break;
        }
        return consumed;
    }

    /** Called by the existing RecyclerView long-press handler. */
    public boolean show(ImageView source) {
        if ((source instanceof LoadImageView image && image.mFailed)
                || (source instanceof LoadImageViewNew cover && cover.mFailed)) return false;
        Drawable drawable = source.getDrawable();
        if (drawable instanceof TransitionDrawable transition && transition.getNumberOfLayers() > 0) {
            drawable = transition.getDrawable(transition.getNumberOfLayers() - 1);
        }
        if (!mEligible || !source.isAttachedToWindow() || !source.isShown()
                || source.getWidth() <= 0 || source.getHeight() <= 0 || drawable == null
                || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0
                || !(source.getRootView() instanceof ViewGroup root)) {
            return false;
        }

        // Copy only the selected image, including sprite clipping. Never share drawable ownership
        // with another ImageView or retain a recycled thumbnail after the source detaches.
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        float scale = Math.min(1f, 2048f / Math.max(width, height));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        Rect bounds = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            drawSnapshot(drawable, new Canvas(bitmap));
        } finally {
            drawable.setBounds(bounds);
        }

        int[] sourceLocation = new int[2];
        int[] rootLocation = new int[2];
        source.getLocationOnScreen(sourceLocation);
        root.getLocationOnScreen(rootLocation);
        float x = sourceLocation[0] - rootLocation[0] + source.getPaddingLeft();
        float y = sourceLocation[1] - rootLocation[1] + source.getPaddingTop();
        RectF imageBounds = new RectF(0, 0, width, height);
        source.getImageMatrix().mapRect(imageBounds);
        imageBounds.offset(x, y);
        RectF clip = new RectF(x, y,
                x + source.getWidth() - source.getPaddingLeft() - source.getPaddingRight(),
                y + source.getHeight() - source.getPaddingTop() - source.getPaddingBottom());

        mSource = source;
        source.addOnAttachStateChangeListener(this);
        mRoot = root;
        mOverlay = new PreviewOverlay(source, bitmap, imageBounds, clip);
        mOverlay.layout(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(mOverlay);
        mHolding = true;
        if (mTouchHost != null && mTouchHost.getParent() != null) {
            mTouchHost.getParent().requestDisallowInterceptTouchEvent(true);
        }
        animateTo(1f);
        return true;
    }

    private static void drawSnapshot(Drawable drawable, Canvas canvas) {
        if (drawable instanceof PreciselyClipDrawable clipped) {
            // Keep the sprite tile's clip and the child bounds calculated by the wrapper.
            int save = canvas.save();
            try {
                canvas.clipRect(clipped.getBounds());
                drawSnapshot(clipped.getDrawable(), canvas);
            } finally {
                canvas.restoreToCount(save);
            }
        } else if (drawable instanceof BitmapDrawable bitmapDrawable
                && bitmapDrawable.getBitmap().getConfig() == Bitmap.Config.HARDWARE) {
            // A bitmap-backed Canvas cannot draw the hardware-decoded list covers.
            // Copy only for this snapshot, leaving the loader's shared bitmap untouched.
            Bitmap software = bitmapDrawable.getBitmap().copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) throw new IllegalStateException("Unable to copy thumbnail bitmap");
            try {
                canvas.drawBitmap(software, null, bitmapDrawable.getBounds(), bitmapDrawable.getPaint());
            } finally {
                software.recycle();
            }
        } else {
            drawable.draw(canvas);
        }
    }

    private void release() {
        if (!mHolding) return;
        mHolding = false;
        if (mTouchHost != null && mTouchHost.getParent() != null) {
            mTouchHost.getParent().requestDisallowInterceptTouchEvent(false);
        }
        if (mOverlay != null) animateTo(0f);
    }

    private void animateTo(float target) {
        if (mAnimator != null) {
            ValueAnimator previous = mAnimator;
            mAnimator = null;
            previous.cancel();
        }
        PreviewOverlay overlay = mOverlay;
        ValueAnimator animator = ValueAnimator.ofFloat(overlay.progress, target);
        animator.setDuration(Math.max(1, Math.round(DURATION_MS * Math.abs(target - overlay.progress))));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            overlay.progress = (float) value.getAnimatedValue();
            overlay.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (mAnimator == animation) {
                    mAnimator = null;
                    if (target == 0f) removeOverlay();
                }
            }
        });
        mAnimator = animator;
        animator.start();
    }

    private void cancelPending() {
        if (mPendingView != null) {
            mPendingView.removeCallbacks(mShowPending);
            mPendingView = null;
        }
    }

    private void removeOverlay() {
        if (mSource != null) mSource.removeOnAttachStateChangeListener(this);
        if (mRoot != null && mOverlay != null) mRoot.getOverlay().remove(mOverlay);
        mSource = null;
        mRoot = null;
        mOverlay = null;
    }

    public void dismiss() {
        mEligible = false;
        cancelPending();
        mHolding = false;
        if (mAnimator != null) {
            ValueAnimator animator = mAnimator;
            mAnimator = null;
            animator.cancel();
        }
        if (mTouchHost != null && mTouchHost.getParent() != null) {
            mTouchHost.getParent().requestDisallowInterceptTouchEvent(false);
        }
        mTouchHost = null;
        removeOverlay();
    }

    public void dispose() {
        dismiss();
        if (mRecyclerView != null) {
            mRecyclerView.removeOnItemTouchListener(this);
            mRecyclerView.removeOnAttachStateChangeListener(this);
            mRecyclerView = null;
        }
    }

    @Override public void onViewAttachedToWindow(View view) {}
    @Override public void onViewDetachedFromWindow(View view) { dismiss(); }

    private static final class PreviewOverlay extends View {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF start;
        private final RectF startClip;
        private final RectF target = new RectF();
        private final RectF current = new RectF();
        private final RectF clip = new RectF();
        private float progress;

        PreviewOverlay(View source, Bitmap bitmap, RectF start, RectF startClip) {
            super(source.getContext());
            this.bitmap = bitmap;
            this.start = start;
            this.startClip = startClip;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.argb(Math.round(255 * progress), 0, 0, 0));
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            target.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2);
            interpolate(current, start, target, progress);
            interpolate(clip, startClip, target, progress);
            int save = canvas.save();
            canvas.clipRect(clip);
            canvas.drawBitmap(bitmap, null, current, paint);
            canvas.restoreToCount(save);
        }

        private static void interpolate(RectF result, RectF start, RectF end, float fraction) {
            result.set(start.left + (end.left - start.left) * fraction,
                    start.top + (end.top - start.top) * fraction,
                    start.right + (end.right - start.right) * fraction,
                    start.bottom + (end.bottom - start.bottom) * fraction);
        }
    }
}
