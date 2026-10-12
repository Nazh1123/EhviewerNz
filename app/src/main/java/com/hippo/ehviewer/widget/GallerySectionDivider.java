package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;

/** Reserves space for the entire stroke instead of fitting a drawable into a thin child. */
public final class GallerySectionDivider extends View {
    private final int strokePx;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GallerySectionDivider(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        strokePx = getResources().getDimensionPixelSize(R.dimen.gallery_section_divider_thickness);
        paint.setColor(AttrResources.getAttrColor(context, android.R.attr.textColorSecondary));
    }

    @Override protected int getSuggestedMinimumHeight() {
        return Math.max(super.getSuggestedMinimumHeight(), getPaddingTop() + strokePx + getPaddingBottom());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - strokePx) / 2f;
        canvas.drawRoundRect(getWidth() * 0.15f, top, getWidth() * 0.85f, top + strokePx,
                strokePx / 2f, strokePx / 2f, paint);
    }
}
