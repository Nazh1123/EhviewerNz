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
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;

import com.hippo.util.NaturalComparator;
import com.hippo.util.PathNaturalComparator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded recursive scanner used by local-folder import and viewing. */
public final class LocalFolderGalleryScanner {

    public static final int MAX_DIRECTORIES = 512;
    public static final int MAX_ENTRIES = 20_000;
    public static final int MAX_IMAGES = 10_000;
    public static final int MAX_DEPTH = 16;
    public static final long MAX_SCAN_MILLIS = 15_000L;

    private static final NaturalComparator NAME_COMPARATOR = new NaturalComparator();
    private static final PathNaturalComparator PATH_COMPARATOR = new PathNaturalComparator();
    private LocalFolderGalleryScanner() {}

    public static boolean isUnsafeSelection(@NonNull Uri treeUri) {
        String authority = treeUri.getAuthority();
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (RuntimeException ignored) {
            return true;
        }
        if (documentId == null || documentId.isEmpty()) {
            return true;
        }
        String normalizedId = documentId.replace('\\', '/');
        if ("root".equalsIgnoreCase(normalizedId)
                || "home".equalsIgnoreCase(normalizedId)
                || "downloads".equalsIgnoreCase(normalizedId)) {
            return true;
        }
        if ("com.android.externalstorage.documents".equals(authority)) {
            int separator = normalizedId.indexOf(':');
            return separator < 0
                    || normalizedId.substring(separator + 1).replace("/", "").isEmpty();
        }
        return false;
    }

