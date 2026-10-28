package com.hippo.ehviewer.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/** A visual replacement only: the Editable still contains the complete English query. */
final class SearchTagChipSpan extends ReplacementSpan {
    final String label;
    final char operator;
    private final float density;
    private final int accent;
    private final float maxLabelWidth;

    SearchTagChipSpan(String label, char operator, float density, int accent, float maxLabelWidth) {
        this.label = label;
        this.operator = operator;
        this.density = density;
        this.accent = accent;
        this.maxLabelWidth = maxLabelWidth;
    }

    float actionWidth() { return 32 * density; }

    private CharSequence displayLabel(Paint paint) {
        return TextUtils.ellipsize(label, new TextPaint(paint), maxLabelWidth, TextUtils.TruncateAt.END);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
            int extra = Math.max(0, (Math.round(32 * density) - (fm.descent - fm.ascent)) / 2);
            fm.ascent -= extra;
            fm.top = Math.min(fm.top, fm.ascent);
            fm.descent += extra;
            fm.bottom = Math.max(fm.bottom, fm.descent);
        }
        return Math.round(actionWidth() * 2 + 16 * density
                + paint.measureText(displayLabel(paint).toString()));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint original) {
        Paint paint = new Paint(original);
        float width = getSize(original, text, start, end, null);
        float cy = (top + bottom) / 2f;
        float height = Math.min(32 * density, bottom - top);
        int tint = operator == '-' ? original.getColor() : accent;
        paint.setColor(ColorUtils.setAlphaComponent(tint, operator == '~' ? 45 : 28));
        canvas.drawRoundRect(x, cy - height / 2, x + width, cy + height / 2,
                height / 2, height / 2, paint);
        paint.setColor(ColorUtils.setAlphaComponent(tint, 65));
        canvas.drawLine(x + actionWidth(), cy - height / 3,
                x + actionWidth(), cy + height / 3, paint);
        paint.setColor(original.getColor());
        float baseline = cy - (paint.ascent() + paint.descent()) / 2;
        String sign = operator == '-' ? "−" : String.valueOf(operator);
        canvas.drawText(sign, x + (actionWidth() - paint.measureText(sign)) / 2, baseline, paint);
        canvas.drawText(displayLabel(paint).toString(), x + actionWidth() + 8 * density, baseline, paint);
        float closeX = x + width - actionWidth() / 2;
        paint.setStrokeWidth(1.5f * density);
        float radius = 3.5f * density;
        canvas.drawLine(closeX - radius, cy - radius, closeX + radius, cy + radius, paint);
        canvas.drawLine(closeX - radius, cy + radius, closeX + radius, cy - radius, paint);
    }
}
