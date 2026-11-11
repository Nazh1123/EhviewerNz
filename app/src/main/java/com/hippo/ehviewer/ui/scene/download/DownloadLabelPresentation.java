package com.hippo.ehviewer.ui.scene.download;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.DownloadLabel;

/** Display-only indentation, never part of a label's stored name. */
public final class DownloadLabelPresentation {
    public static void bind(TextView view, String title, DownloadLabel item) {
        boolean child = item != null && item.getParentId() != null;
        if (child) {
            SpannableString text = new SpannableString("- " + title);
            text.setSpan(new ForegroundColorSpan(0x80808080), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(text);
        } else view.setText(title);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                item != null && item.getFolder() ? R.drawable.v_label_folder_x24 : 0, 0, 0, 0);
        view.setCompoundDrawablePadding((int) (8 * view.getResources().getDisplayMetrics().density));
    }
}
