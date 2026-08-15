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

import com.hippo.ehviewer.dao.DownloadLabel;

import org.junit.Test;

import java.util.List;

public class DownloadManagerLabelOrderTest {

    @Test
    public void insertsAfterLeadingUnderscoreLabels() {
        assertEquals(2, position("_pinned", "_system", "regular", "another"));
    }

    @Test
    public void insertsBeforeFirstRegularLabel() {
        assertEquals(0, position("regular", "_later"));
    }

    @Test
    public void insertsAtEndWhenAllLabelsStartWithUnderscore() {
        assertEquals(2, position("_first", "_second"));
    }

    private static int position(String... names) {
        List<DownloadLabel> labels = new java.util.ArrayList<>();
        for (String name : names) {
            labels.add(new DownloadLabel(null, name, 0L));
        }
        return DownloadManager.findLocalFolderImportLabelPosition(labels);
    }
}
