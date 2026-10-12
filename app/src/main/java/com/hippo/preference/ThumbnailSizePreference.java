package com.hippo.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;

/** A normal single-choice list with a small explanation below its dialog title. */
public class ThumbnailSizePreference extends ListPreference {
    public ThumbnailSizePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        View title = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.dialog_thumbnail_size_title, null);
        ((TextView) title.findViewById(R.id.title)).setText(getDialogTitle());
        builder.setCustomTitle(title);
    }
}
