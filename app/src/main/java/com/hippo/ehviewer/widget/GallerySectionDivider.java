package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.hippo.android.resource.AttrResources;

/** Reserves space for the entire stroke instead of fitting a drawable into a thin child. */
public final class GallerySectionDivider extends View {
    private static final int STROKE_PX = 3;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GallerySectionDivider(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(AttrResources.getAttrColor(context, android.R.attr.textColorSecondary));
    }

    @Override protected int getSuggestedMinimumHeight() {
        return Math.max(super.getSuggestedMinimumHeight(), getPaddingTop() + STROKE_PX + getPaddingBottom());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - STROKE_PX) / 2f;
        canvas.drawRoundRect(getWidth() * 0.15f, top, getWidth() * 0.85f, top + STROKE_PX,
                STROKE_PX / 2f, STROKE_PX / 2f, paint);
    }
}
