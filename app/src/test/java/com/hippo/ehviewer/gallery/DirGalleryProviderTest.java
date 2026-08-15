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

package com.hippo.ehviewer.gallery;

import static org.junit.Assert.assertEquals;

import com.hippo.unifile.UniFile;

import org.junit.Test;

import java.io.File;

public class DirGalleryProviderTest {

    @Test
    public void findsExactHistoryFile() {
        assertEquals(2, findPage("4.jpg"));
    }

    @Test
    public void fallsBackToPreviousNaturallySortedFile() {
        assertEquals(1, findPage("3.jpg"));
        assertEquals(2, findPage("9.jpg"));
    }

    @Test
    public void returnsNoPageWhenHistoryPrecedesFirstFile() {
        assertEquals(-1, findPage("0.jpg"));
    }

    private static int findPage(String historyFilename) {
        UniFile[] files = {
                UniFile.fromFile(new File("1.jpg")),
                UniFile.fromFile(new File("2.jpg")),
                UniFile.fromFile(new File("4.jpg")),
                UniFile.fromFile(new File("10.jpg"))
        };
        return DirGalleryProvider.findPageAtOrBeforeFilename(files, historyFilename);
    }
}
