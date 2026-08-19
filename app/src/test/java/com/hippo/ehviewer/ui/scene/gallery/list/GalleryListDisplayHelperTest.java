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
