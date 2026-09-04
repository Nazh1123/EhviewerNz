/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared implementation for gallery-version maintenance entries and upgrade prompts. */
public final class GalleryVersionMaintenance {

    private static final int API_BATCH_SIZE = 25;
    private static final int BATCHES_PER_BURST = 4;
    private static final long BURST_PAUSE_MS = 8400L;
    private static final AtomicBoolean UPDATE_RUNNING = new AtomicBoolean();

    private GalleryVersionMaintenance() {
    }

    public static void showUpdateConfirmation(@NonNull Context context) {
        if (UPDATE_RUNNING.get()) {
            Toast.makeText(context, R.string.gallery_version_update_running,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_update_downloaded_gallery_versions)
                .setMessage(R.string.gallery_version_update_background_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> startBackgroundUpdate(context))
                .show();
    }

    public static void startBackgroundUpdate(@NonNull Context context) {
        if (UPDATE_RUNNING.get()) {
            Toast.makeText(context, R.string.gallery_version_update_running,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new UpdateTask(context).execute();
    }

    private static final class UpdateTask extends AsyncTask<Void, Void, UpdateResult> {
        private final Context application;
        private final DownloadManager manager;
        private final List<DownloadInfo> pending = new ArrayList<>();
        private boolean ownsRunningFlag;

        UpdateTask(@NonNull Context context) {
            application = context.getApplicationContext();
            manager = EhApplication.getDownloadManager(application);
            for (DownloadInfo info : new ArrayList<>(manager.getAllDownloadInfoList())) {
                if (info.firstGid == null && info.gid > 0L && info.token != null
                        && !info.token.isEmpty() && !DownloadManager.isImportedGallery(info)) {
                    pending.add(info);
                }
            }
        }

        @Override
        protected void onPreExecute() {
            ownsRunningFlag = UPDATE_RUNNING.compareAndSet(false, true);
            if (!ownsRunningFlag) {
                cancel(false);
            }
        }

        @Override
        protected UpdateResult doInBackground(Void... ignored) {
            if (isCancelled()) {
                return new UpdateResult(0, 0, 0);
            }
            int updated = 0;
            int unavailable = 0;
            int retry = 0;
            int batchNumber = 0;
            for (int offset = 0; offset < pending.size(); offset += API_BATCH_SIZE) {
                int end = Math.min(offset + API_BATCH_SIZE, pending.size());
                List<GalleryInfo> requestItems = new ArrayList<>(end - offset);
                for (int i = offset; i < end; i++) {
                    DownloadInfo info = pending.get(i);
                    GalleryInfo request = new GalleryInfo();
                    request.gid = info.gid;
                    request.token = info.token;
                    requestItems.add(request);
                }
                try {
                    EhEngine.fillGalleryListByApi(null,
                            EhApplication.getOkHttpClient(application), requestItems,
                            EhUrl.getReferer());
                    List<DownloadInfo> changed = new ArrayList<>(requestItems.size());
                    for (int i = 0; i < requestItems.size(); i++) {
                        Long firstGid = requestItems.get(i).firstGid;
                        if (firstGid == null) {
                            retry++;
                            continue;
                        }
                        DownloadInfo info = pending.get(offset + i);
                        info.firstGid = firstGid;
                        changed.add(info);
                        if (firstGid > 0L) {
                            updated++;
                        } else {
                            unavailable++;
                        }
                    }
                    if (!changed.isEmpty()) {
                        EhDB.putDownloadInfo(changed);
                    }
                } catch (Throwable error) {
                    ExceptionUtils.throwIfFatal(error);
                    retry += end - offset;
                }
                batchNumber++;
                if (end < pending.size() && batchNumber % BATCHES_PER_BURST == 0) {
                    try {
                        Thread.sleep(BURST_PAUSE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        retry += pending.size() - end;
                        break;
                    }
                }
            }
            return new UpdateResult(updated, unavailable, retry);
        }

        @Override
        protected void onPostExecute(UpdateResult result) {
            if (ownsRunningFlag) {
                UPDATE_RUNNING.set(false);
            }
            manager.onGalleryVersionInfoUpdated();
            Toast.makeText(application, application.getString(
                    R.string.gallery_version_update_done, result.updated,
                    result.unavailable, result.retry), Toast.LENGTH_LONG).show();
        }

        @Override
        protected void onCancelled() {
            if (ownsRunningFlag) {
                UPDATE_RUNNING.set(false);
            }
        }
    }

    private static final class UpdateResult {
        final int updated;
        final int unavailable;
        final int retry;

        UpdateResult(int updated, int unavailable, int retry) {
            this.updated = updated;
            this.unavailable = unavailable;
            this.retry = retry;
        }
    }
}
