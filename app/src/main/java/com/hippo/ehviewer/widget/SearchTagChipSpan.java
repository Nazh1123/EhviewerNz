package com.hippo.ehviewer.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.SearchQueryTokenizer.Tag;
import java.util.Locale;

/** A visual replacement only: the Editable still contains the complete English query. */
final class SearchTagChipSpan extends ReplacementSpan {
    private static final int ACTION_WIDTH_DP = 20;
    private static final int LABEL_PADDING_DP = 2;
    static final int CHIP_HEIGHT_DP = 32;
    static final int ROW_HEIGHT_DP = 40;
    final String label;
    final char operator;
    private final float density;
    private final int accent;
    private final float maxLabelWidth;
    private final boolean active;

    SearchTagChipSpan(String label, char operator, float density, int accent, float maxLabelWidth) {
        this(label, operator, density, accent, maxLabelWidth, true);
    }

    SearchTagChipSpan(String label, char operator, float density, int accent, float maxLabelWidth,
                      boolean active) {
        this.label = label;
        this.operator = operator;
        this.density = density;
        this.accent = accent;
        this.maxLabelWidth = maxLabelWidth;
        this.active = active;
    }

    static String labelFor(Tag tag, @Nullable EhTagDatabase database) {
        String value = tag.value();
        String prefix = EhTagDatabase.namespaceToPrefix(tag.namespace());
        if (prefix == null || prefix.isEmpty()) prefix = tag.namespace() + ":";
        if (database != null) {
            String translated = database.getTranslation(prefix + value.toLowerCase(Locale.ROOT));
            if (translated != null) value = translated;
        }
        return prefix + " " + value;
    }

    float actionWidth() { return ACTION_WIDTH_DP * density; }

    float height() { return CHIP_HEIGHT_DP * density; }

    static float centerY(Paint paint, int baseline) {
        return Math.round(baseline + (paint.ascent() + paint.descent()) / 2);
    }

    static float horizontalSpace(float density) {
        return (ACTION_WIDTH_DP + LABEL_PADDING_DP) * 2 * density;
    }

    private String displayLabel(Paint paint) {
        return TextUtils.ellipsize(label, new TextPaint(paint), maxLabelWidth, TextUtils.TruncateAt.END).toString();
    }

    private int width(Paint paint, String displayLabel) {
        return Math.round(horizontalSpace(density) + paint.measureText(displayLabel));
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
        }
        return width(paint, displayLabel(paint));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint original) {
        Paint paint = new Paint(original);
        String displayLabel = displayLabel(original);
        float width = width(original, displayLabel);
        // Keep ordinary font metrics for the native cursor. Row spacing lives in the TextView.
        float cy = centerY(original, y);
        float height = height();
        int tint = !active || operator == '-' ? original.getColor() : accent;
        paint.setColor(ColorUtils.setAlphaComponent(tint, !active ? 12 : operator == '~' ? 45 : 28));
        canvas.drawRoundRect(x, cy - height / 2, x + width, cy + height / 2,
                height / 2, height / 2, paint);
        paint.setColor(ColorUtils.setAlphaComponent(tint, 65));
        paint.setStrokeWidth(0.5f * density);
        float dividerInset = actionWidth() - LABEL_PADDING_DP * density;
        canvas.drawLine(x + dividerInset, cy - 6 * density, x + dividerInset, cy + 6 * density, paint);
        canvas.drawLine(x + width - dividerInset, cy - 6 * density,
                x + width - dividerInset, cy + 6 * density, paint);
        paint.setColor(original.getColor());
        float baseline = cy - (paint.ascent() + paint.descent()) / 2;
        String sign = operator == '-' ? "−" : String.valueOf(operator);
        canvas.drawText(sign, x + (actionWidth() - paint.measureText(sign)) / 2, baseline, paint);
        canvas.drawText(displayLabel,
                x + actionWidth() + LABEL_PADDING_DP * density, baseline, paint);
        float closeX = x + width - actionWidth() / 2;
        // Use the same font and weight as the operator instead of a heavier fixed-width stroke.
        String action = active ? "×" : "+";
        canvas.drawText(action, closeX - paint.measureText(action) / 2, baseline, paint);
    }
}
