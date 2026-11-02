package com.hippo.ehviewer.client.data;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Freshness and visible-body comparisons do not touch storage or decoded images. */
public final class GalleryDetailState {
    private GalleryDetailState() {}
    public static final long FRESH_MILLIS = TimeUnit.MINUTES.toMillis(3);
    public static boolean isFresh(GalleryDetail detail, long now) {
        long age = now - detail.fetchedAtElapsed;
        return detail.fetchedAtElapsed >= 0 && age >= 0 && age < FRESH_MILLIS;
    }
    private static boolean sameGallery(GalleryDetail a, GalleryDetail b) {
        return a != null && b != null && a.gid == b.gid && Objects.equals(a.token, b.token);
    }
    public static boolean sameComments(GalleryDetail a, GalleryDetail b) {
        if (!sameGallery(a, b) || a.comments == null || b.comments == null) return false;
        GalleryComment[] left = a.comments.comments, right = b.comments.comments;
        if (left == null || right == null || left.length != right.length) return false;
        for (int i = 0; i < Math.min(2, left.length); i++) {
            GalleryComment x = left[i], y = right[i];
            if (x.id != y.id || x.time != y.time || !Objects.equals(x.user, y.user)
                    || !Objects.equals(x.comment, y.comment)) return false;
        }
        return true;
    }
    public static boolean samePreviews(GalleryDetail a, GalleryDetail b) {
        if (!sameGallery(a, b) || a.previewPages != b.previewPages) return false;
        PreviewSet left = a.previewSet, right = b.previewSet;
        if (left == null || right == null || left.getClass() != right.getClass() || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            GalleryPreview x = left.getGalleryPreview(a.gid, i), y = right.getGalleryPreview(b.gid, i);
            if (x.position != y.position || x.offsetX != y.offsetX || x.offsetY != y.offsetY
                    || x.clipWidth != y.clipWidth || x.clipHeight != y.clipHeight
                    || !Objects.equals(x.imageUrl, y.imageUrl) || !Objects.equals(x.pageUrl, y.pageUrl)) return false;
        }
        return true;
    }
}
