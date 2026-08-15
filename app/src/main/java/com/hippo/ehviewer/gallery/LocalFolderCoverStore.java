/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Durable, app-owned thumbnails for galleries backed by Storage Access Framework folders. */
public final class LocalFolderCoverStore {

    private static final String TAG = LocalFolderCoverStore.class.getSimpleName();
    private static final String DIRECTORY_NAME = "local_folder_covers";
    private static final int MAX_COVER_EDGE = 480;
    private static final int JPEG_QUALITY = 86;

    private LocalFolderCoverStore() {}

    /** Returns an existing non-empty cover, or {@code null}. */
    @Nullable
    public static File find(@NonNull Context context, long galleryId) {
        File file = coverFile(context, galleryId, false);
        return file != null && file.isFile() && file.length() > 0 ? file : null;
    }

    /**
     * Ensures that a small, standard JPEG exists in app-private storage.
     * The source URI remains in DownloadInfo so old records and lost covers can be rebuilt.
     */
    @Nullable
    public static synchronized File ensure(
            @NonNull Context context, long galleryId, @Nullable String sourceUri) {
        File existing = find(context, galleryId);
        if (existing != null) {
            return existing;
        }
        if (sourceUri == null || sourceUri.isEmpty()) {
            return null;
        }
        File target = coverFile(context, galleryId, true);
        if (target == null) {
            return null;
        }
        if (target.exists() && !target.delete()) {
            return null;
        }
        File temporary = new File(target.getParentFile(), target.getName() + "."
                + Thread.currentThread().getId() + ".tmp");
        Bitmap decoded = null;
        Bitmap scaled = null;
        Bitmap flattened = null;
        try {
            Uri uri = Uri.parse(sourceUri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            int sampleSize = calculateSampleSize(
                    bounds.outWidth, bounds.outHeight, MAX_COVER_EDGE);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                decoded = BitmapFactory.decodeStream(input, null, options);
            }
            if (decoded == null) {
                return null;
            }

            int largestEdge = Math.max(decoded.getWidth(), decoded.getHeight());
            if (largestEdge > MAX_COVER_EDGE) {
                float scale = MAX_COVER_EDGE / (float) largestEdge;
                int width = Math.max(1, Math.round(decoded.getWidth() * scale));
                int height = Math.max(1, Math.round(decoded.getHeight() * scale));
                scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
            } else {
                scaled = decoded;
            }

            // JPEG has no alpha. Flatten explicitly so transparent covers do not become black.
            flattened = Bitmap.createBitmap(
                    scaled.getWidth(), scaled.getHeight(), Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(flattened);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(scaled, 0.0f, 0.0f, null);

            if (temporary.exists() && !temporary.delete()) {
                return null;
            }
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    return null;
                }
                output.flush();
            }
            if (target.exists()) {
                // Another worker completed the same cover while this one was decoding it.
                return target.length() > 0 ? target : null;
            }
            if (!temporary.renameTo(target)) {
                return null;
            }
            return target;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "Failed to create local folder cover for " + galleryId, e);
            return null;
        } finally {
            if (temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            if (flattened != null && !flattened.isRecycled()) {
                flattened.recycle();
            }
            if (scaled != null && scaled != decoded && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    public static synchronized void delete(@NonNull Context context, long galleryId) {
        File file = coverFile(context, galleryId, false);
        if (file != null && file.isFile() && !file.delete()) {
            Log.w(TAG, "Failed to delete local folder cover for " + galleryId);
        }
    }

    static int calculateSampleSize(int width, int height, int targetEdge) {
        int sampleSize = 1;
        int largestEdge = Math.max(width, height);
        if (largestEdge <= 0 || targetEdge <= 0) {
            return sampleSize;
        }
        while (largestEdge / sampleSize >= targetEdge * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    @Nullable
    private static File coverFile(
            @NonNull Context context, long galleryId, boolean createDirectory) {
        File filesDir = context.getFilesDir();
        if (filesDir == null) {
            return null;
        }
        File directory = new File(filesDir, DIRECTORY_NAME);
        if (!directory.isDirectory()
                && (!createDirectory || !directory.mkdirs() && !directory.isDirectory())) {
            return null;
        }
        return new File(directory, Long.toString(galleryId) + "_480.jpg");
    }

}
