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
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared implementation for the two gallery-version maintenance setting entries. */
final class GalleryVersionMaintenance {

    private static final int API_BATCH_SIZE = 25;
    private static final int BATCHES_PER_BURST = 4;
    private static final long BURST_PAUSE_MS = 5200L;
    private static final AtomicBoolean UPDATE_RUNNING = new AtomicBoolean();

    private GalleryVersionMaintenance() {
    }

    static void showUpdateConfirmation(@NonNull Context context) {
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
                        (dialog, which) -> new UpdateTask(context).execute())
                .show();
    }

    static void scanAndConfirmCleanup(@NonNull Context context) {
        Toast.makeText(context, R.string.gallery_version_cleanup_scanning,
                Toast.LENGTH_SHORT).show();
        new CleanupScanTask(context).execute();
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

    private static final class CleanupScanTask extends AsyncTask<Void, Void, CleanupResult> {
        private final Context context;
        private final Context application;
        private final DownloadManager manager;

        CleanupScanTask(@NonNull Context context) {
            this.context = context;
            application = context.getApplicationContext();
            manager = EhApplication.getDownloadManager(application);
        }

        @Override
        protected CleanupResult doInBackground(Void... ignored) {
            Map<Long, List<DownloadInfo>> families = new HashMap<>();
            for (DownloadInfo info : new ArrayList<>(manager.getAllDownloadInfoList())) {
                if (info.firstGid != null && info.firstGid > 0L
                        && !DownloadManager.isImportedGallery(info)) {
                    families.computeIfAbsent(info.firstGid, key -> new ArrayList<>()).add(info);
                }
            }

            List<CleanupFamily> result = new ArrayList<>();
            for (List<DownloadInfo> family : families.values()) {
                DownloadInfo keeper = null;
                for (DownloadInfo info : family) {
                    if (manager.isCompleteUsableGallery(info)
                            && (keeper == null || info.gid > keeper.gid)) {
                        keeper = info;
                    }
                }
                if (keeper == null) {
                    continue;
                }
                List<DownloadInfo> old = new ArrayList<>();
                for (DownloadInfo info : family) {
                    if (info.gid < keeper.gid && info.state != DownloadInfo.STATE_WAIT
                            && info.state != DownloadInfo.STATE_DOWNLOAD
                            && info.state != DownloadInfo.STATE_UPDATE) {
                        old.add(info);
                    }
                }
                if (!old.isEmpty()) {
                    old.sort(Comparator.comparingLong(value -> value.gid));
                    result.add(new CleanupFamily(keeper, old));
                }
            }
            return new CleanupResult(result);
        }

        @Override
        protected void onPostExecute(CleanupResult result) {
            if (result.galleries == 0) {
                Toast.makeText(application, R.string.gallery_version_cleanup_none,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(R.string.settings_delete_old_gallery_versions)
                    .setMessage(application.getString(
                            R.string.gallery_version_cleanup_confirm,
                            result.galleries, result.families.size()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> new CleanupTask(application, manager, result).execute())
                    .show();
        }
    }

    private static final class CleanupTask extends AsyncTask<Void, Void, Void> {
        private final Context application;
        private final DownloadManager manager;
        private final CleanupResult result;

        CleanupTask(Context application, DownloadManager manager, CleanupResult result) {
            this.application = application;
            this.manager = manager;
            this.result = result;
        }

        @Override
        protected Void doInBackground(Void... ignored) {
            for (CleanupFamily family : result.families) {
                DownloadInfo nearest = manager.findClosestOlderGalleryVersion(
                        family.keeper.firstGid, family.keeper.gid);
                if (nearest != null) {
                    GalleryUpdateManager.migrateReadingProgress(
                            application, nearest.gid, family.keeper);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void ignored) {
            LongList gids = new LongList();
            List<DownloadInfo> deleted = new ArrayList<>();
            for (CleanupFamily family : result.families) {
                DownloadInfo keeper = manager.getDownloadInfo(family.keeper.gid);
                if (keeper == null || keeper.firstGid == null
                        || !keeper.firstGid.equals(family.keeper.firstGid)
                        || keeper.state != DownloadInfo.STATE_FINISH || keeper.legacy != 0) {
                    continue;
                }
                for (DownloadInfo info : family.old) {
                    // Revalidate the snapshot before deleting anything.
                    DownloadInfo current = manager.getDownloadInfo(info.gid);
                    if (current != null && current.firstGid != null
                            && current.firstGid.equals(family.keeper.firstGid)
                            && current.gid < family.keeper.gid
                            && current.state != DownloadInfo.STATE_WAIT
                            && current.state != DownloadInfo.STATE_DOWNLOAD
                            && current.state != DownloadInfo.STATE_UPDATE) {
                        gids.add(current.gid);
                        deleted.add(current);
                    }
                }
            }
            manager.deleteRangeDownload(gids);
            EhApplication.getExecutorService(application).execute(() -> {
                for (DownloadInfo info : deleted) {
                    UniFile directory = SpiderDen.getExistingGalleryDownloadDir(info);
                    if (directory == null || !directory.exists() || directory.delete()) {
                        EhDB.removeDownloadDirname(info.gid);
                    }
                }
            });
            Toast.makeText(application, application.getString(
                    R.string.gallery_version_cleanup_done, deleted.size()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static final class CleanupFamily {
        final DownloadInfo keeper;
        final List<DownloadInfo> old;

        CleanupFamily(DownloadInfo keeper, List<DownloadInfo> old) {
            this.keeper = keeper;
            this.old = old;
        }
    }

    private static final class CleanupResult {
        final List<CleanupFamily> families;
        final int galleries;

        CleanupResult(List<CleanupFamily> families) {
            this.families = families;
            int count = 0;
            for (CleanupFamily family : families) {
                count += family.old.size();
            }
            galleries = count;
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
