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

package com.hippo.ehviewer.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Converts quick-organizer download labels into gallery search queries. */
public final class DownloadLabelSearchQueryResolver {

    private DownloadLabelSearchQueryResolver() {
    }

    @Nullable
    public static String resolve(@Nullable String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            return null;
        }
        String prefix = trimmed.substring(0, separator).trim().toUpperCase(Locale.ROOT);
        String value = trimmed.substring(separator + 1).trim();
        if (value.isEmpty()) {
            return null;
        }
        switch (prefix) {
            case "A":
                return quote("a", value);
            case "C":
                return quote("c", value);
            case "AI":
            case "M":
                return value;
            case "U":
                return quote("upload", value);
            default:
                return null;
        }
    }

    @NonNull
    private static String quote(@NonNull String namespace, @NonNull String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return namespace + ":\"" + escaped + "\"";
    }
}
