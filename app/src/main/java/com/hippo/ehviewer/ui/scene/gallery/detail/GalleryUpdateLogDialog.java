package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.hippo.android.resource.AttrResources;
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        Context themed = builder.getContext();
        View root = LayoutInflater.from(themed).inflate(R.layout.dialog_gallery_update_log, null);
        LinearLayout content = root.findViewById(R.id.gallery_update_log_content);
        int primary = AttrResources.getAttrColor(themed, android.R.attr.textColorPrimary);
        int secondary = AttrResources.getAttrColor(themed, android.R.attr.textColorSecondary);
        int addedColor = themed.getColor(R.color.deep_green_600);
        int deletedColor = themed.getColor(R.color.red_500);

        content.addView(label(themed, themed.getString(R.string.gallery_update_log_time), 12, secondary));
        TextView time = label(themed, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(record.completedAt)), 16, primary);
        time.setTextIsSelectable(true);
        time.setPadding(0, dp(themed, 4), 0, dp(themed, 4));
        content.addView(time);
        TextView target = label(themed, themed.getString(R.string.gallery_update_log_target, record.targetGid),
                12, secondary);
        target.setTextIsSelectable(true);
        target.setPadding(0, 0, 0, dp(themed, 16));
        content.addView(target);
        if (record.isFailure()) {
            section(themed, content, themed.getString(R.string.gallery_update_error_source),
                    record.sourceGid > 0 ? themed.getString(R.string.gallery_update_error_source_info,
                            record.sourceGid, record.sourceTitle.isEmpty()
                                    ? themed.getString(R.string.gallery_update_error_title_unknown) : record.sourceTitle)
                            : themed.getString(R.string.gallery_update_error_source_unknown), primary);
            String reason = record.errorReason;
            if (record.retainedParentGids.length > 0) {
                StringBuilder gids = new StringBuilder();
                for (long gid : record.retainedParentGids) {
                    if (gids.length() > 0) gids.append(", ");
                    gids.append(gid);
                }
                reason += "\n\n" + themed.getString(R.string.gallery_update_error_parents_retained, gids);
            }
            section(themed, content, themed.getString(R.string.gallery_update_error_reason),
                    reason, deletedColor);
        } else if (record.complete) {
            pageSection(themed, content, R.string.gallery_update_log_added,
                    record.addedPages, "+", addedColor);
            pageSection(themed, content, R.string.gallery_update_log_deleted,
                    record.deletedPages, "-", deletedColor);
        } else {
            section(themed, content, themed.getString(R.string.gallery_update_history_incomplete),
                    themed.getString(R.string.gallery_update_log_incomplete), primary);
        }
        if (!record.isFailure()) {
            section(themed, content, themed.getString(R.string.gallery_read_updates),
                    themed.getString(R.string.gallery_update_history_local_notice), secondary);
        }
        View actions = root.findViewById(R.id.gallery_update_log_actions);
        if (readUpdate == null && showHistory == null) {
            actions.setVisibility(View.GONE);
        } else {
            Button read = root.findViewById(R.id.gallery_update_log_read_action);
            read.setEnabled(readUpdate != null && record.complete && !record.isFailure()
                    && record.hasTokenSnapshot() && record.addedPages.length > 0);
            read.setOnClickListener(view -> {
                if (read.isEnabled() && readUpdate != null) readUpdate.run();
            });
            TextView notice = root.findViewById(R.id.gallery_update_log_read_notice);
            if (record.complete && !record.isFailure()) {
                int reason = !record.hasTokenSnapshot() ? R.string.gallery_update_log_legacy_notice
                        : record.addedPages.length == 0 ? R.string.gallery_update_log_no_added
                        : readUpdate == null ? R.string.gallery_update_history_no_local : 0;
                if (reason != 0) {
                    notice.setText(reason);
                    notice.setVisibility(View.VISIBLE);
                }
            }
            Button history = root.findViewById(R.id.gallery_update_log_history_action);
            history.setEnabled(showHistory != null);
            history.setOnClickListener(view -> {
                if (history.isEnabled() && showHistory != null) showHistory.run();
            });
        }
        AlertDialog dialog = builder
                .setTitle(record.isFailure() ? R.string.gallery_update_error_log : R.string.gallery_update_log)
                .setView(root)
                .setNeutralButton(R.string.gallery_update_log_versions,
                        (ignored, which) -> { if (showVersions != null) showVersions.run(); })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(showVersions != null);
        return dialog;
    }

    static CharSequence historyName(Context context, GalleryUpdateRecord record, int number) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(record.completedAt));
        String prefix = number + ". " + time;
        if (record.isFailure()) return prefix + " · " + context.getString(R.string.gallery_update_error_log);
        if (!record.complete) return prefix + " · " + context.getString(R.string.gallery_update_history_incomplete);
        SpannableStringBuilder name = new SpannableStringBuilder(prefix + "   ");
        int addedStart = name.length();
        name.append("+").append(Integer.toString(record.addedPages.length));
        name.setSpan(new ForegroundColorSpan(context.getColor(R.color.deep_green_600)),
                addedStart, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.append(" ");
        int deletedStart = name.length();
        name.append("-").append(Integer.toString(record.deletedPages.length));
        name.setSpan(new ForegroundColorSpan(context.getColor(R.color.red_500)),
                deletedStart, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return name;
    }

    private static String pages(Context context, int[] pages) {
        return pages.length == 0 ? context.getString(R.string.gallery_update_log_none)
                : GalleryUpdateRecord.formatPages(pages);
    }

    private static void section(Context context, LinearLayout content, String title,
                                String value, int color) {
        LinearLayout card = card(context, 0);
        card.addView(heading(context, title, color));
        card.addView(body(context, value, false));
        addCard(context, content, card);
    }

    private static void pageSection(Context context, LinearLayout content, int title,
                                    int[] pageNumbers, String sign, int color) {
        LinearLayout card = card(context, color);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBaselineAligned(false);
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(heading(context, context.getString(title),
                AttrResources.getAttrColor(context, android.R.attr.textColorSecondary)));
        details.addView(body(context, pages(context, pageNumbers), true));
        card.addView(details, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView count = label(context, sign + pageNumbers.length, 44, color);
        count.setTypeface(count.getTypeface(), Typeface.BOLD);
        count.setIncludeFontPadding(false);
        count.setGravity(Gravity.TOP | Gravity.END);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.setMarginStart(dp(context, 12));
        countParams.gravity = Gravity.TOP;
        card.addView(count, countParams);
        addCard(context, content, card);
    }

    private static void addCard(Context context, LinearLayout content, LinearLayout card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 12);
        content.addView(card, params);
    }

    private static LinearLayout card(Context context, int borderColor) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        card.setBackground(cardBackground(context,
                AttrResources.getAttrColor(context, android.R.attr.textColorPrimary), borderColor));
        return card;
    }

    private static TextView heading(Context context, String title, int color) {
        TextView heading = label(context, title, 14, color);
        heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
        return heading;
    }

    private static TextView body(Context context, String value, boolean pageNumbers) {
        TextView body = label(context, value, pageNumbers ? 15 : 13,
                AttrResources.getAttrColor(context, android.R.attr.textColorPrimary));
        if (pageNumbers) body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(context, 3), 1);
        body.setPadding(0, dp(context, 8), 0, 0);
        return body;
    }

    private static GradientDrawable cardBackground(Context context, int color, int borderColor) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 12));
        background.setColor(ColorUtils.setAlphaComponent(color, 10));
        if (borderColor != 0) background.setStroke(dp(context, 1), ColorUtils.setAlphaComponent(borderColor, 48));
        return background;
    }

    private static TextView label(Context context, String text, int size, int color) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
