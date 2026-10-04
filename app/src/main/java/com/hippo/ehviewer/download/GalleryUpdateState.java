package com.hippo.ehviewer.download;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;

/** Download completion is not update completion while cleanup still has a persisted plan. */
public final class GalleryUpdateState {
    private GalleryUpdateState() {}

    public static int resolve(@Nullable Integer reported, @Nullable DownloadInfo info,
                              boolean hasPlan) {
        if (reported != null) {
            if (reported == GalleryUpdateManager.UPDATE_STATE_UPDATED && hasPlan) {
                return GalleryUpdateManager.UPDATE_STATE_UPDATING;
            }
            return reported;
        }
        if (info == null) return GalleryUpdateManager.UPDATE_STATE_FAILED;
        return switch (info.state) {
            case DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD, DownloadInfo.STATE_UPDATE ->
                    GalleryUpdateManager.UPDATE_STATE_UPDATING;
            case DownloadInfo.STATE_FINISH -> hasPlan
                    ? GalleryUpdateManager.UPDATE_STATE_FAILED
                    : GalleryUpdateManager.UPDATE_STATE_UPDATED;
            default -> GalleryUpdateManager.UPDATE_STATE_FAILED;
        };
    }
}
