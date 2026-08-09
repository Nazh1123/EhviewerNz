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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DownloadLabelSearchQueryResolverTest {

    @Test
    public void artistLabelUsesQuotedShortNamespace() {
        assertEquals("a:\"arti nme$\"",
                DownloadLabelSearchQueryResolver.resolve("A: arti nme"));
    }

    @Test
    public void cosplayerLabelUsesQuotedShortNamespace() {
        assertEquals("cos:\"cos name$\"",
                DownloadLabelSearchQueryResolver.resolve("C: cos name"));
    }

    @Test
    public void groupLabelUsesQuotedShortNamespace() {
        assertEquals("g:\"group name$\"",
                DownloadLabelSearchQueryResolver.resolve("G: group name"));
    }

    @Test
    public void annotatedGroupLabelIgnoresComment() {
        assertEquals("g:\"group name$\"",
                DownloadLabelSearchQueryResolver.resolve("G: group name #note"));
    }

    @Test
    public void aiAndMiscLabelsUseRawValue() {
        assertEquals("txt tet",
                DownloadLabelSearchQueryResolver.resolve("AI: txt tet"));
        assertEquals("txt tet",
                DownloadLabelSearchQueryResolver.resolve("M: txt tet"));
    }

    @Test
    public void uploaderLabelUsesUploadNamespace() {
        assertEquals("uploader:\"uplod er$\"",
                DownloadLabelSearchQueryResolver.resolve("U: uplod er"));
    }

    @Test
    public void prefixesAreCaseInsensitive() {
        assertEquals("a:\"artist$\"",
                DownloadLabelSearchQueryResolver.resolve("a: artist"));
        assertEquals("g:\"group$\"",
                DownloadLabelSearchQueryResolver.resolve("g: group"));
    }

    @Test
    public void unsupportedAndEmptyLabelsHaveNoQuery() {
        assertNull(DownloadLabelSearchQueryResolver.resolve(null));
        assertNull(DownloadLabelSearchQueryResolver.resolve("Default"));
        assertNull(DownloadLabelSearchQueryResolver.resolve("AI:"));
    }
}
