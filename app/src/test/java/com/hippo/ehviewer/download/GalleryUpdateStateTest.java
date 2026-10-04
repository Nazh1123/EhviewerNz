package com.hippo.ehviewer.download;

import com.hippo.ehviewer.dao.DownloadInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class GalleryUpdateStateTest {
    private DownloadInfo finished() {
        DownloadInfo info = new DownloadInfo(); info.state = DownloadInfo.STATE_FINISH; return info;
    }
    @Test public void failedCleanupIsRetryableEvenWhenDownloadFinished() {
        assertEquals(GalleryUpdateManager.UPDATE_STATE_FAILED, GalleryUpdateState.resolve(
                GalleryUpdateManager.UPDATE_STATE_FAILED, finished(), true));
    }
    @Test public void restartedPendingCleanupCannotBeShownAsUpdated() {
        assertEquals(GalleryUpdateManager.UPDATE_STATE_FAILED, GalleryUpdateState.resolve(null, finished(), true));
        assertEquals(GalleryUpdateManager.UPDATE_STATE_UPDATING, GalleryUpdateState.resolve(
                GalleryUpdateManager.UPDATE_STATE_UPDATED, finished(), true));
    }
    @Test public void completedCleanupIsUpdated() {
        assertEquals(GalleryUpdateManager.UPDATE_STATE_UPDATED, GalleryUpdateState.resolve(null, finished(), false));
    }
}
