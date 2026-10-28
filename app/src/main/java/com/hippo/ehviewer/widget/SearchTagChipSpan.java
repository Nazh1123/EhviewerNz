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
    private static final int ACTION_WIDTH_DP = 20;
    private static final int LABEL_PADDING_DP = 2;
    private static final int CHIP_HEIGHT_DP = 32;
    static final int ROW_HEIGHT_DP = 40;
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

    float actionWidth() { return ACTION_WIDTH_DP * density; }

    static float horizontalSpace(float density) {
        return (ACTION_WIDTH_DP + LABEL_PADDING_DP) * 2 * density;
    }

    private CharSequence displayLabel(Paint paint) {
        return TextUtils.ellipsize(label, new TextPaint(paint), maxLabelWidth, TextUtils.TruncateAt.END);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
            expandLineHeight(fm, Math.round(ROW_HEIGHT_DP * density));
        }
        return Math.round(horizontalSpace(density)
                + paint.measureText(displayLabel(paint).toString()));
    }

    static void expandLineHeight(Paint.FontMetricsInt fm, int height) {
        int extra = Math.max(0, height - (fm.descent - fm.ascent));
        fm.ascent -= extra / 2;
        fm.top = Math.min(fm.top, fm.ascent);
        fm.descent += extra - extra / 2;
        fm.bottom = Math.max(fm.bottom, fm.descent);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint original) {
        Paint paint = new Paint(original);
        float width = getSize(original, text, start, end, null);
        float cy = (top + bottom) / 2f;
        float height = Math.min(CHIP_HEIGHT_DP * density, bottom - top);
        int tint = operator == '-' ? original.getColor() : accent;
        paint.setColor(ColorUtils.setAlphaComponent(tint, operator == '~' ? 45 : 28));
        canvas.drawRoundRect(x, cy - height / 2, x + width, cy + height / 2,
                height / 2, height / 2, paint);
        paint.setColor(ColorUtils.setAlphaComponent(tint, 65));
        paint.setStrokeWidth(0.5f * density);
        float dividerInset = actionWidth() - LABEL_PADDING_DP * density;
        for (float position : new float[]{dividerInset, width - dividerInset}) {
            canvas.drawLine(x + position, cy - 6 * density,
                    x + position, cy + 6 * density, paint);
        }
        paint.setColor(original.getColor());
        float baseline = cy - (paint.ascent() + paint.descent()) / 2;
        String sign = operator == '-' ? "−" : String.valueOf(operator);
        canvas.drawText(sign, x + (actionWidth() - paint.measureText(sign)) / 2, baseline, paint);
        canvas.drawText(displayLabel(paint).toString(),
                x + actionWidth() + LABEL_PADDING_DP * density, baseline, paint);
        float closeX = x + width - actionWidth() / 2;
        // Use the same font and weight as the operator instead of a heavier fixed-width stroke.
        canvas.drawText("×", closeX - paint.measureText("×") / 2, baseline, paint);
    }
}
