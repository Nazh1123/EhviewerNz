package com.hippo.ehviewer.ui;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared removal actions for the gallery and download screens. */
public final class DownloadRemovalDialog {
    private DownloadRemovalDialog() {}

    public static void show(@NonNull Context context, @NonNull GalleryInfo gallery,
                            @NonNull DownloadManager manager) {
        DownloadInfo info = manager.getDownloadInfo(gallery.gid);
        if (info == null) return;
        show(context, Collections.singletonList(info), manager,
                context.getString(R.string.download_remove_dialog_message, gallery.title), null);
    }

    public static void show(@NonNull Context context, @NonNull List<DownloadInfo> downloads,
                            @NonNull DownloadManager manager, @Nullable Runnable onAction) {
        if (downloads.isEmpty()) return;
        show(context, downloads, manager,
                context.getString(R.string.download_remove_dialog_message_2, downloads.size()),
                onAction);
    }

    private static void show(Context context, List<DownloadInfo> downloads,
                             DownloadManager manager, String message, @Nullable Runnable onAction) {
        // Selection lists can change when the download manager notifies its listeners.
        List<DownloadInfo> selected = new ArrayList<>(downloads);
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.download_remove_dialog_title)
                .setMessage(message);
        View content = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.dialog_download_remove, null);
        AlertDialog dialog = builder.setView(content).create();
        boolean allOnline = true;
        boolean anyOnline = false;
        for (DownloadInfo info : selected) {
            boolean online = info.archiveUri == null;
            allOnline &= online;
            anyOnline |= online;
        }
        // Imported archives and folders cannot be downloaded from the server or erased here.
        content.findViewById(R.id.download_redownload).setEnabled(allOnline);
        content.findViewById(R.id.download_remove_files).setEnabled(anyOnline);
        content.findViewById(R.id.download_remove_record).setOnClickListener(view -> {
            dialog.dismiss();
            remove(context, selected, manager, false, false, onAction);
        });
        content.findViewById(R.id.download_redownload).setOnClickListener(view -> {
            dialog.dismiss();
            remove(context, selected, manager, true, false, onAction);
        });
        content.findViewById(R.id.download_remove_files).setOnClickListener(view -> {
            dialog.dismiss();
            remove(context, selected, manager, false, true, onAction);
        });
        dialog.show();
    }

    private static void remove(Context context, List<DownloadInfo> downloads,
                               DownloadManager manager, boolean redownload, boolean removeFiles,
                               @Nullable Runnable onAction) {
        if (onAction != null) onAction.run();
        LongList gids = new LongList();
        for (DownloadInfo info : downloads) gids.add(info.gid);
        manager.deleteRangeDownload(gids);

        if (redownload) {
            for (DownloadInfo info : downloads) {
                Intent intent = new Intent(context, DownloadService.class)
                        .setAction(DownloadService.ACTION_START)
                        .putExtra(DownloadService.KEY_GALLERY_INFO, info)
                        .putExtra(DownloadService.KEY_LABEL, info.label);
                context.startService(intent);
            }
        } else if (removeFiles) {
            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                for (DownloadInfo info : downloads) {
                    if (info.archiveUri != null) continue;
                    UniFile directory = SpiderDen.getExistingGalleryDownloadDir(info);
                    if (directory == null) directory = SpiderDen.getGalleryDownloadDir(info);
                    EhDB.removeDownloadDirname(info.gid);
                    if (directory != null) directory.delete();
                }
            });
        }
    }
}
