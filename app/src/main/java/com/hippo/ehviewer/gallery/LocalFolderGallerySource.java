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

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A persistent Storage Access Framework tree reference plus a relative gallery directory. */
public final class LocalFolderGallerySource {

    private static final String PREFIX = "ehviewer-local-folder-v1:";

    @NonNull
    public final String treeUri;
    @NonNull
    public final String relativePath;

    private LocalFolderGallerySource(
            @NonNull String treeUri, @NonNull String relativePath) {
        this.treeUri = treeUri;
        this.relativePath = relativePath;
    }

    @NonNull
    public static LocalFolderGallerySource create(
            @NonNull Uri treeUri, @Nullable String relativePath) {
        return create(treeUri.toString(), relativePath);
    }

    @NonNull
    static LocalFolderGallerySource create(
            @NonNull String treeUri, @Nullable String relativePath) {
        if (treeUri.isEmpty()) {
            throw new IllegalArgumentException("Tree URI must not be empty");
        }
        return new LocalFolderGallerySource(treeUri, normalizeRelativePath(relativePath));
    }

    public static boolean isLocalFolderGallery(@Nullable String value) {
        return value != null && value.startsWith(PREFIX);
    }

    @Nullable
    public static LocalFolderGallerySource parse(@Nullable String value) {
        if (!isLocalFolderGallery(value)) {
            return null;
        }
        int lengthEnd = value.indexOf(':', PREFIX.length());
        if (lengthEnd < 0) {
            return null;
        }
        try {
            int treeUriLength = Integer.parseInt(value.substring(PREFIX.length(), lengthEnd));
            int treeUriStart = lengthEnd + 1;
            int treeUriEnd = treeUriStart + treeUriLength;
            if (treeUriLength <= 0 || treeUriEnd > value.length()) {
                return null;
            }
            return create(value.substring(treeUriStart, treeUriEnd),
                    value.substring(treeUriEnd));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @NonNull
    public String encode() {
        return PREFIX + treeUri.length() + ':' + treeUri + relativePath;
    }

    /** Returns a stable negative ID, keeping imported folders outside the positive E-H gallery range. */
    public long stableGalleryId() {
        byte[] bytes = encode().getBytes(StandardCharsets.UTF_8);
        long hash;
        try {
            hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getLong();
        } catch (NoSuchAlgorithmException ignored) {
            hash = encode().hashCode();
        }
        long positive = hash & Long.MAX_VALUE;
        return positive == 0 ? -1 : -positive;
    }

    @NonNull
    public Uri getTreeUri() {
        return Uri.parse(treeUri);
    }

    @NonNull
    private static String normalizeRelativePath(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid relative path");
            }
        }
        return normalized;
    }
}
