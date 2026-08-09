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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.GalleryTags;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class DownloadQuickOrganizerTest {

    @Test
    public void artistTakesPriorityOverCosplayer() {
        GalleryInfo gallery = galleryWithKnownTags(
                "cosplayer:cos name", "artist:artist name");

        assertEquals("A: artist name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void cosplayerIsUsedWhenArtistIsAbsent() {
        GalleryInfo gallery = galleryWithKnownTags("cosplayer:cos name");

        assertEquals("C: cos name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void coserNamespaceAliasIsSupported() {
        GalleryInfo gallery = galleryWithKnownTags("coser:cos name");

        assertEquals("C: cos name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void groupIsUsedWhenArtistAndCosplayerAreAbsent() {
        GalleryInfo gallery = galleryWithKnownTags("group:group name");

        assertEquals("G: group name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void groupFromTagListIsSupportedCaseInsensitively() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.tgList = new ArrayList<>(Collections.singletonList("Group:group name"));

        assertEquals("G: group name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void cosplayerTakesPriorityOverGroup() {
        GalleryInfo gallery = galleryWithKnownTags(
                "group:group name", "cosplayer:cos name");

        assertEquals("C: cos name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void storedArtistTagsAreSupported() {
        GalleryInfo gallery = new GalleryInfo();
        GalleryTags tags = new GalleryTags(gallery.gid);
        tags.artist = "first artist,second artist";

        assertEquals("A: first artist",
                DownloadQuickOrganizer.resolveLabel(gallery, tags));
    }

    @Test
    public void storedGroupTagsAreSupported() {
        GalleryInfo gallery = new GalleryInfo();
        GalleryTags tags = new GalleryTags(gallery.gid);
        tags.group = "first group,second group";

        assertEquals("G: first group",
                DownloadQuickOrganizer.resolveLabel(gallery, tags));
    }

    @Test
    public void titleKeywordIsUsedForMiscWithoutQuotes() {
        GalleryInfo gallery = galleryWithKnownTags();
        gallery.title = "[misc name] Sample Gallery";

        assertEquals("M: misc name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void uploaderUsesUploaderPrefixWhenTitleHasNoKeyword() {
        GalleryInfo gallery = galleryWithKnownTags();
        gallery.title = "AI Generated Patreon";
        gallery.uploader = "uploader name";

        assertEquals("U: uploader name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void aiGeneratedWithoutTitleKeywordUsesUploaderPrefix() {
        GalleryInfo gallery = galleryWithKnownTags("other:ai generated");
        gallery.title = "120p";
        gallery.uploader = "uploader name";

        assertEquals("U: uploader name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void unusableTitleAndUploaderReturnNull() {
        GalleryInfo gallery = galleryWithKnownTags();
        gallery.title = "123p";
        gallery.uploader = " ";

        assertNull(DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void aiGeneratedTagUsesAiPrefix() {
        GalleryInfo gallery = galleryWithKnownTags("other:ai generated");
        gallery.title = "[creator] Sample Gallery";

        assertEquals("AI: creator",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void abbreviatedAiGeneratedTagUsesAiPrefix() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.tgList = new ArrayList<>(Collections.singletonList("o:ai generated"));
        gallery.title = "[creator] Sample Gallery";

        assertEquals("AI: creator",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void storedAiGeneratedTagUsesAiPrefix() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.title = "[creator] Sample Gallery";
        GalleryTags tags = new GalleryTags(gallery.gid);
        tags.other = "some tag,ai generated";

        assertEquals("AI: creator",
                DownloadQuickOrganizer.resolveLabel(gallery, tags));
    }

    @Test
    public void artistTakesPriorityOverAiGeneratedTag() {
        GalleryInfo gallery = galleryWithKnownTags(
                "other:ai generated", "artist:artist name");

        assertEquals("A: artist name",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void aiGeneratedTagRequiresExactValue() {
        GalleryInfo gallery = galleryWithKnownTags("other:ai generated variant");
        gallery.title = "[creator] Sample Gallery";

        assertEquals("M: creator",
                DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void unknownTagMetadataIsNotTreatedAsMisc() {
        GalleryInfo gallery = new GalleryInfo();
        gallery.title = "[unknown] Gallery";

        assertNull(DownloadQuickOrganizer.resolveLabel(gallery, null));
    }

    @Test
    public void annotatedLabelsAreEquivalentWithOrWithoutSpace() {
        assertEquals("A: artist name #first note",
                DownloadQuickOrganizer.findEquivalentLabel(
                        Collections.singletonList("A: artist name #first note"),
                        "A: artist name"));
        assertEquals("A: artist name#second note",
                DownloadQuickOrganizer.findEquivalentLabel(
                        Collections.singletonList("A: artist name#second note"),
                        "A: artist name"));
    }

    @Test
    public void exactLabelIsPreferredOverAnnotatedEquivalent() {
        assertEquals("A: artist name",
                DownloadQuickOrganizer.findEquivalentLabel(
                        Arrays.asList("A: artist name #note", "A: artist name"),
                        "A: artist name"));
    }

    @Test
    public void existingLabelIsReusedIgnoringCase() {
        assertEquals("KKi",
                DownloadQuickOrganizer.findEquivalentLabel(
                        Collections.singletonList("KKi"), "kki"));
    }

    @Test
    public void annotatedLabelIsReusedIgnoringCase() {
        assertEquals("A: KKi #note",
                DownloadQuickOrganizer.findEquivalentLabel(
                        Collections.singletonList("A: KKi #note"), "a: kki"));
    }

    private static GalleryInfo galleryWithKnownTags(String... tags) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.simpleTags = tags;
        return gallery;
    }
}
