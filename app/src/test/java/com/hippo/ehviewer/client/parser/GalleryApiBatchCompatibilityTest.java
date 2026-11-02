package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryApiBatchCompatibilityTest {

    @Test
    public void badEntriesDoNotAbortBatchOrLoseVersionRoot() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication());
        GalleryInfo removed = gallery(100L);
        GalleryInfo missingTitle = gallery(200L);
        GalleryInfo nullTitle = gallery(300L);
        GalleryInfo healthy = gallery(400L);
        GalleryInfo notReturned = gallery(500L);
        JSONArray metadata = new JSONArray()
                .put(new JSONObject().put("gid", removed.gid).put("error", "Removed"))
                .put(new JSONObject().put("gid", missingTitle.gid))
                .put(new JSONObject().put("gid", nullTitle.gid).put("title", JSONObject.NULL))
                .put(new JSONObject()
                        .put("gid", healthy.gid).put("first_gid", "50")
                        .put("title", "Healthy gallery").put("title_jpn", "")
                        .put("category", "Manga").put("thumb", "https://example.com/thumb.jpg")
                        .put("uploader", "author").put("posted", "1700000000")
                        .put("rating", "4.5").put("tags", new JSONArray()).put("filecount", "10"));

        GalleryApiParser.parse(new JSONObject().put("gmetadata", metadata).toString(),
                Arrays.asList(removed, missingTitle, nullTitle, healthy, notReturned));

        assertEquals(Long.valueOf(-1L), removed.firstGid);
        assertEquals(Long.valueOf(-1L), missingTitle.firstGid);
        assertEquals(Long.valueOf(-1L), nullTitle.firstGid);
        assertNull(removed.title);
        assertNull(missingTitle.title);
        assertNull(nullTitle.title);
        assertEquals(Long.valueOf(50L), healthy.firstGid);
        assertEquals("Healthy gallery", healthy.title);
        assertEquals(10, healthy.pages);
        assertNull(notReturned.firstGid);
    }

    private static GalleryInfo gallery(long gid) {
        GalleryInfo gallery = new GalleryInfo();
        gallery.gid = gid;
        return gallery;
    }
}
