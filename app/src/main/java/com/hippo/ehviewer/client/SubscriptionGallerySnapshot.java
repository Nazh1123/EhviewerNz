package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import java.util.ArrayList;
import java.util.List;

/** List screens mutate layout and favorites; cached results must not share those objects. */
public final class SubscriptionGallerySnapshot {
    private SubscriptionGallerySnapshot() {}
    public static List<GalleryInfo> copy(List<GalleryInfo> galleries) {
        List<GalleryInfo> result = new ArrayList<>(galleries.size());
        for (GalleryInfo from : galleries) {
            GalleryInfo to = new GalleryInfo();
            SubscriptionMetadataCache.copyMetadata(from, to);
            to.gid = from.gid; to.token = from.token; to.rated = from.rated;
            to.thumbWidth = from.thumbWidth; to.thumbHeight = from.thumbHeight;
            to.spanSize = from.spanSize; to.spanIndex = from.spanIndex; to.spanGroupIndex = from.spanGroupIndex;
            to.favoriteSlot = from.favoriteSlot; to.favoriteName = from.favoriteName;
            to.tgList = from.tgList == null ? null : new ArrayList<>(from.tgList);
            result.add(to);
        }
        return result;
    }
}