    @NonNull
    public static ScanResult scan(
            @NonNull Context context, @NonNull LocalFolderGallerySource source)
            throws ScanException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_SCAN_MILLIS);
        ResolvedDirectory root;
        try {
            root = resolveRoot(context, source, deadline);
        } catch (ScanException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScanException(Reason.INACCESSIBLE, e);
        }

        ArrayDeque<PendingDirectory> pending = new ArrayDeque<>();
        pending.add(new PendingDirectory(root.uri, "", 0));
        Set<String> visitedDirectories = new HashSet<>();
        List<ImageEntry> images = new ArrayList<>();
        int directoryCount = 0;
        int entryCount = 0;
        int directImageCount = 0;

        while (!pending.isEmpty()) {
            checkCancelledOrExpired(deadline);
            PendingDirectory directory = pending.removeFirst();
            if (!visitedDirectories.add(directory.uri.toString())) {
                continue;
            }
            if (++directoryCount > MAX_DIRECTORIES) {
                throw new ScanException(Reason.TOO_LARGE);
            }

            List<NamedDocument> children = new ArrayList<>();
            try {
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        directory.uri, DocumentsContract.getDocumentId(directory.uri));
                String[] projection = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                };
                try (Cursor cursor = context.getContentResolver().query(
                        childrenUri, projection, null, null, null)) {
                    if (cursor == null) {
                        throw new ScanException(Reason.INACCESSIBLE);
                    }
                    int idColumn = cursor.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int nameColumn = cursor.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int typeColumn = cursor.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_MIME_TYPE);
                    while (cursor.moveToNext()) {
                        checkCancelledOrExpired(deadline);
                        if (++entryCount > MAX_ENTRIES) {
                            throw new ScanException(Reason.TOO_LARGE);
                        }
                        String documentId = cursor.getString(idColumn);
                        String name = cursor.getString(nameColumn);
                        String mimeType = cursor.getString(typeColumn);
                        if (documentId == null || name == null || name.isEmpty()) {
                            continue;
                        }
                        children.add(new NamedDocument(
                                DocumentsContract.buildDocumentUriUsingTree(
                                        directory.uri, documentId),
                                name,
                                DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)));
                    }
                }
            } catch (ScanException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ScanException(Reason.INACCESSIBLE, e);
            }
            Collections.sort(children, (left, right) ->
                    NAME_COMPARATOR.compare(left.name, right.name));
            for (NamedDocument child : children) {
                checkCancelledOrExpired(deadline);
                String name = child.name;
                String relativePath = directory.relativePath.isEmpty()
                        ? name : directory.relativePath + '/' + name;
                if (child.directory) {
                    if (directory.depth >= MAX_DEPTH) {
                        throw new ScanException(Reason.TOO_LARGE);
                    }
                    pending.addLast(new PendingDirectory(
                            child.uri, relativePath, directory.depth + 1));
                } else if (isSupportedImage(name)) {
                    if (images.size() >= MAX_IMAGES) {
                        throw new ScanException(Reason.TOO_LARGE);
                    }
                    images.add(new ImageEntry(child.uri, relativePath, name));
                    if (directory.depth == 0) {
                        directImageCount++;
                    }
                }
            }
        }

        Collections.sort(images, (left, right) ->
                PATH_COMPARATOR.compare(left.relativePath, right.relativePath));
        return new ScanResult(root.name, images, directImageCount,
                directoryCount, entryCount);
    }

    @NonNull
    private static ResolvedDirectory resolveRoot(
            @NonNull Context context,
            @NonNull LocalFolderGallerySource source,
            long deadline) throws ScanException {
        Uri treeUri = source.getTreeUri();
        Uri current;
        try {
            current = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        } catch (RuntimeException e) {
            throw new ScanException(Reason.INACCESSIBLE, e);
        }
        ResolvedDirectory resolved = queryDirectory(context, current);
        if (!source.relativePath.isEmpty()) {
            for (String segment : source.relativePath.split("/")) {
                checkCancelledOrExpired(deadline);
                resolved = findChildDirectory(context, resolved.uri, segment, deadline);
            }
        }
        return resolved;
    }

    @NonNull
    private static ResolvedDirectory queryDirectory(
            @NonNull Context context, @NonNull Uri uri) throws ScanException {
        String[] projection = {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = context.getContentResolver().query(
                uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new ScanException(Reason.INACCESSIBLE);
            }
            String name = cursor.getString(cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME));
            String type = cursor.getString(cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE));
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                throw new ScanException(Reason.INACCESSIBLE);
            }
            return new ResolvedDirectory(uri,
                    name == null || name.isEmpty() ? "Local folder" : name);
        } catch (ScanException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScanException(Reason.INACCESSIBLE, e);
        }
    }

    @NonNull
    private static ResolvedDirectory findChildDirectory(
            @NonNull Context context,
            @NonNull Uri parent,
            @NonNull String childName,
            long deadline) throws ScanException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent));
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = context.getContentResolver().query(
                childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                throw new ScanException(Reason.INACCESSIBLE);
            }
            int idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int typeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);
            int inspected = 0;
            while (cursor.moveToNext()) {
                checkCancelledOrExpired(deadline);
                if (++inspected > MAX_ENTRIES) {
                    throw new ScanException(Reason.TOO_LARGE);
                }
                String name = cursor.getString(nameColumn);
                String type = cursor.getString(typeColumn);
                if (childName.equals(name)
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(
                            parent, cursor.getString(idColumn));
                    return new ResolvedDirectory(child, name);
                }
            }
            throw new ScanException(Reason.INACCESSIBLE);
        } catch (ScanException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScanException(Reason.INACCESSIBLE, e);
        }
    }

    private static boolean isSupportedImage(@NonNull String filename) {
        String lowerName = filename.toLowerCase(Locale.ROOT);
        for (String extension : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static void checkCancelledOrExpired(long deadline) throws ScanException {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScanException(Reason.CANCELLED);
        }
        if (System.nanoTime() > deadline) {
            throw new ScanException(Reason.TOO_LARGE);
        }
    }

    public enum Reason {
        INACCESSIBLE,
        TOO_LARGE,
        CANCELLED
    }

    public static final class ScanException extends Exception {
        @NonNull
        public final Reason reason;

        ScanException(@NonNull Reason reason) {
            this.reason = reason;
        }

        ScanException(@NonNull Reason reason, @NonNull Throwable cause) {
            super(cause);
            this.reason = reason;
        }
    }

    public static final class ImageEntry {
        @NonNull
        public final Uri uri;
        @NonNull
        public final String relativePath;
        @NonNull
        public final String filename;

        ImageEntry(
                @NonNull Uri uri, @NonNull String relativePath, @NonNull String filename) {
            this.uri = uri;
            this.relativePath = relativePath;
            this.filename = filename;
        }
    }

    public static final class ScanResult {
        @NonNull
        public final String rootName;
        @NonNull
        public final List<ImageEntry> images;
        public final int directImageCount;
        public final int directoryCount;
        public final int entryCount;

        ScanResult(
                @NonNull String rootName,
                @NonNull List<ImageEntry> images,
                int directImageCount,
                int directoryCount,
                int entryCount) {
            this.rootName = rootName;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.directImageCount = directImageCount;
            this.directoryCount = directoryCount;
            this.entryCount = entryCount;
        }
    }

    private static final class PendingDirectory {
        @NonNull
        final Uri uri;
        @NonNull
        final String relativePath;
        final int depth;

        PendingDirectory(
                @NonNull Uri uri, @NonNull String relativePath, int depth) {
            this.uri = uri;
            this.relativePath = relativePath;
            this.depth = depth;
        }
    }

    private static final class ResolvedDirectory {
        @NonNull
        final Uri uri;
        @NonNull
        final String name;

        ResolvedDirectory(@NonNull Uri uri, @NonNull String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static final class NamedDocument {
        @NonNull
        final Uri uri;
        @NonNull
        final String name;
        final boolean directory;

        NamedDocument(@NonNull Uri uri, @NonNull String name, boolean directory) {
            this.uri = uri;
            this.name = name;
            this.directory = directory;
        }
    }
}
