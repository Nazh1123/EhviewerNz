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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.hippo.unifile.UniFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

public class DirGalleryProviderTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void listsOnlyDirectImagesInNaturalNameOrder() throws Exception {
        File directory = temporaryFolder.newFolder("gallery");
        new File(directory, "page10.jpg").createNewFile();
        new File(directory, "page2.PNG").createNewFile();
        new File(directory, "notes.txt").createNewFile();
        File childDirectory = new File(directory, "child");
        childDirectory.mkdir();
        new File(childDirectory, "page1.jpg").createNewFile();

        UniFile[] files = DirGalleryProvider.listAndSortImageFiles(UniFile.fromFile(directory));

        assertNotNull(files);
        assertArrayEquals(new String[]{"page2.PNG", "page10.jpg"},
                Arrays.stream(files).map(UniFile::getName).toArray(String[]::new));
    }

    @Test
    public void selectedImageDeterminesStartPage() throws Exception {
        UniFile[] files = new UniFile[]{
                UniFile.fromFile(temporaryFolder.newFile("page1.jpg")),
                UniFile.fromFile(temporaryFolder.newFile("page2.jpg")),
                UniFile.fromFile(temporaryFolder.newFile("page10.jpg"))
        };

        assertEquals(2, DirGalleryProvider.findStartPage(files, "page10.jpg"));
        assertEquals(0, DirGalleryProvider.findStartPage(files, "missing.jpg"));
    }
}
