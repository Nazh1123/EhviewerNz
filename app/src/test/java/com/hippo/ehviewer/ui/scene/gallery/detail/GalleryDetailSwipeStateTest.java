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
    public void jumpRequiresCurrentVisibilityOrSeenAndNearBottom() {
        GalleryDetailSwipeState state = new GalleryDetailSwipeState();

        state.observeActivationView(false);
        assertFalse(state.hasSeenActivationView());
        assertFalse(state.canJumpToNewContent(true));

        state.observeActivationView(true);
        assertTrue(state.hasSeenActivationView());
        assertTrue(state.canJumpToNewContent(false));

        state.observeActivationView(false);
        assertFalse(state.canJumpToNewContent(false));
        assertTrue(state.canJumpToNewContent(true));

        state.resetActivationViewSeen();
        assertFalse(state.hasSeenActivationView());
        assertFalse(state.canJumpToNewContent(true));
    }

    @Test
    public void bottomRangeUsesRemainingScrollDistance() {
        assertFalse(GalleryDetailSwipeState.isWithinBottomRange(439, 500, 1000, 60f));
        assertTrue(GalleryDetailSwipeState.isWithinBottomRange(440, 500, 1000, 60f));
        assertTrue(GalleryDetailSwipeState.isWithinBottomRange(500, 500, 1000, 60f));
        assertTrue(GalleryDetailSwipeState.isWithinBottomRange(0, 500, 400, 60f));
    }
}
