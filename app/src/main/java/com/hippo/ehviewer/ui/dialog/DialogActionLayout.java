package com.hippo.ehviewer.ui.dialog;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.method.TransformationMethod;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import androidx.appcompat.app.AlertDialog;

/** Keep the short secondary action at the start of the cancel/confirm row. */
public final class DialogActionLayout {
    private DialogActionLayout() {}

    /** Call after show(), retaining native buttons, styling, and their existing listeners. */
    public static void placeNeutralAtStart(AlertDialog dialog) {
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral == null || neutral.getVisibility() != View.VISIBLE) return;
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        ViewGroup original = (ViewGroup) neutral.getParent();
        ViewGroup parent = (ViewGroup) original.getParent();
        int index = parent.indexOfChild(original);
        int minimumWidth = Math.round(48 * original.getResources().getDisplayMetrics().density);
        int horizontalPadding = Math.round(4 * original.getResources().getDisplayMetrics().density);
        Button[] buttons = {neutral, cancel, confirm};
        float[] textSizes = {neutral.getTextSize(), cancel.getTextSize(), confirm.getTextSize()};
        LinearLayout panel = new LinearLayout(original.getContext()) {
            private int previousWidth;

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                if (width > previousWidth) {
                    for (int i = 0; i < buttons.length; i++)
                        buttons[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizes[i]);
                }
                previousWidth = width;
                super.onMeasure(widthSpec, heightSpec);
                int available = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
                // Use actual native button measurements, including background insets.
                // Only reduce the font when the complete translated row would overflow.
                if (available >= minimumWidth * buttons.length) {
                    for (int attempt = 0; attempt < 8; attempt++) {
                        int needed = 0;
                        for (Button button : buttons) {
                            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
                            needed += button.getMeasuredWidth() + params.leftMargin + params.rightMargin;
                        }
                        if (needed <= available) break;
                        float scale = available / (float) needed * 0.98f;
                        for (Button button : buttons)
                            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, button.getTextSize() * scale);
                        super.onMeasure(widthSpec, heightSpec);
                    }
                }
                setMinimumHeight(getMeasuredHeight());
            }
        };
        panel.setId(original.getId());
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPaddingRelative(original.getPaddingStart(), original.getPaddingTop(),
                original.getPaddingEnd(), original.getPaddingBottom());
        for (Button button : buttons) {
            TransformationMethod transformation = button.getTransformationMethod();
            button.setSingleLine(true);
            button.setTransformationMethod(transformation);
            button.setEllipsize(null);
            button.setGravity(Gravity.CENTER);
            button.setLetterSpacing(0);
            button.setMinWidth(minimumWidth);
            button.setMinimumWidth(minimumWidth);
            button.setPaddingRelative(horizontalPadding, button.getPaddingTop(),
                    horizontalPadding, button.getPaddingBottom());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    (LinearLayout.LayoutParams) button.getLayoutParams());
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.weight = 0;
            params.gravity = Gravity.CENTER_VERTICAL;
            original.removeView(button);
            panel.addView(button, params);
            if (button == neutral) panel.addView(new Space(original.getContext()),
                    new LinearLayout.LayoutParams(0, 0, 1));
        }
        ViewGroup.LayoutParams params = original.getLayoutParams();
        parent.removeViewAt(index);
        parent.addView(panel, index, params);
    }
}
