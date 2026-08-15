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
import android.content.SharedPreferences;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderInfo;

/** Durable reading position and page count for imported folders and archives. */
public final class ImportedGalleryProgress {

    private static final String PREFERENCES_NAME = "imported_gallery_progress";
    private static final String VERSION_PREFIX = "1|";
    private static final int MAX_PAGES = 100_000;

    private ImportedGalleryProgress() {}

    public static boolean isImportedGallery(@Nullable DownloadInfo info) {
        if (info == null) {
            return false;
        }
        return LocalFolderGallerySource.isLocalFolderGallery(info.archiveUri)
                || info.archiveUri != null && info.archiveUri.startsWith("content://");
    }

    public static synchronized int getStartPage(@NonNull Context context, long galleryId) {
        Entry entry = get(context, galleryId);
        return entry == null ? 0 : entry.page;
    }

    @Nullable
    public static synchronized Entry get(@NonNull Context context, long galleryId) {
        return decode(preferences(context).getString(Long.toString(galleryId), null));
    }

    public static synchronized void save(
            @NonNull Context context, long galleryId, int page, int pages) {
        Entry current = get(context, galleryId);
        int effectivePages = sanitizePages(pages);
        if (effectivePages <= 0 && current != null) {
            effectivePages = current.pages;
        }
        int effectivePage = sanitizePage(page, effectivePages);
        if (current != null
                && current.page == effectivePage
                && current.pages == effectivePages) {
            return;
        }
        preferences(context).edit()
                .putString(Long.toString(galleryId), encode(effectivePage, effectivePages))
                .apply();
    }

    public static synchronized void updatePageCount(
            @NonNull Context context, long galleryId, int pages) {
        Entry current = get(context, galleryId);
        save(context, galleryId, current == null ? 0 : current.page, pages);
    }

    public static synchronized void reset(@NonNull Context context, long galleryId) {
        Entry current = get(context, galleryId);
        if (current != null) {
            save(context, galleryId, 0, current.pages);
        }
    }

    public static synchronized void remove(@NonNull Context context, long galleryId) {
        preferences(context).edit().remove(Long.toString(galleryId)).apply();
    }

    /** Builds the same lightweight model already consumed by the download list UI. */
    @Nullable
    public static SpiderInfo toSpiderInfo(
            @NonNull Context context, @NonNull DownloadInfo info) {
        Entry entry = get(context, info.gid);
        int pages = entry == null ? 0 : entry.pages;
        if (pages <= 0) {
            pages = sanitizePages(Math.max(info.total, info.pages));
        }
        if (pages <= 0) {
            return null;
        }
        SpiderInfo spiderInfo = new SpiderInfo();
        spiderInfo.gid = info.gid;
        spiderInfo.token = info.token == null ? "" : info.token;
        spiderInfo.pages = pages;
        spiderInfo.startPage = sanitizePage(entry == null ? 0 : entry.page, pages);
        spiderInfo.pTokenMap = new SparseArray<>();
        return spiderInfo;
    }

    @NonNull
    static String encode(int page, int pages) {
        int safePages = sanitizePages(pages);
        return VERSION_PREFIX + sanitizePage(page, safePages) + '|' + safePages;
    }

    @Nullable
    static Entry decode(@Nullable String encoded) {
        if (encoded == null || !encoded.startsWith(VERSION_PREFIX)) {
            return null;
        }
        int separator = encoded.indexOf('|', VERSION_PREFIX.length());
        if (separator < 0) {
            return null;
        }
        try {
            int page = Integer.parseInt(
                    encoded.substring(VERSION_PREFIX.length(), separator));
            int pages = sanitizePages(Integer.parseInt(encoded.substring(separator + 1)));
            return new Entry(sanitizePage(page, pages), pages);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int sanitizePages(int pages) {
        return pages > 0 && pages <= MAX_PAGES ? pages : 0;
    }

    private static int sanitizePage(int page, int pages) {
        int nonNegativePage = Math.max(0, page);
        return pages > 0 ? Math.min(nonNegativePage, pages - 1) : nonNegativePage;
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static final class Entry {
        public final int page;
        public final int pages;

        Entry(int page, int pages) {
            this.page = page;
            this.pages = pages;
        }
    }
}
