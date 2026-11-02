package com.hippo.ehviewer.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import com.hippo.widget.LoadImageView;
import com.hippo.widget.LoadImageViewNew;
import com.hippo.drawable.PreciselyClipDrawable;

/** A thumbnail preview opened by long-click and closed by a tap or Back. */
public final class HoldThumbnailPreview implements View.OnAttachStateChangeListener {
    private static final long DURATION_MS = 110;
    private RecyclerView mRecyclerView;
    private ImageView mSource;
    private Dialog mDialog;
    private PreviewOverlay mOverlay;
    private ValueAnimator mAnimator;

    public void attachToRecyclerView(RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
        recyclerView.addOnAttachStateChangeListener(this);
    }

    /** Keep the ordinary click handler and use the standard long-click gesture to open. */
    public void bind(ImageView image) {
        image.setOnLongClickListener(view -> show(image));
    }

    /** Called by the existing RecyclerView long-press handler. */
    public boolean show(ImageView source) {
        if ((source instanceof LoadImageView image && image.mFailed)
                || (source instanceof LoadImageViewNew cover && cover.mFailed)) return false;
        Drawable drawable = source.getDrawable();
        if (drawable instanceof TransitionDrawable transition && transition.getNumberOfLayers() > 0) {
            drawable = transition.getDrawable(transition.getNumberOfLayers() - 1);
        }
        if (!source.isAttachedToWindow() || !source.isShown()
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
        source.getLocationOnScreen(sourceLocation);
        float x = sourceLocation[0] + source.getPaddingLeft();
        float y = sourceLocation[1] + source.getPaddingTop();
        RectF imageBounds = new RectF(0, 0, width, height);
        source.getImageMatrix().mapRect(imageBounds);
        imageBounds.offset(x, y);
        RectF clip = new RectF(x, y,
                x + source.getWidth() - source.getPaddingLeft() - source.getPaddingRight(),
                y + source.getHeight() - source.getPaddingTop() - source.getPaddingBottom());

        dismiss();
        mSource = source;
        source.addOnAttachStateChangeListener(this);
        mOverlay = new PreviewOverlay(source, bitmap, imageBounds, clip);
        mOverlay.setOnClickListener(view -> close());
        mDialog = new Dialog(source.getContext(), android.R.style.Theme_Translucent_NoTitleBar) {
            @Override public void cancel() { close(); }
        };
        mDialog.setContentView(mOverlay);
        mDialog.setOnDismissListener(dialog -> {
            if (mDialog == dialog) dismiss();
        });
        Window window = mDialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.getDecorView().setSystemUiVisibility(root.getSystemUiVisibility());
        mDialog.show();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
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

    private void close() {
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
                    if (target == 0f) dismiss();
                }
            }
        });
        mAnimator = animator;
        animator.start();
    }

    public void dismiss() {
        if (mAnimator != null) {
            ValueAnimator animator = mAnimator;
            mAnimator = null;
            animator.cancel();
        }
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.dismiss();
            mDialog = null;
        }
        if (mSource != null) mSource.removeOnAttachStateChangeListener(this);
        mSource = null;
        mOverlay = null;
    }

    public void dispose() {
        dismiss();
        if (mRecyclerView != null) {
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
        private final int[] location = new int[2];
        private float progress;

        PreviewOverlay(View source, Bitmap bitmap, RectF start, RectF startClip) {
            super(source.getContext());
            this.bitmap = bitmap;
            this.start = start;
            this.startClip = startClip;
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.argb(Math.round(255 * progress), 0, 0, 0));
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            target.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2);
            // The dialog content can have different system-bar insets from the source window.
            getLocationOnScreen(location);
            current.set(start);
            current.offset(-location[0], -location[1]);
            clip.set(startClip);
            clip.offset(-location[0], -location[1]);
            interpolate(current, current, target, progress);
            interpolate(clip, clip, target, progress);
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
