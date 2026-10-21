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

package com.hippo.ehviewer.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

public class EhCacheKeyFactory {
    private static final String SHEET_PREFIX = "preview:sheet:v1:";
    private static final Pattern COVER_FILE = Pattern.compile(
            "^([0-9a-f]{40}-[0-9]+-[0-9]+-[0-9]+-[a-z0-9]+)_([0-9]+)\\.(jpg|jpeg|png|webp)$");
    private static final Pattern SHEET_PATH = Pattern.compile(
            "^/(c[12m])/[^/]+/(\\d+-\\d+)\\.(jpg|jpeg|webp)$");

    /** H@H hosts and timed access tokens may change without changing the sprite sheet. */
    public static String getNormalPreviewKey(String imageUrl) {
        HttpUrl url = HttpUrl.parse(imageUrl);
        if (url != null && url.host().endsWith(".hath.network") && url.query() == null) {
            Matcher match = SHEET_PATH.matcher(url.encodedPath());
            if (match.matches()) {
                return SHEET_PREFIX + match.group(1) + ":" + match.group(2) + ":" + match.group(3);
            }
        }
        // Unknown URL formats retain their complete identity rather than risking a collision.
        return SHEET_PREFIX + "url:" + imageUrl;
    }

    public static boolean isNormalPreviewKey(String key) {
        return key != null && key.startsWith(SHEET_PREFIX);
    }

    public static String getThumbKey(long gid) {
        return "preview:large:" + gid + ":" + 0; // "thumb:" + gid;
    }

    /** Recognised content-addressed thumbnails may move hosts without changing pixels. */
    public static String coverIdentity(String url) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
        if (parsed != null && parsed.query() == null && !parsed.pathSegments().isEmpty()) {
            String file = parsed.pathSegments().get(parsed.pathSegments().size() - 1);
            if (COVER_FILE.matcher(file).matches()) return "thumb:" + file;
        }
        return url;
    }

    public static boolean needsCoverUpdate(String current, String next) {
        if (next == null || next.isEmpty()) return false;
        String oldIdentity = coverIdentity(current);
        String nextIdentity = coverIdentity(next);
        if (java.util.Objects.equals(oldIdentity, nextIdentity)) return false;
        if (oldIdentity != null && oldIdentity.startsWith("thumb:") && nextIdentity.startsWith("thumb:")) {
            Matcher oldFile = COVER_FILE.matcher(oldIdentity.substring(6));
            Matcher nextFile = COVER_FILE.matcher(nextIdentity.substring(6));
            if (oldFile.matches() && nextFile.matches() && oldFile.group(1).equals(nextFile.group(1))
                    && oldFile.group(3).equals(nextFile.group(3))) {
                try { return Long.parseLong(nextFile.group(2)) > Long.parseLong(oldFile.group(2)); }
                catch (NumberFormatException ignored) { /* Keep unfamiliar sizes distinct. */ }
            }
        }
        return true;
    }

    public static String getCoverKey(long gid, String url) {
        return "cover:v1:" + gid + ":" + coverIdentity(url);
    }

    public static String getNormalPreviewKey(long gid, int index) {
        return "preview:normal:" + gid + ":" + index;
    }

    public static String getLargePreviewKey(long gid, int index) {
        return "preview:large:" + gid + ":" + index;
    }

    public static String getLargePreviewSetKey(long gid, int index) {
        return "large_preview_set:" + gid + ":" + index;
    }

    public static String getImageKey(long gid, int index) {
        return "image:" + gid + ":" + index;
    }
}
