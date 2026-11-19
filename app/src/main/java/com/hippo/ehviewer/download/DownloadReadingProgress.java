package com.hippo.ehviewer.download;

import androidx.annotation.Nullable;
import com.hippo.ehviewer.spider.SpiderInfo;

/** Display-only progress. Spider metadata always retains original gallery page numbers. */
public record DownloadReadingProgress(int current, int total) {
    @Nullable public static DownloadReadingProgress resolve(long gid, @Nullable SpiderInfo fullGallery) {
        GalleryDownloadRangeStore.Entry range = GalleryDownloadRangeStore.peek(gid);
        if (range != null) {
            int position = GalleryPageRange.positionForSourcePage(range.readingPage(), range.range().pages());
            return new DownloadReadingProgress(Math.max(0, position) + 1, range.range().size());
        }
        return fullGallery != null && fullGallery.pages > 0
                ? new DownloadReadingProgress(Math.max(0, Math.min(fullGallery.startPage, fullGallery.pages - 1)) + 1,
                        fullGallery.pages) : null;
    }
}
