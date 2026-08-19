/*
 * Copyright 2026
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

package com.hippo.ehviewer.ui.scene.gallery.list;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GalleryListDisplayHelper {

    private static final Pattern POSTED_PATTERN = Pattern.compile(
            "^\\d{2}(\\d{2})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2})$");

    private static final String MOSAIC_CENSORSHIP = "other:mosaic censorship";
    private static final String FULL_CENSORSHIP = "other:full censorship";
    private static final String UNCENSORED = "other:uncensored";
    private static final String NUDITY_ONLY = "other:nudity only";
    private static final String NON_NUDE = "other:non-nude";

    private GalleryListDisplayHelper() {
    }

    @NonNull
    static String formatCompactPosted(@Nullable String posted) {
        if (posted == null) {
            return "";
        }
        Matcher matcher = POSTED_PATTERN.matcher(posted);
        if (!matcher.matches()) {
            return posted;
        }
        return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3)
                + " " + matcher.group(4) + ":" + matcher.group(5);
    }

    @NonNull
    static String formatRating(float rating) {
        if (rating < 0f || Float.isNaN(rating) || Float.isInfinite(rating)) {
            return "\u2014";
        }
        return String.format(Locale.US, "%.2f", rating);
    }

    @Nullable
    static String resolveCensorship(@Nullable String[] simpleTags,
                                    @Nullable List<String> tagList,
                                    boolean isCosplay) {
        if (containsTag(simpleTags, tagList, MOSAIC_CENSORSHIP)) {
            return "Mo";
        }
        if (containsTag(simpleTags, tagList, FULL_CENSORSHIP)) {
            return "Fu";
        }
        if (containsTag(simpleTags, tagList, UNCENSORED)) {
            return "Un";
        }
        if (isCosplay) {
            if (containsTag(simpleTags, tagList, NUDITY_ONLY)) {
                return "Nu";
            }
            if (containsTag(simpleTags, tagList, NON_NUDE)) {
                return "Cl";
            }
        }
        return null;
    }

    @NonNull
    static String formatPageProgress(int startPage, int pages, boolean appendPageSuffix) {
        if (pages <= 0) {
            return appendPageSuffix ? "" : "—/—";
        }
        String suffix = appendPageSuffix ? "P" : "";
        if (startPage <= 0) {
            return pages + (appendPageSuffix ? "P" : "p");
        }
        return (startPage + 1) + "/" + pages + suffix;
    }

    private static boolean containsTag(@Nullable String[] simpleTags,
                                       @Nullable List<String> tagList,
                                       @NonNull String expected) {
        if (simpleTags != null) {
            for (String tag : simpleTags) {
                if (expected.equalsIgnoreCase(tag)) {
                    return true;
                }
            }
        }
        if (tagList != null) {
            for (String tag : tagList) {
                if (expected.equalsIgnoreCase(tag)) {
                    return true;
                }
            }
        }
        return false;
    }
}
