package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;

/** A stack of profile cards, with the active profile's ordinal inside the front card. */
public final class ReaderKeyProfileDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path backCard = new Path();
    private final String number;
    private final int size;

    public ReaderKeyProfileDrawable(Context context, int ordinal) {
        number = String.valueOf(Math.max(1, ordinal));
        size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        paint.setColor(AttrResources.getAttrColor(context, R.attr.drawableColorPrimary));
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        backCard.moveTo(3, 6);
        backCard.lineTo(3, 21);
        backCard.quadTo(3, 22, 4, 22);
        backCard.lineTo(18, 22);
    }

    @Override public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        // Keep the full touch target, but balance the detailed card with the simpler quick icons.
        canvas.scale(.85f, .85f, 12, 12);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(backCard, paint);
        canvas.drawRoundRect(6, 2, 22, 19, 1.5f, 1.5f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(12);
        float width = paint.measureText(number);
        if (width > 12) paint.setTextSize(12 * 12 / width);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(number, 14, 10.5f - (metrics.ascent + metrics.descent) / 2, paint);
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
