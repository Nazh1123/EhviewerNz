package com.hippo.ehviewer.ui.scene.gallery.list;

import android.widget.ImageView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;

/** Shared badge rendering for list, thumbnail and information-bar layouts. */
final class GalleryVersionBadge {
    static final Object PAYLOAD = new Object();

    private GalleryVersionBadge() {}

    static boolean hasUpdateRecord(GalleryUpdateRecordStore store, long gid) {
        GalleryUpdateRecordStore.ActionSnapshot snapshot = store.peekActionSnapshot(gid);
        return snapshot != null && snapshot.summary() != null;
    }

    static void bind(ImageView badge, boolean updateAvailable, boolean updated,
                     int downloadedDrawableRes) {
        badge.setImageResource(updateAvailable
                ? R.drawable.v_updatable_x24 : downloadedDrawableRes);
        badge.setRotation(updated ? 180f : 0f);
        badge.setContentDescription(badge.getResources().getString(updated
                ? R.string.gallery_updated : updateAvailable
                ? R.string.gallery_update_available : R.string.download_state_downloaded));
    }
}
