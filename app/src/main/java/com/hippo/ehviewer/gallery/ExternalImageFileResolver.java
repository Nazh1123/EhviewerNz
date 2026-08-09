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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

/** Resolves an externally opened image to a local file whose parent can be enumerated. */
public final class ExternalImageFileResolver {

    private static final String EXTERNAL_STORAGE_DOCUMENTS =
            "com.android.externalstorage.documents";
    private static final String MEDIA_DOCUMENTS = "com.android.providers.media.documents";

    private ExternalImageFileResolver() {}

    public static boolean isImageUri(@NonNull Context context, @NonNull Uri uri) {
        String type = null;
        try {
            type = context.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
        }
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }

        if (isSupportedImageName(uri.getLastPathSegment())) {
            return true;
        }
        return isSupportedImageName(queryDisplayName(context, uri));
    }

    @Nullable
    public static File resolve(@NonNull Context context, @NonNull Uri uri) {
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
            return existingFile(uri.getPath());
        }
        if (!ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
            return null;
        }

        File file = existingFile(queryDataColumn(context, uri, null, null));
        if (file != null) {
            return file;
        }

        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                String documentId = DocumentsContract.getDocumentId(uri);
                String authority = uri.getAuthority();
                if (EXTERNAL_STORAGE_DOCUMENTS.equals(authority)) {
                    file = resolveExternalStorageDocument(context, documentId);
                } else if (MEDIA_DOCUMENTS.equals(authority)) {
                    file = resolveMediaDocument(context, documentId);
                }
                if (file != null) {
                    return file;
                }
            }
        } catch (RuntimeException ignored) {
        }

        // A few file providers expose the absolute filesystem path as their URI path.
        return existingFile(uri.getPath());
    }

    public static boolean isSupportedImageName(@Nullable String name) {
        if (name == null) {
            return false;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String extension : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static File resolveExternalStorageDocument(
            @NonNull Context context, @NonNull String documentId) {
        String[] parts = documentId.split(":", 2);
        if (parts.length != 2) {
            return null;
        }

        String volume = parts[0];
        String relativePath = parts[1];
        if ("primary".equalsIgnoreCase(volume)) {
            return existingFile(new File(Environment.getExternalStorageDirectory(), relativePath));
        }

        File[] externalDirs = context.getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File externalDir : externalDirs) {
                File volumeRoot = findVolumeRoot(externalDir);
                if (volumeRoot != null && volume.equalsIgnoreCase(volumeRoot.getName())) {
                    return existingFile(new File(volumeRoot, relativePath));
                }
            }
        }
        return existingFile(new File(new File("/storage", volume), relativePath));
    }

    @Nullable
    private static File resolveMediaDocument(
            @NonNull Context context, @NonNull String documentId) {
        String[] parts = documentId.split(":", 2);
        if (parts.length != 2 || !"image".equals(parts[0])) {
            return null;
        }
        String path = queryDataColumn(
                context,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media._ID + "=?",
                new String[]{parts[1]});
        return existingFile(path);
    }

    @Nullable
    private static File findVolumeRoot(@Nullable File externalDir) {
        File current = externalDir;
        while (current != null) {
            File parent = current.getParentFile();
            if (parent != null && "Android".equals(current.getName())) {
                return parent;
            }
            current = parent;
        }
        return null;
    }

    @Nullable
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static String queryDataColumn(
            @NonNull Context context,
            @NonNull Uri uri,
            @Nullable String selection,
            @Nullable String[] selectionArgs) {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DATA},
                selection,
                selectionArgs,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    @Nullable
    private static File existingFile(@Nullable String path) {
        return path == null || path.isEmpty() ? null : existingFile(new File(path));
    }

    @Nullable
    private static File existingFile(@NonNull File file) {
        return file.isFile() ? file : null;
    }
}
