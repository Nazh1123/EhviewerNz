package com.hippo.ehviewer.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.GetChars;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.view.View;

/** Same-length display wrapping: a separator is displayed as a newline, never inserted in the query. */
final class SearchFirstRow implements TransformationMethod {
    final int left;
    final int right;
    private int after = -1;
    private LeadingMarginSpan margin;
    private RowHeight rowHeight;

    SearchFirstRow(int left, int right) {
        this.left = left;
        this.right = right;
    }

    void clear(Spannable text) {
        if (margin != null) text.removeSpan(margin);
        if (rowHeight != null) text.removeSpan(rowHeight);
        margin = null;
        rowHeight = null;
        after = -1;
    }

    void update(SearchEditText input) {
        Spannable text = input.getText();
        if (text == null || input.getWidth() == 0) return;
        clear(text);
        if (text.length() == 0) {
            input.setPadding(left, input.getPaddingTop(), right, input.getPaddingBottom());
            return;
        }
        int width = Math.max(1, input.getWidth() - left - right);
        SpannableString sample = new SpannableString(text);
        StaticLayout layout = StaticLayout.Builder.obtain(sample, 0, sample.length(), input.getPaint(), width)
                .setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).build();
        int end = layout.getLineEnd(0);
        int separator = -1;
        if (end < text.length()) {
            for (int i = end - 1; i >= 0; i--) {
                if (!Character.isWhitespace(text.charAt(i))) continue;
                boolean inChip = false;
                for (SearchTagChipSpan chip : text.getSpans(i, i + 1, SearchTagChipSpan.class)) {
                    if (text.getSpanStart(chip) <= i && text.getSpanEnd(chip) > i) inChip = true;
                }
                if (!inChip) { separator = i; break; }
            }
        }
        // An unbroken word still uses native soft wrapping, with the right button kept clear.
        int rightPadding = end < text.length() && separator < 0 ? right : 0;
        input.setPadding(rightPadding > 0 ? left : 0, input.getPaddingTop(), rightPadding, input.getPaddingBottom());
        after = separator;
        margin = new RowMargin(rightPadding > 0 ? 0 : left);
        text.setSpan(margin, 0, separator >= 0 ? separator + 1 : text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rowHeight = new RowHeight(Math.round(SearchTagChipSpan.ROW_HEIGHT_DP
                * input.getResources().getDisplayMetrics().density));
        text.setSpan(rowHeight, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    @Override public CharSequence getTransformation(CharSequence source, View view) {
        return new Display(source);
    }

    @Override public void onFocusChanged(View view, CharSequence text, boolean focused, int direction, Rect rect) {}

    private final class Display implements Spanned, GetChars {
        private final CharSequence source;
        Display(CharSequence source) { this.source = source; }
        @Override public int length() { return source.length(); }
        @Override public char charAt(int index) {
            char value = source.charAt(index);
            return index == after && Character.isWhitespace(value) ? '\n' : value;
        }
        @Override public void getChars(int start, int end, char[] dest, int offset) {
            TextUtils.getChars(source, start, end, dest, offset);
            if (after >= start && after < end && Character.isWhitespace(dest[offset + after - start])) {
                dest[offset + after - start] = '\n';
            }
        }
        @Override public CharSequence subSequence(int start, int end) {
            return new SpannableString(this).subSequence(start, end);
        }
        @Override public String toString() {
            char[] chars = new char[length()];
            getChars(0, length(), chars, 0);
            return new String(chars);
        }
        @Override public <T> T[] getSpans(int start, int end, Class<T> type) {
            return ((Spanned) source).getSpans(start, end, type);
        }
        @Override public int getSpanStart(Object span) { return ((Spanned) source).getSpanStart(span); }
        @Override public int getSpanEnd(Object span) { return ((Spanned) source).getSpanEnd(span); }
        @Override public int getSpanFlags(Object span) { return ((Spanned) source).getSpanFlags(span); }
        @Override public int nextSpanTransition(int start, int limit, Class type) {
            return ((Spanned) source).nextSpanTransition(start, limit, type);
        }
    }

    // Display geometry must not be serialized with the saved query text.
    private static final class RowMargin implements LeadingMarginSpan {
        private final int width;
        RowMargin(int width) { this.width = width; }
        @Override public int getLeadingMargin(boolean first) { return first ? width : 0; }
        @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir,
                int top, int baseline, int bottom, CharSequence text, int start, int end,
                boolean first, Layout layout) {}
    }

    private static final class RowHeight implements LineHeightSpan {
        private final int height;
        RowHeight(int height) { this.height = height; }
        @Override public void chooseHeight(CharSequence text, int start, int end,
                int spanStartVertical, int lineTop, Paint.FontMetricsInt fm) {
            SearchTagChipSpan.expandLineHeight(fm, height);
        }
    }
}
