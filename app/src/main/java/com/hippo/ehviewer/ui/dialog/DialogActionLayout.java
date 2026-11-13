package com.hippo.ehviewer.ui.dialog;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.method.TransformationMethod;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;

/** Keep the secondary action above the standard cancel/confirm pair in narrow dialogs. */
public final class DialogActionLayout {
    private DialogActionLayout() {}

    /** Call after show(), retaining the dialog's buttons and their existing listeners. */
    public static void placeNeutralAboveActions(AlertDialog dialog) {
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral == null || neutral.getVisibility() != View.VISIBLE) return;
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        TransformationMethod transformation = neutral.getTransformationMethod();
        neutral.setSingleLine(false);
        neutral.setTransformationMethod(transformation);
        neutral.setEllipsize(null);
        neutral.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ViewGroup original = (ViewGroup) neutral.getParent();
        ViewGroup parent = (ViewGroup) original.getParent();
        int index = parent.indexOfChild(original);

        LinearLayout panel = new LinearLayout(original.getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                // Reserve both complete rows before AlertDialog sizes scrollable content.
                setMinimumHeight(getMeasuredHeight());
            }
        };
        panel.setId(original.getId());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPaddingRelative(original.getPaddingStart(), original.getPaddingTop(),
                original.getPaddingEnd(), original.getPaddingBottom());

        LinearLayout actions = new LinearLayout(original.getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        for (Button button : new Button[]{neutral, cancel, confirm}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    (LinearLayout.LayoutParams) button.getLayoutParams());
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.weight = 0;
            params.gravity = Gravity.END;
            original.removeView(button);
            (button == neutral ? panel : actions).addView(button, params);
        }
        panel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ViewGroup.LayoutParams params = original.getLayoutParams();
        parent.removeViewAt(index);
        parent.addView(panel, index, params);
    }
}
