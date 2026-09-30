/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.download.part;

import static com.hippo.util.FileUtils.getFileName;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.gallery.A7ZipArchive;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UniRandomAccessFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地下载压缩包导入。
 */
public class DownloadArchiveImporter {

    private static final String TAG = DownloadArchiveImporter.class.getSimpleName();

    public interface Host {
        @Nullable
        Context getEHContext();

        String getString(int resId);

        void runOnUiThread(Runnable runnable);

        void updateForLabel();

        void updateView();

        @Nullable
        DownloadManager getDownloadManager();
    }

    @NonNull
    private final Host mHost;

    public DownloadArchiveImporter(@NonNull Host host) {
        mHost = host;
    }

    public void importLocalArchive(@NonNull ActivityResultLauncher<Intent> filePickerLauncher) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-rar",
                "application/rar",
                "application/x-cbz",
                "application/x-cbr"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // CRITICAL: Add flags to enable persistent URI permissions
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, mHost.getString(R.string.import_archive_title)));
        } catch (Exception e) {
            Context context = mHost.getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void handleSelectedFile(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }

        Context context = mHost.getEHContext();
        if (context == null) {
            return;
        }

        // CRITICAL: Request persistent URI permission IMMEDIATELY when file is selected
        // This is the key to solving the permission loss issue after app restart
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.d(TAG, "Successfully obtained persistent URI permission for: " + uri);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: " + uri, e);
            Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: " + uri, e);
            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Show processing dialog
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show();

        // Process the archive file in background
        new Thread(() -> processArchiveFile(uri)).start();
    }

    private void processArchiveFile(Uri uri) {
        Context context = mHost.getEHContext();
        if (context == null) {
            return;
        }

        try {
            // Verify URI accessibility (permission should already be granted)
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    mHost.runOnUiThread(() ->
                            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot access file even with persistent permission", e);
                mHost.runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Get file name
            String fileName = getFileName(context, uri);
            if (fileName == null) {
                fileName = "imported_archive_" + System.currentTimeMillis();
            }

            // Validate file format
            if (!isValidArchiveFormat(fileName)) {
                mHost.runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_invalid_format, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Create DownloadInfo for the archive
            DownloadInfo downloadInfo = createArchiveDownloadInfo(context, uri, fileName);
            if (downloadInfo == null) {
                mHost.runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            DownloadManager downloadManager = mHost.getDownloadManager();
            // Check if already imported
            if (downloadManager != null && downloadManager.containDownloadInfo(downloadInfo.gid)) {
                mHost.runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_already_imported, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Add to download manager
            if (downloadManager != null) {
                List<DownloadInfo> downloadList = new ArrayList<>();
                downloadList.add(downloadInfo);
                downloadManager.addDownload(downloadList);
                mHost.runOnUiThread(() -> {
                    Toast.makeText(context, R.string.import_archive_success, Toast.LENGTH_SHORT).show();
                    mHost.updateForLabel();
                    mHost.updateView();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process archive file", e);
            mHost.runOnUiThread(() ->
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private boolean isValidArchiveFormat(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr");
    }

    private DownloadInfo createArchiveDownloadInfo(Context context, Uri uri, String fileName) {
        try {
            DownloadInfo downloadInfo = new DownloadInfo();
            downloadInfo.gid = System.currentTimeMillis(); // Use timestamp as unique ID
            downloadInfo.token = "";
            downloadInfo.title = fileName.replaceAll("\\.[^.]*$", ""); // Remove extension
            downloadInfo.titleJpn = null;
            downloadInfo.thumb = null; // No thumbnail for imported archives
            downloadInfo.category = EhUtils.UNKNOWN; // Keep as UNKNOWN, will be handled in display logic
            downloadInfo.posted = null;
            downloadInfo.uploader = "Local Archive";
            downloadInfo.rating = -1.0f; // Keep default rating to not affect other downloads
            downloadInfo.state = DownloadInfo.STATE_FINISH;
            downloadInfo.legacy = 0;
            downloadInfo.time = System.currentTimeMillis();
            downloadInfo.label = null;
            int pageCount = countArchiveImages(context, uri);
            downloadInfo.pages = pageCount;
            downloadInfo.total = pageCount;
            downloadInfo.finished = pageCount;
            downloadInfo.downloaded = pageCount;

            // Store the URI in the archiveUri field - this is the key identifier
            downloadInfo.archiveUri = uri.toString();

            return downloadInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DownloadInfo", e);
            return null;
        }
    }

    private int countArchiveImages(@NonNull Context context, @NonNull Uri uri) {
        UniRandomAccessFile randomAccessFile = null;
        A7ZipArchive archive = null;
        try {
            UniFile file = UniFile.fromUri(context, uri);
            if (file == null || !file.exists()) {
                return 0;
            }
            randomAccessFile = file.createRandomAccessFile("r");
            if (randomAccessFile == null) {
                return 0;
            }
            archive = A7ZipArchive.create(randomAccessFile);
            return archive == null ? 0 : archive.getArchiveEntries().size();
        } catch (Exception e) {
            Log.w(TAG, "Failed to count imported archive pages: " + uri, e);
            return 0;
        } finally {
            if (archive != null) {
                try {
                    archive.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to close imported archive", e);
                }
            }
            if (randomAccessFile != null) {
                try {
                    randomAccessFile.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close imported archive file", e);
                }
            }
        }
    }
}
