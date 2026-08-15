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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class LocalFolderGallerySourceTest {

    @Test
    public void roundTripsTreeUriAndRelativePath() {
        LocalFolderGallerySource source = LocalFolderGallerySource.create(
                "content://provider/tree/primary%3APictures", "/Book 01/Part A/");

        LocalFolderGallerySource decoded = LocalFolderGallerySource.parse(source.encode());

        assertNotNull(decoded);
        assertEquals(source.treeUri, decoded.treeUri);
        assertEquals("Book 01/Part A", decoded.relativePath);
    }

    @Test
    public void createsStableNegativeIdsPerGalleryDirectory() {
        LocalFolderGallerySource first = LocalFolderGallerySource.create("content://tree", "A");
        LocalFolderGallerySource same = LocalFolderGallerySource.create("content://tree", "A");
        LocalFolderGallerySource second = LocalFolderGallerySource.create("content://tree", "B");

        assertTrue(first.stableGalleryId() < 0);
        assertEquals(first.stableGalleryId(), same.stableGalleryId());
        assertNotEquals(first.stableGalleryId(), second.stableGalleryId());
    }

    @Test
    public void rejectsParentTraversal() {
        try {
            LocalFolderGallerySource.create("content://tree", "../Pictures");
            fail("Expected invalid relative path to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
