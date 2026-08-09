/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.GalleryTitleKeywordExtractor;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.GalleryTags;

import java.util.List;

/** Resolves automatic download labels and their comment-insensitive identities. */
public final class DownloadQuickOrganizer {

    private static final String ARTIST_NAMESPACE = "artist";
    private static final String COSPLAYER_NAMESPACE = "cosplayer";
    private static final String COSER_NAMESPACE = "coser";
    private static final String GROUP_NAMESPACE = "group";
    private static final String OTHER_NAMESPACE = "other";
    private static final String OTHER_SHORT_NAMESPACE = "o";
    private static final String AI_GENERATED_TAG = "ai generated";

    private DownloadQuickOrganizer() {
    }

    /**
     * Returns {@code null} when the gallery has no trustworthy tag metadata or no usable fallback.
     */
    @Nullable
    public static String resolveLabel(@NonNull GalleryInfo gallery,
                                      @Nullable GalleryTags storedTags) {
        if (!hasKnownTags(gallery, storedTags)) {
            return null;
        }

        String artist = findNamespacedTag(gallery.simpleTags, ARTIST_NAMESPACE);
        if (artist == null) {
            artist = findNamespacedTag(gallery.tgList, ARTIST_NAMESPACE);
        }
        if (artist == null && storedTags != null) {
            artist = firstStoredTag(storedTags.artist);
        }
        if (artist != null) {
            return "A: " + artist;
        }

        String cosplayer = findNamespacedTag(gallery.simpleTags, COSPLAYER_NAMESPACE);
        if (cosplayer == null) {
            cosplayer = findNamespacedTag(gallery.simpleTags, COSER_NAMESPACE);
        }
        if (cosplayer == null) {
            cosplayer = findNamespacedTag(gallery.tgList, COSPLAYER_NAMESPACE);
        }
        if (cosplayer == null) {
            cosplayer = findNamespacedTag(gallery.tgList, COSER_NAMESPACE);
        }
        if (cosplayer == null && storedTags != null) {
            cosplayer = firstStoredTag(storedTags.cosplayer);
        }
        if (cosplayer != null) {
            return "C: " + cosplayer;
        }

        String group = findNamespacedTag(gallery.simpleTags, GROUP_NAMESPACE);
        if (group == null) {
            group = findNamespacedTag(gallery.tgList, GROUP_NAMESPACE);
        }
        if (group == null && storedTags != null) {
            group = firstStoredTag(storedTags.group);
        }
        if (group != null) {
            return "G: " + group;
        }

        boolean aiGenerated = hasNamespacedTagValue(
                gallery.simpleTags, OTHER_NAMESPACE, AI_GENERATED_TAG)
                || hasNamespacedTagValue(
                        gallery.simpleTags, OTHER_SHORT_NAMESPACE, AI_GENERATED_TAG)
                || hasNamespacedTagValue(
                        gallery.tgList, OTHER_NAMESPACE, AI_GENERATED_TAG)
                || hasNamespacedTagValue(
                        gallery.tgList, OTHER_SHORT_NAMESPACE, AI_GENERATED_TAG)
                || storedTags != null
                        && containsStoredTag(storedTags.other, AI_GENERATED_TAG);

        String keyword = GalleryTitleKeywordExtractor.extractArtistKeyword(gallery.title);
        if (keyword != null && !keyword.trim().isEmpty()) {
            return (aiGenerated ? "AI: " : "M: ") + keyword.trim();
        }
        if (gallery.uploader == null || gallery.uploader.trim().isEmpty()) {
            return null;
        }
        return "U: " + gallery.uploader.trim();
    }

    public static boolean hasKnownTags(@NonNull GalleryInfo gallery,
                                       @Nullable GalleryTags storedTags) {
        return gallery.simpleTags != null
                || (gallery.tgList != null && !gallery.tgList.isEmpty())
                || storedTags != null;
    }

    /**
     * Finds the existing concrete label for a desired label. Text from the first {@code #}
     * onward is treated as a comment, with or without a preceding space.
     */
    @Nullable
    public static String findEquivalentLabel(@NonNull List<String> existingLabels,
                                             @NonNull String desiredLabel) {
        String desiredIdentity = normalizeLabelIdentity(desiredLabel);
        if (desiredIdentity == null) {
            return null;
        }

        String equivalent = null;
        for (String existingLabel : existingLabels) {
            if (existingLabel == null) {
                continue;
            }
            if (desiredLabel.equalsIgnoreCase(existingLabel.trim())) {
                return existingLabel;
            }
            if (equivalent == null
                    && desiredIdentity.equalsIgnoreCase(
                            normalizeLabelIdentity(existingLabel))) {
                equivalent = existingLabel;
            }
        }
        return equivalent;
    }

    @Nullable
    static String normalizeLabelIdentity(@Nullable String label) {
        if (label == null) {
            return null;
        }
        int commentStart = label.indexOf('#');
        String identity = (commentStart >= 0 ? label.substring(0, commentStart) : label).trim();
        return identity.isEmpty() ? null : identity;
    }

    @Nullable
    private static String findNamespacedTag(@Nullable String[] tags, String namespace) {
        if (tags == null) {
            return null;
        }
        for (String tag : tags) {
            String value = getTagValue(tag, namespace);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static String findNamespacedTag(@Nullable List<String> tags, String namespace) {
        if (tags == null) {
            return null;
        }
        for (String tag : tags) {
            String value = getTagValue(tag, namespace);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasNamespacedTagValue(@Nullable String[] tags,
                                                  String namespace,
                                                  String expectedValue) {
        if (tags == null) {
            return false;
        }
        for (String tag : tags) {
            String value = getTagValue(tag, namespace);
            if (expectedValue.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNamespacedTagValue(@Nullable List<String> tags,
                                                  String namespace,
                                                  String expectedValue) {
        if (tags == null) {
            return false;
        }
        for (String tag : tags) {
            String value = getTagValue(tag, namespace);
            if (expectedValue.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String getTagValue(@Nullable String tag, String namespace) {
        if (tag == null) {
            return null;
        }
        int separator = tag.indexOf(':');
        if (separator <= 0 || !namespace.equalsIgnoreCase(tag.substring(0, separator).trim())) {
            return null;
        }
        String value = tag.substring(separator + 1).trim();
        return value.isEmpty() ? null : value;
    }

    @Nullable
    private static String firstStoredTag(@Nullable String tags) {
        if (tags == null) {
            return null;
        }
        int separator = tags.indexOf(',');
        String value = (separator >= 0 ? tags.substring(0, separator) : tags).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean containsStoredTag(@Nullable String tags, String expectedValue) {
        if (tags == null) {
            return false;
        }
        for (String tag : tags.split(",")) {
            if (expectedValue.equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }
}
