package com.hippo.ehviewer.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryDownloadRangeStore;
import com.hippo.ehviewer.download.GalleryPageRange;

public final class RangeDownloadDialog {
    private RangeDownloadDialog() { }
    public static void show(Context context, GalleryInfo gallery, DownloadManager manager) {
        GalleryDownloadRangeStore.get(context);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(R.string.range_download);
        Context themed = builder.getContext();
        int padding = Math.round(24 * themed.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(themed);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, 0);
        AppCompatEditText input = new AppCompatEditText(themed);
        input.setHint(R.string.range_download_input_hint);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView preview = new TextView(themed);
        preview.setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Small);
        preview.setPadding(0, padding / 2, 0, padding / 2);
        content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        GalleryDownloadRangeStore.Entry existing = GalleryDownloadRangeStore.peek(gallery.gid);
        int total = gallery.pages > 0 ? gallery.pages : existing != null ? existing.range().totalPages : 0;
        if (total <= 0) {
            Toast.makeText(context, R.string.range_download_missing_pages, Toast.LENGTH_SHORT).show();
            return;
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                try {
                    GalleryPageRange range = GalleryPageRange.parse(text.toString(), total);
                    input.setError(null);
                    preview.setText(themed.getString(R.string.range_download_preview, range.toString(), range.size()));
                } catch (IllegalArgumentException e) {
                    preview.setText(null);
                }
            }
        });
        input.setText(existing != null ? existing.range().toString() : "");
        AlertDialog dialog = builder.setView(content).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.download, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                GalleryPageRange range = GalleryPageRange.parse(input.getText().toString(), total);
                manager.changePageRange(gallery, range,
                        error -> Toast.makeText(context, error, Toast.LENGTH_LONG).show());
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                input.setError(themed.getString(R.string.range_download_invalid, total));
            }
        }));
        dialog.show();
    }
}
