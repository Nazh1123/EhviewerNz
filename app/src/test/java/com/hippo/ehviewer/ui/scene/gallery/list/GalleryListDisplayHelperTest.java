package com.hippo.ehviewer.ui.scene.gallery.list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;

public class GalleryListDisplayHelperTest {

    @Test
    public void formatCompactPosted_usesRequestedFormat() {
        assertEquals("26-08-13 14:30",
                GalleryListDisplayHelper.formatCompactPosted("2026-08-13 14:30"));
    }

    @Test
    public void resolveCensorship_readsSimpleTagsAndListFallback() {
        assertEquals("Mo", GalleryListDisplayHelper.resolveCensorship(
                new String[]{"other:mosaic censorship"}, null));
        assertEquals("Fu", GalleryListDisplayHelper.resolveCensorship(
                null, Collections.singletonList("other:full censorship")));
        assertEquals("Un", GalleryListDisplayHelper.resolveCensorship(
                new String[]{"other:uncensored"}, null));
        assertNull(GalleryListDisplayHelper.resolveCensorship(
                new String[]{"language:english"}, null));
    }

    @Test
    public void formatPageProgress_matchesDetailAndThumbnailForms() {
        assertEquals("12/167P",
                GalleryListDisplayHelper.formatPageProgress(11, 167, true));
        assertEquals("12/167",
                GalleryListDisplayHelper.formatPageProgress(11, 167, false));
        assertEquals("0/167",
                GalleryListDisplayHelper.formatPageProgress(0, 167, false));
    }
}
