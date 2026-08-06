package com.hippo.ehviewer.ui.fragment;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;

final class MissingGalleryCleaner {

    private MissingGalleryCleaner() {
    }

    static void showConfirmation(@NonNull Context context, int titleResId) {
        new AlertDialog.Builder(context)
                .setTitle(titleResId)
                .setMessage(R.string.settings_download_delete_missing_galleries_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> new CleanTask(context).execute())
                .show();
    }

    private static class CleanTask extends AsyncTask<Void, Integer, ScanResult> {

        private final Context mContext;
        private final EhApplication mApplication;
        private final DownloadManager mDownloadManager;
        private ProgressDialog mProgressDialog;

        CleanTask(@NonNull Context context) {
            mContext = context;
            mApplication = (EhApplication) context.getApplicationContext();
            mDownloadManager = EhApplication.getDownloadManager(mApplication);
        }

        @Override
        protected void onPreExecute() {
            mProgressDialog = new ProgressDialog(mContext);
            mProgressDialog.setTitle(
                    R.string.settings_download_delete_missing_galleries_scanning);
            mProgressDialog.setIndeterminate(false);
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mProgressDialog.setCancelable(false);
            mProgressDialog.show();
        }

        @Override
        protected ScanResult doInBackground(Void... ignored) {
            UniFile downloadRoot = Settings.getDownloadLocation();
            if (downloadRoot == null || !downloadRoot.exists()
                    || !downloadRoot.isDirectory() || !downloadRoot.canRead()
                    || downloadRoot.listFiles() == null) {
                return new ScanResult(false, new ArrayList<>());
            }

            List<DownloadInfo> downloads = new ArrayList<>(
                    mDownloadManager.getAllDownloadInfoList());
            List<DownloadInfo> missingDownloads = new ArrayList<>();
            int total = downloads.size();
            publishProgress(0, total);
            for (int i = 0; i < total; i++) {
                DownloadInfo info = downloads.get(i);
                if (isLocalGalleryMissing(info)) {
                    missingDownloads.add(info);
                }
                publishProgress(i + 1, total);
            }
            return new ScanResult(true, missingDownloads);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (mProgressDialog == null || values.length < 2) {
                return;
            }
            mProgressDialog.setMax(values[1]);
            mProgressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(ScanResult result) {
            dismissProgressDialog();
            if (!result.locationAvailable) {
                Toast.makeText(mApplication,
                        R.string.settings_download_invalid_download_location,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (result.missingDownloads.isEmpty()) {
                Toast.makeText(mApplication,
                        R.string.settings_download_delete_missing_galleries_none,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            LongList gidList = new LongList();
            for (DownloadInfo info : result.missingDownloads) {
                gidList.add(info.gid);
            }
            mDownloadManager.deleteRangeDownload(gidList);
            deleteEmptyGalleryDirectories(mApplication, result.missingDownloads);
            Toast.makeText(mApplication, mApplication.getString(
                            R.string.settings_download_delete_missing_galleries_done,
                            result.missingDownloads.size()),
                    Toast.LENGTH_LONG).show();
        }

        private void dismissProgressDialog() {
            if (mProgressDialog == null) {
                return;
            }
            try {
                if (mProgressDialog.isShowing()) {
                    mProgressDialog.dismiss();
                }
            } catch (IllegalArgumentException e) {
                ExceptionUtils.throwIfFatal(e);
            }
            mProgressDialog = null;
        }
    }

    private static boolean isLocalGalleryMissing(@NonNull DownloadInfo info) {
        if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
            return false;
        }
        UniFile directory = SpiderDen.getExistingGalleryDownloadDir(info);
        return directory == null || !directory.exists()
                || isGalleryDirectoryEmpty(directory);
    }

    private static boolean isGalleryDirectoryEmpty(@NonNull UniFile directory) {
        if (!directory.isDirectory()) {
            return false;
        }
        UniFile[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        for (UniFile file : files) {
            String name = file.getName();
            if (DownloadManager.DOWNLOAD_INFO_FILENAME.equals(name)
                    || ".nomedia".equals(name)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static void deleteEmptyGalleryDirectories(
            @NonNull Context context,
            @NonNull List<DownloadInfo> downloads) {
        EhApplication.getExecutorService(context).execute(() -> {
            for (DownloadInfo info : downloads) {
                UniFile directory = SpiderDen.getExistingGalleryDownloadDir(info);
                EhDB.removeDownloadDirname(info.gid);
                if (directory != null && directory.exists()
                        && isGalleryDirectoryEmpty(directory)) {
                    directory.delete();
                }
            }
        });
    }

    private static class ScanResult {
        final boolean locationAvailable;
        final List<DownloadInfo> missingDownloads;

        ScanResult(boolean locationAvailable, List<DownloadInfo> missingDownloads) {
            this.locationAvailable = locationAvailable;
            this.missingDownloads = missingDownloads;
        }
    }
}
