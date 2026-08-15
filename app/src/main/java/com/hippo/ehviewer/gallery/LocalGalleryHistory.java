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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Stores the most recently viewed image for external local-gallery directories. */
public final class LocalGalleryHistory {

    static final String PREFERENCES_NAME = "local_gallery_history";
    static final String ENTRY_PREFIX = "directory:";
    public static final int MAX_ENTRIES = 200;

    private LocalGalleryHistory() {}

    @Nullable
    public static synchronized Entry get(@NonNull Context context, @NonNull String directory) {
        String value = preferences(context).getString(key(directory), null);
        return decode(directory, value);
    }

    public static synchronized void put(
            @NonNull Context context, @NonNull String directory, @NonNull String filename) {
        put(context, directory, filename, System.currentTimeMillis());
    }

    static synchronized void put(
            @NonNull Context context,
            @NonNull String directory,
            @NonNull String filename,
            long updatedAt) {
        if (directory.isEmpty() || filename.isEmpty()) {
            return;
        }

        SharedPreferences preferences = preferences(context);
        String entryKey = key(directory);
        SharedPreferences.Editor editor = preferences.edit();
        Map<String, ?> all = preferences.getAll();

        for (String keyToRemove : keysToRemove(all, entryKey)) {
            editor.remove(keyToRemove);
        }

        editor.putString(entryKey, encode(filename, updatedAt)).apply();
    }

    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    static String key(@NonNull String directory) {
        return ENTRY_PREFIX + directory;
    }

    static String encode(@NonNull String filename, long updatedAt) {
        return updatedAt + "\n" + filename;
    }

    @Nullable
    static Entry decode(@NonNull String directory, @Nullable String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf('\n');
        if (separator <= 0 || separator == value.length() - 1) {
            return null;
        }
        try {
            long updatedAt = Long.parseLong(value.substring(0, separator));
            return new Entry(directory, value.substring(separator + 1), updatedAt);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @NonNull
    static List<String> keysToRemove(
            @NonNull Map<String, ?> all, @NonNull String incomingKey) {
        List<String> keys = new ArrayList<>();
        if (all.containsKey(incomingKey)) {
            return keys;
        }
        List<StoredEntry> entries = collectEntries(all);
        int removeCount = entries.size() - MAX_ENTRIES + 1;
        if (removeCount <= 0) {
            return keys;
        }
        Collections.sort(entries,
                (left, right) -> Long.compare(left.updatedAt, right.updatedAt));
        for (int i = 0; i < removeCount; i++) {
            keys.add(entries.get(i).key);
        }
        return keys;
    }

    @NonNull
    private static List<StoredEntry> collectEntries(@NonNull Map<String, ?> all) {
        List<StoredEntry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> item : all.entrySet()) {
            String itemKey = item.getKey();
            Object itemValue = item.getValue();
            if (!itemKey.startsWith(ENTRY_PREFIX) || !(itemValue instanceof String)) {
                continue;
            }
            Entry entry = decode(itemKey.substring(ENTRY_PREFIX.length()), (String) itemValue);
            if (entry != null) {
                entries.add(new StoredEntry(itemKey, entry.updatedAt));
            }
        }
        return entries;
    }

    public static final class Entry {
        @NonNull
        public final String directory;
        @NonNull
        public final String filename;
        public final long updatedAt;

        private Entry(
                @NonNull String directory, @NonNull String filename, long updatedAt) {
            this.directory = directory;
            this.filename = filename;
            this.updatedAt = updatedAt;
        }
    }

    private static final class StoredEntry {
        @NonNull
        final String key;
        final long updatedAt;

        StoredEntry(@NonNull String key, long updatedAt) {
            this.key = key;
            this.updatedAt = updatedAt;
        }
    }
}
