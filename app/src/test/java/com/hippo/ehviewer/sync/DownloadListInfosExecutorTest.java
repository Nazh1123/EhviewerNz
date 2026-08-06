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

package com.hippo.ehviewer.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.dao.DownloadInfo;

import org.junit.Test;

public class DownloadListInfosExecutorTest {

    @Test
    public void englishTitleSearchIgnoresCase() {
        DownloadInfo info = new DownloadInfo();
        info.title = "Artist Collection Vol. 1";

        assertTrue(DownloadListInfosExecutor.matchesTitleIgnoreCase(
                info, "ARTIST COLLECTION"));
        assertTrue(DownloadListInfosExecutor.matchesTitleIgnoreCase(
                info, "collection VOL"));
    }

    @Test
    public void japaneseTitleSearchIgnoresLatinCase() {
        DownloadInfo info = new DownloadInfo();
        info.titleJpn = "作品 ABC Special";

        assertTrue(DownloadListInfosExecutor.matchesTitleIgnoreCase(
                info, "abc SPECIAL"));
    }

    @Test
    public void unrelatedAndNullTitlesDoNotMatch() {
        DownloadInfo info = new DownloadInfo();

        assertFalse(DownloadListInfosExecutor.matchesTitleIgnoreCase(info, "artist"));
        info.title = "Another Gallery";
        assertFalse(DownloadListInfosExecutor.matchesTitleIgnoreCase(info, "artist"));
    }
}
