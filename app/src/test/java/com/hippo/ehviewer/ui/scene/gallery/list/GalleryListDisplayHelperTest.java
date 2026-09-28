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

import org.junit.Test;

import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GalleryListDisplayHelperTest {

    @Test
    public void formatRatingAlwaysUsesTwoDecimalsAndDecimalPoint() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("4.32", GalleryListDisplayHelper.formatRating(4.32f));
            assertEquals("4.30", GalleryListDisplayHelper.formatRating(4.3f));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void formatRatingUsesPlaceholderForInvalidValue() {
        assertEquals("\u2014", GalleryListDisplayHelper.formatRating(-1f));
        assertEquals("\u2014", GalleryListDisplayHelper.formatRating(Float.NaN));
    }

    @Test
    public void ratingHighlightUsesViridisAndClampsAtEnds() {
        assertEquals(0xff440154, GalleryListDisplayHelper.ratingHighlightColor(0f, false));
        assertEquals(0xff440154, GalleryListDisplayHelper.ratingHighlightColor(0.2f, false));
        assertEquals(0xff327572, GalleryListDisplayHelper.ratingHighlightColor(2.5f, false));
        assertEquals(0xff756a11, GalleryListDisplayHelper.ratingHighlightColor(4.8f, false));
        assertEquals(0xff756a11, GalleryListDisplayHelper.ratingHighlightColor(5f, false));
        assertEquals(0xffb09db4, GalleryListDisplayHelper.ratingHighlightColor(0f, true));
        assertEquals(0xff7bafad, GalleryListDisplayHelper.ratingHighlightColor(2.5f, true));
        assertEquals(0xffcbbe48, GalleryListDisplayHelper.ratingHighlightColor(5f, true));
        int betweenSamples = GalleryListDisplayHelper.ratingHighlightColor(2.57f, false);
        assertTrue(betweenSamples != 0xff327572 && betweenSamples != 0xff30766e);
    }

    @Test
    public void onlyUncensoredAndNudityOnlyTypesAreHighlighted() {
        assertTrue(GalleryListDisplayHelper.isHighlightCensorship("Un"));
        assertTrue(GalleryListDisplayHelper.isHighlightCensorship("Nu"));
        assertFalse(GalleryListDisplayHelper.isHighlightCensorship("Mo"));
        assertFalse(GalleryListDisplayHelper.isHighlightCensorship("Cl"));
        assertFalse(GalleryListDisplayHelper.isHighlightCensorship(null));
    }

    @Test
    public void censorshipUsesPrimaryTypeBeforeCosplayFallback() {
        assertEquals("Un", GalleryListDisplayHelper.resolveCensorship(
                new String[]{"other:nudity only", "other:uncensored"},
                null,
                true));
    }

    @Test
    public void censorshipUsesCosplayNudityFallbacks() {
        assertEquals("Nu", GalleryListDisplayHelper.resolveCensorship(
                new String[]{"other:nudity only"},
                null,
                true));
        assertEquals("Cl", GalleryListDisplayHelper.resolveCensorship(
                null,
                Collections.singletonList("other:non-nude"),
                true));
        assertEquals("Nu", GalleryListDisplayHelper.resolveCensorship(
                new String[]{"other:non-nude", "other:nudity only"},
                null,
                true));
    }

    @Test
    public void censorshipIgnoresNudityFallbacksOutsideCosplay() {
        assertNull(GalleryListDisplayHelper.resolveCensorship(
                new String[]{"other:nudity only", "other:non-nude"},
                null,
                false));
    }

    @Test
    public void pageProgressHidesZeroReadingProgress() {
        assertEquals("123p", GalleryListDisplayHelper.formatPageProgress(0, 123, false));
        assertEquals("123P", GalleryListDisplayHelper.formatPageProgress(0, 123, true));
        assertEquals("2/123", GalleryListDisplayHelper.formatPageProgress(1, 123, false));
    }
}
