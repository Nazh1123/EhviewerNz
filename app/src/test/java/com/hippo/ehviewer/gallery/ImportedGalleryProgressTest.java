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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.dao.DownloadInfo;

import org.junit.Test;

public class ImportedGalleryProgressTest {

    @Test
    public void roundTripsAndClampsReadingPosition() {
        ImportedGalleryProgress.Entry entry = ImportedGalleryProgress.decode(
                ImportedGalleryProgress.encode(25, 20));

        assertEquals(19, entry.page);
        assertEquals(20, entry.pages);
    }

    @Test
    public void preservesPageWhenTotalIsNotKnownYet() {
        ImportedGalleryProgress.Entry entry = ImportedGalleryProgress.decode(
                ImportedGalleryProgress.encode(12, 0));

        assertEquals(12, entry.page);
        assertEquals(0, entry.pages);
    }

    @Test
    public void rejectsMalformedOrUnsupportedRecords() {
        assertNull(ImportedGalleryProgress.decode(null));
        assertNull(ImportedGalleryProgress.decode("2|1|10"));
        assertNull(ImportedGalleryProgress.decode("1|bad|10"));
    }

    @Test
    public void recognizesFolderAndArchiveImports() {
        DownloadInfo folder = new DownloadInfo();
        folder.archiveUri = LocalFolderGallerySource.create(
                "content://documents/tree/pictures", "Book").encode();
        DownloadInfo archive = new DownloadInfo();
        archive.archiveUri = "content://documents/archive/book.cbz";
        DownloadInfo regular = new DownloadInfo();

        assertTrue(ImportedGalleryProgress.isImportedGallery(folder));
        assertTrue(ImportedGalleryProgress.isImportedGallery(archive));
        assertFalse(ImportedGalleryProgress.isImportedGallery(regular));
    }
}
