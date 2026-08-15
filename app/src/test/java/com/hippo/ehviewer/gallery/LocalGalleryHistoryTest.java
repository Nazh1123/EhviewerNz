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
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocalGalleryHistoryTest {

    @Test
    public void encodesAndDecodesFilenameAndTimestamp() {
        LocalGalleryHistory.Entry entry =
                LocalGalleryHistory.decode(
                        "/pictures/a", LocalGalleryHistory.encode("002.jpg", 20L));
        assertNotNull(entry);
        assertEquals("/pictures/a", entry.directory);
        assertEquals("002.jpg", entry.filename);
        assertEquals(20L, entry.updatedAt);
    }

    @Test
    public void keepsTwoHundredMostRecentlyUpdatedDirectories() {
        assertEquals(200, LocalGalleryHistory.MAX_ENTRIES);
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < LocalGalleryHistory.MAX_ENTRIES; i++) {
            entries.put(
                    LocalGalleryHistory.key("/pictures/" + i),
                    LocalGalleryHistory.encode(String.format("%03d.jpg", i), i));
        }
        entries.put(
                LocalGalleryHistory.key("/pictures/0"),
                LocalGalleryHistory.encode("resume.jpg", 1000L));

        List<String> removals = LocalGalleryHistory.keysToRemove(
                entries, LocalGalleryHistory.key("/pictures/200"));

        assertEquals(1, removals.size());
        assertEquals(LocalGalleryHistory.key("/pictures/1"), removals.get(0));
    }
}
