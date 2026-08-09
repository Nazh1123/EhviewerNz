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

package com.hippo.ehviewer.ui.scene.gallery.detail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GalleryDetailSwipeStateTest {

    @Test
    public void remembersActivationViewAfterItLeavesViewport() {
        GalleryDetailSwipeState state = new GalleryDetailSwipeState();

        state.observeActivationView(false);
        assertFalse(state.hasSeenActivationView());

        state.observeActivationView(true);
        state.observeActivationView(false);
        assertTrue(state.hasSeenActivationView());

        state.resetActivationViewSeen();
        assertFalse(state.hasSeenActivationView());
    }

    @Test
    public void bottomRegionIncludesOnlyLastRequestedPixels() {
        assertFalse(GalleryDetailSwipeState.isInBottomRegion(439.9f, 500, 60f));
        assertTrue(GalleryDetailSwipeState.isInBottomRegion(440f, 500, 60f));
        assertTrue(GalleryDetailSwipeState.isInBottomRegion(500f, 500, 60f));
        assertFalse(GalleryDetailSwipeState.isInBottomRegion(500.1f, 500, 60f));
    }
}
