package com.hippo.drawable;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.hippo.ehviewer.R;

/** A compact count fraction with upright numbers on either side of a diagonal slash. */
public final class GalleryCountDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private final int intrinsicSize;
    private final int baseColor;
    private ColorStateList tint;
    private String filtered = "0";
    private String total = "0";

    public GalleryCountDrawable(Context context) {
        intrinsicSize = Math.round(32 * context.getResources().getDisplayMetrics().density);
        baseColor = context.getColor(R.color.primary_drawable_dark);
        paint.setColor(baseColor);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    public void setCounts(int filteredCount, int totalCount) {
        String nextFiltered = Integer.toString(filteredCount);
        String nextTotal = Integer.toString(totalCount);
        if (filtered.equals(nextFiltered) && total.equals(nextTotal)) return;
        filtered = nextFiltered;
        total = nextTotal;
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        if (size <= 0) return;
        int saved = canvas.save();
        canvas.translate(bounds.exactCenterX() - size / 2f, bounds.exactCenterY() - size / 2f);
        canvas.scale(size / 32f, size / 32f);
        paint.setTextSize(16f);
        float width = Math.max(paint.measureText(filtered), paint.measureText(total));
        paint.getTextBounds(filtered, 0, filtered.length(), textBounds);
        float height = textBounds.height();
        paint.getTextBounds(total, 0, total.length(), textBounds);
        height = Math.max(height, textBounds.height());
        // Keep the same large type size for both counts, shrinking only for longer numbers.
        paint.setTextSize(16f * Math.min(1f, Math.min(15f / width, 12f / height)));
        drawCount(canvas, filtered, 8.5f, 7f);
        drawCount(canvas, total, 23.5f, 25f);
        paint.setStrokeWidth(1.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(8f, 29f, 24f, 3f, paint);
        canvas.restoreToCount(saved);
    }

    private void drawCount(Canvas canvas, String count, float centerX, float centerY) {
        paint.getTextBounds(count, 0, count.length(), textBounds);
        canvas.drawText(count, centerX - paint.measureText(count) / 2f,
                centerY - textBounds.exactCenterY(), paint);
    }

    @Override public int getIntrinsicWidth() { return intrinsicSize; }
    @Override public int getIntrinsicHeight() { return intrinsicSize; }
    @Override public int getAlpha() { return paint.getAlpha(); }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public void setTintList(ColorStateList tint) {
        this.tint = tint;
        onStateChange(getState());
    }

    @Override protected boolean onStateChange(int[] state) {
        int color = tint == null ? baseColor : tint.getColorForState(state, tint.getDefaultColor());
        if (paint.getColor() == color) return false;
        paint.setColor(color);
        invalidateSelf();
        return true;
    }

    @Override public boolean isStateful() { return tint != null && tint.isStateful(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
