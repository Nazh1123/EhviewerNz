package com.hippo.ehviewer.client;

import android.app.Application;
import android.os.Parcel;
import com.hippo.ehviewer.client.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class GalleryDetailStateTest {
    @Test public void freshnessExpiresAtThreeMinutesAndUnknownParcelAgeRequiresRevalidation() {
        GalleryDetail detail = new GalleryDetail();
        assertFalse(GalleryDetailState.isFresh(detail, 10));
        detail.fetchedAtElapsed = 100;
        assertTrue(GalleryDetailState.isFresh(detail, 180099));
        assertFalse(GalleryDetailState.isFresh(detail, 180100));
        assertFalse(GalleryDetailState.isFresh(detail, 99));
        Parcel parcel = Parcel.obtain();
        try {
            detail.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            GalleryDetail copy = GalleryDetail.CREATOR.createFromParcel(parcel);
            // Keep the existing Parcelable wire format. The scene saves age in a separate Bundle key.
            assertEquals(-1, copy.fetchedAtElapsed);
            assertFalse(GalleryDetailState.isFresh(copy, 180100));
        } finally { parcel.recycle(); }
    }
    private GalleryDetail detail(String image, int offset) {
        GalleryDetail detail = new GalleryDetail(); detail.gid = 1; detail.token = "token";
        NormalPreviewSet previews = new NormalPreviewSet();
        previews.addItem(0, image, offset, 0, 100, 150, "https://example.org/page");
        detail.previewSet = previews; detail.previewPages = 1;
        GalleryComment comment = new GalleryComment(); comment.user = "user"; comment.comment = "text";
        detail.comments = new GalleryCommentList(new GalleryComment[]{comment}, false);
        return detail;
    }
    @Test public void bodyComparisonDetectsImageCropAndCommentChanges() {
        GalleryDetail a = detail("https://example.org/a", 0), b = detail("https://example.org/a", 0);
        assertTrue(GalleryDetailState.samePreviews(a, b)); assertTrue(GalleryDetailState.sameComments(a, b));
        assertFalse(GalleryDetailState.samePreviews(a, detail("https://example.org/a", 100)));
        assertFalse(GalleryDetailState.samePreviews(a, detail("https://example.org/b", 0)));
        b.comments.comments[0].comment = "updated";
        assertFalse(GalleryDetailState.sameComments(a, b));
        b.token = "different";
        assertFalse(GalleryDetailState.samePreviews(a, b));
    }
}
