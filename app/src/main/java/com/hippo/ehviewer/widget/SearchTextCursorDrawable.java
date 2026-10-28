package com.hippo.ehviewer.widget;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;

import com.hippo.ehviewer.R;

import org.xmlpull.v1.XmlPullParser;

/** Keeps the themed cursor at ordinary text height when chip rows enlarge its bounds. */
public final class SearchTextCursorDrawable extends DrawableWrapper {
    private int textHeight;
    private final Rect padding = new Rect();

    public SearchTextCursorDrawable() {
        super(null);
    }

    @Override public void inflate(Resources resources, XmlPullParser parser, AttributeSet attrs,
                                  Resources.Theme theme) {
        Paint text = new Paint();
        text.setTextSize(resources.getDimension(R.dimen.text_little_small));
        Paint.FontMetricsInt fm = text.getFontMetricsInt();
        textHeight = fm.bottom - fm.top;
        int[] attributes = {android.R.attr.textCursorDrawable, androidx.appcompat.R.attr.colorControlActivated};
        TypedArray appearance = theme == null ? resources.obtainAttributes(attrs, attributes)
                : theme.obtainStyledAttributes(null, attributes, androidx.appcompat.R.attr.editTextStyle, 0);
        try {
            Drawable cursor = appearance.getDrawable(0);
            if (cursor == null) {
                GradientDrawable fallback = new GradientDrawable();
                fallback.setColor(appearance.getColor(1, Color.BLACK));
                fallback.setSize(Math.max(1, Math.round(2 * resources.getDisplayMetrics().density)), textHeight);
                cursor = fallback;
            }
            setDrawable(cursor.mutate());
        } finally {
            appearance.recycle();
        }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        Drawable cursor = getDrawable();
        if (cursor == null) return;
        cursor.getPadding(padding);
        int height = Math.min(bounds.height(), textHeight + padding.top + padding.bottom);
        int top = bounds.top + (bounds.height() - height) / 2;
        cursor.setBounds(bounds.left, top, bounds.right, top + height);
    }
}
