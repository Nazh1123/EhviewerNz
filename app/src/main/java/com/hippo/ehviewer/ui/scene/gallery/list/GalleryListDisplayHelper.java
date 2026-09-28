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
    // Matplotlib viridis sampled at 1/32 intervals. Brightness is bounded for
    // contrast on the app's light and dark thumbnail cards; the dark palette
    // also limits saturation. Green stops (16-25) have 25% less saturation,
    // with a short transition on either side.
    private static final int[] VIRIDIS_LIGHT = {
            0xff440154, 0xff470d60, 0xff48186a, 0xff482374, 0xff472d7b, 0xff453781,
            0xff424086, 0xff3e4989, 0xff3b528b, 0xff375b8d, 0xff33638d, 0xff2f6b8e,
            0xff2c718c, 0xff2c7385, 0xff2d747e, 0xff2f7579, 0xff327572, 0xff30766e,
            0xff2f7669, 0xff307765, 0xff327760, 0xff37775a, 0xff3c7754, 0xff42774f,
            0xff477649, 0xff4d7542, 0xff517437, 0xff55732b, 0xff5b721f, 0xff627012,
            0xff6a6e0c, 0xff706c0d, 0xff756a11,
    };
    private static final int[] VIRIDIS_DARK = {
            0xffb09db4, 0xffb09db8, 0xffaf9dbc, 0xffac9dbf, 0xffaa9dc2, 0xffa59fc2,
            0xffa1a0c3, 0xff9ca2c2, 0xff97a3c1, 0xff91a5c0, 0xff8ba7be, 0xff85a8bc,
            0xff81a9b9, 0xff7facb6, 0xff7dadb3, 0xff7caeb0, 0xff7bafad, 0xff77b0a9,
            0xff72b2a6, 0xff6db3a1, 0xff67b59a, 0xff5eb78e, 0xff61bc87, 0xff6bc381,
            0xff79c97c, 0xff88cf75, 0xff90cf62, 0xff9ccb58, 0xffa7c84e, 0xffb2c545,
            0xffbcc244, 0xffc5c045, 0xffcbbe48,
    };


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

    static int ratingHighlightColor(float rating, boolean darkTheme) {
        float position = Math.max(0f, Math.min(1f, (rating - 0.2f) / 4.6f));
        int[] palette = darkTheme ? VIRIDIS_DARK : VIRIDIS_LIGHT;
        float scaled = position * (palette.length - 1);
        int index = (int) scaled;
        return index >= palette.length - 1
                ? palette[palette.length - 1]
                : blendColor(palette[index], palette[index + 1], scaled - index);
    }

    private static int blendColor(int from, int to, float fraction) {
        int red = Math.round(((from >> 16) & 0xff) * (1f - fraction)
                + ((to >> 16) & 0xff) * fraction);
        int green = Math.round(((from >> 8) & 0xff) * (1f - fraction)
                + ((to >> 8) & 0xff) * fraction);
        int blue = Math.round((from & 0xff) * (1f - fraction)
                + (to & 0xff) * fraction);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    static boolean isHighlightCensorship(@Nullable String censorship) {
        return "Un".equals(censorship) || "Nu".equals(censorship);
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
