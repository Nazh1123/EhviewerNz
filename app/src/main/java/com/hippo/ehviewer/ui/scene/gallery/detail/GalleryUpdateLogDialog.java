package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.GalleryUpdateRecord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Entirely local; even constructing the version-history dialog waits for the button click. */
final class GalleryUpdateLogDialog {
    private GalleryUpdateLogDialog() {}

    static AlertDialog show(Context context, GalleryUpdateRecord record, Runnable showVersions) {
        return show(context, record, null, null, showVersions);
    }

    static AlertDialog show(Context context, GalleryUpdateRecord record, Runnable readUpdate,
                            Runnable showHistory, Runnable showVersions) {
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        section(context, content, context.getString(R.string.gallery_update_log_time),
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(record.completedAt)), padding);
        if (record.isFailure()) {
            section(context, content, context.getString(R.string.gallery_update_error_source),
                    record.sourceGid > 0 ? context.getString(R.string.gallery_update_error_source_info,
                            record.sourceGid, record.sourceTitle.isEmpty()
                                    ? context.getString(R.string.gallery_update_error_title_unknown) : record.sourceTitle)
                            : context.getString(R.string.gallery_update_error_source_unknown), padding);
            String reason = record.errorReason;
            if (record.retainedParentGids.length > 0) {
                StringBuilder gids = new StringBuilder();
                for (long gid : record.retainedParentGids) {
                    if (gids.length() > 0) gids.append(", ");
                    gids.append(gid);
                }
                reason += "\n\n" + context.getString(R.string.gallery_update_error_parents_retained, gids);
            }
            section(context, content, context.getString(R.string.gallery_update_error_reason), reason, padding);
        } else if (record.complete) {
            section(context, content, context.getString(R.string.gallery_update_log_added,
                            record.addedPages.length), pages(context, record.addedPages), padding);
            section(context, content, context.getString(R.string.gallery_update_log_deleted,
                            record.deletedPages.length), pages(context, record.deletedPages), padding);
        } else {
            TextView notice = new TextView(context);
            notice.setText(R.string.gallery_update_log_incomplete);
            content.addView(notice);
        }
        if (!record.isFailure()) {
            section(context, content, context.getString(R.string.gallery_read_updates),
                    context.getString(R.string.gallery_update_history_local_notice), padding);
        }
        if (readUpdate != null || showHistory != null) {
            Button read = new Button(context);
            read.setText(R.string.gallery_update_log_read);
            read.setEnabled(readUpdate != null && record.complete && !record.isFailure()
                    && record.addedPages.length > 0);
            read.setOnClickListener(view -> readUpdate.run());
            content.addView(read);
            Button history = new Button(context);
            history.setText(R.string.gallery_update_log_history);
            history.setEnabled(showHistory != null);
            history.setOnClickListener(view -> showHistory.run());
            content.addView(history);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(record.isFailure() ? R.string.gallery_update_error_log : R.string.gallery_update_log)
                .setView(scroll)
                .setNeutralButton(R.string.gallery_update_log_versions,
                        (ignored, which) -> showVersions.run())
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        return dialog;
    }

    static String historyName(Context context, GalleryUpdateRecord record) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(record.completedAt));
        if (record.isFailure()) return time + " · " + context.getString(R.string.gallery_update_error_log);
        if (!record.complete) return time + " · " + context.getString(R.string.gallery_update_history_incomplete);
        return time + " +" + record.addedPages.length + " -" + record.deletedPages.length;
    }

    private static String pages(Context context, int[] pages) {
        return pages.length == 0 ? context.getString(R.string.gallery_update_log_none)
                : GalleryUpdateRecord.formatPages(pages);
    }

    private static void section(Context context, LinearLayout content, String title,
                                String value, int padding) {
        TextView heading = new TextView(context);
        heading.setText(title + ":");
        heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
        content.addView(heading);
        TextView body = new TextView(context);
        body.setText(value);
        body.setTextIsSelectable(true);
        body.setPadding(padding / 2, padding / 4, 0, padding * 3 / 4);
        content.addView(body);
    }
}
