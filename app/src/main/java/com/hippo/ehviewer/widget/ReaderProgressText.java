package com.hippo.ehviewer.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

/** Keeps the separator centered while page counters change width independently. */
public final class ReaderProgressText {
    private ReaderProgressText() {}

    public static CharSequence centerSeparator(String text) {
        int separator = text.indexOf('·');
        if (separator <= 0 || separator == text.length() - 1) return text;
        String left = text.substring(0, separator);
        String right = text.substring(separator + 1);
        SpannableString result = new SpannableString(text);
        result.setSpan(new CounterSpan(left, right, true), 0, separator,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.setSpan(new CounterSpan(left, right, false), separator + 1, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private static final class CounterSpan extends ReplacementSpan {
        private final String left;
        private final String right;
        private final boolean alignRight;

        CounterSpan(String left, String right, boolean alignRight) {
            this.left = left;
            this.right = right;
            this.alignRight = alignRight;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                     Paint.FontMetricsInt metrics) {
            return (int) Math.ceil(Math.max(paint.measureText(left), paint.measureText(right)));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            if (alignRight) x += getSize(paint, text, start, end, null)
                    - paint.measureText(text, start, end);
            canvas.drawText(text, start, end, x, y, paint);
        }
    }
}
