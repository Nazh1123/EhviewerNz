/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.preference;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Deletes superseded downloads using the same modal task UI as redundancy cleanup. */
public class DeleteOldGalleryVersionsPreference extends TaskPreference {

    public DeleteOldGalleryVersionsPreference(Context context) {
        super(context);
    }

    public DeleteOldGalleryVersionsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DeleteOldGalleryVersionsPreference(
            Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @NonNull
    @Override
    protected Task onCreateTask() {
        return new CleanupTask(getContext());
    }

    private static final class CleanupTask extends Task {
        private final EhApplication application;
        private final DownloadManager manager;

        CleanupTask(@NonNull Context context) {
            super(context);
            application = getApplication();
            manager = EhApplication.getDownloadManager(application);
        }

        @Override
        protected Object doInBackground(Void... ignored) {
            // Grouping is deliberately cheap: unknown/unavailable metadata and local imports
            // cannot establish a version family and are excluded before any file validation.
            Map<Long, List<DownloadInfo>> grouped = new HashMap<>();
            for (DownloadInfo info : new ArrayList<>(manager.getAllDownloadInfoList())) {
                if (info.firstGid != null && info.firstGid > 0L
                        && !DownloadManager.isImportedGallery(info)) {
                    grouped.computeIfAbsent(info.firstGid, key -> new ArrayList<>()).add(info);
                }
            }

            List<List<DownloadInfo>> duplicates = new ArrayList<>();
            int validationTotal = 0;
            for (List<DownloadInfo> family : grouped.values()) {
                if (family.size() > 1) {
                    duplicates.add(family);
                    validationTotal += family.size();
                }
            }
            int maximumDeletes = validationTotal - duplicates.size();
            int totalWork = validationTotal + maximumDeletes;
            publishProgress(new int[]{0, totalWork});

            int checked = 0;
            List<CleanupFamily> result = new ArrayList<>();
            for (List<DownloadInfo> family : duplicates) {
                family.sort(Comparator.comparingLong((DownloadInfo value) -> value.gid)
                        .reversed());
                DownloadInfo keeper = null;
                int inspected = 0;
                for (DownloadInfo info : family) {
                    inspected++;
                    if (manager.isCompleteUsableGallery(info)) {
                        keeper = info;
                    }
                    publishProgress(new int[]{++checked, totalWork});
                    // The family is ordered newest first, so no older item can replace keeper.
                    if (keeper != null) {
                        break;
                    }
                }
                checked += family.size() - inspected;
                publishProgress(new int[]{checked, totalWork});
                if (keeper == null) {
                    continue;
                }

                List<DownloadInfo> old = new ArrayList<>();
                for (DownloadInfo info : family) {
                    if (info.gid < keeper.gid && !isActive(info)) {
                        old.add(info);
                    }
                }
                if (old.isEmpty()) {
                    continue;
                }
                old.sort(Comparator.comparingLong(value -> value.gid));
                result.add(new CleanupFamily(keeper, old));
            }

            // DownloadManager owns UI-observed collections, so remove their records on the
            // main thread while this worker keeps the modal task window open.
            List<CleanupFamily> deletedFamilies = new ArrayList<>();
            List<DownloadInfo> deleted = new ArrayList<>();
            CountDownLatch removalFinished = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    LongList gids = new LongList();
                    for (CleanupFamily family : result) {
                        DownloadInfo keeper = manager.getDownloadInfo(family.keeper.gid);
                        if (keeper == null || keeper.firstGid == null
                                || !keeper.firstGid.equals(family.keeper.firstGid)
                                || keeper.state != DownloadInfo.STATE_FINISH
                                || keeper.legacy != 0) {
                            continue;
                        }
                        List<DownloadInfo> old = new ArrayList<>();
                        for (DownloadInfo candidate : family.old) {
                            DownloadInfo current = manager.getDownloadInfo(candidate.gid);
                            if (current != null && current.firstGid != null
                                    && current.firstGid.equals(keeper.firstGid)
                                    && current.gid < keeper.gid && !isActive(current)) {
                                gids.add(current.gid);
                                old.add(current);
                                deleted.add(current);
                            }
                        }
                        if (!old.isEmpty()) {
                            deletedFamilies.add(new CleanupFamily(keeper, old));
                        }
                    }
                    manager.deleteRangeDownload(gids);
                } finally {
                    removalFinished.countDown();
                }
            });
            boolean interrupted = false;
            for (;;) {
                try {
                    removalFinished.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            int completed = validationTotal;
            for (CleanupFamily family : deletedFamilies) {
                // Migrate from the nearest old version before its directory is removed.
                DownloadInfo nearest = family.old.get(family.old.size() - 1);
                GalleryUpdateManager.migrateReadingProgress(
                        application, nearest.gid, family.keeper);
            }
            for (DownloadInfo info : deleted) {
                UniFile directory = SpiderDen.getExistingGalleryDownloadDir(info);
                if (directory == null || !directory.exists() || directory.delete()) {
                    EhDB.removeDownloadDirname(info.gid);
                }
                publishProgress(new int[]{++completed, totalWork});
            }
            publishProgress(new int[]{totalWork, totalWork});
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return deleted.size();
        }

        @Override
        protected void onPostExecute(Object value) {
            int deleted = value instanceof Integer ? (Integer) value : 0;
            String message = deleted == 0
                    ? application.getString(R.string.gallery_version_cleanup_none)
                    : application.getString(
                            R.string.gallery_version_cleanup_done, deleted);
            Toast.makeText(application, message, Toast.LENGTH_LONG).show();
            super.onPostExecute(value);
        }

        private static boolean isActive(DownloadInfo info) {
            return info.state == DownloadInfo.STATE_WAIT
                    || info.state == DownloadInfo.STATE_DOWNLOAD
                    || info.state == DownloadInfo.STATE_UPDATE;
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

}
