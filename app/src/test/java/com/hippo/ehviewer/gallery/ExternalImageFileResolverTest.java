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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExternalImageFileResolverTest {

    @Test
    public void recognizesSupportedImageExtensionsCaseInsensitively() {
        assertTrue(ExternalImageFileResolver.isSupportedImageName("cover.JPG"));
        assertTrue(ExternalImageFileResolver.isSupportedImageName("animated.WeBp"));
        assertFalse(ExternalImageFileResolver.isSupportedImageName("archive.zip"));
        assertFalse(ExternalImageFileResolver.isSupportedImageName(null));
    }
}
