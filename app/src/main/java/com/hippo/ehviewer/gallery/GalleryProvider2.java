/*
 * Copyright 2016 Hippo Seven
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.unifile.UniFile;

public abstract class GalleryProvider2 extends GalleryProvider {

    // With dot
    public static final String[] SUPPORT_IMAGE_EXTENSIONS = {
            ".jpg", // Joint Photographic Experts Group
            ".jpeg",
            ".png", // Portable Network Graphics
            ".gif", // Graphics Interchange Format
            ".webp"
    };

    public int getStartPage() {
        return 0;
    }

    public void putStartPage(int page) {}

    /**
     * @return without extension
     */
    @NonNull
    public abstract String getImageFilename(int index);

    public abstract boolean save(int index, @NonNull UniFile file);

    /**
     * @param filename without extension
     */
    @Nullable
    public abstract UniFile save(int index, @NonNull UniFile dir, @NonNull String filename);

    /** The destination and the actual action taken by this save. */
    public static final class SaveResult {
        @NonNull public final UniFile file;
        public final boolean overwritten;
        public final boolean skipped;

        private SaveResult(@NonNull UniFile file, boolean overwritten, boolean skipped) {
            this.file = file;
            this.overwritten = overwritten;
            this.skipped = skipped;
        }

        public SaveResult skipped() {
            return new SaveResult(file, overwritten, true);
        }

        /** Remove a partial new file after a failed copy, leaving existing files untouched. */
        public void deleteIfCreated() {
            if (overwritten) return;
            try {
                file.delete();
            } catch (RuntimeException ignored) {
                // Best effort; the original copy failure remains the reported error.
            }
        }
    }

    /** Resolve the exact filename before writing, so the result reflects the actual destination. */
    @Nullable
    public static SaveResult prepareSaveDestination(@NonNull UniFile dir,
                                                    @NonNull String filename) {
        UniFile.CreateFileResult result = dir.createFileWithStatus(filename);
        return result != null ? new SaveResult(result.file, !result.created, false) : null;
    }

    /** @param filename without extension */
    @Nullable
    public abstract SaveResult saveWithResult(int index, @NonNull UniFile dir,
                                              @NonNull String filename);
}
