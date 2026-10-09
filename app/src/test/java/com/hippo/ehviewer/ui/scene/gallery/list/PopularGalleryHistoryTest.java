package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class PopularGalleryHistoryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void excludesPreviousIdsWhilePreservingCurrentOrderAndEntirePreviousList() {
        PopularGalleryHistory history = history(0);
        history.record(galleries(30, 20, 10));
        history.record(galleries(40, 20, 50));
        assertEquals(Arrays.asList(40L, 50L), ids(history.galleries(PopularGalleryHistory.UPDATES)));
        assertEquals(Arrays.asList(30L, 20L, 10L), ids(history.galleries(PopularGalleryHistory.PREVIOUS)));
        assertEquals(Arrays.asList(40L, 20L, 50L), ids(history.galleries(PopularGalleryHistory.CURRENT)));
        history.galleries(PopularGalleryHistory.PREVIOUS).clear();
        assertEquals(3, history.galleries(PopularGalleryHistory.PREVIOUS).size());
    }

    @Test public void identicalRequestsStillAdvanceTheBaselineAndEmptyResponsesAreValid() {
        PopularGalleryHistory history = history(0);
        assertFalse(history.hasPrevious());
        history.record(galleries(10, 20));
        assertFalse(history.hasPrevious());
        history.record(galleries(10, 20));
        assertTrue(history.galleries(PopularGalleryHistory.UPDATES).isEmpty());
        history.record(Collections.emptyList());
        assertEquals(Arrays.asList(10L, 20L), ids(history.galleries(PopularGalleryHistory.PREVIOUS)));
        history.record(galleries(30));
        assertTrue(history.hasPrevious());
        assertTrue(history.galleries(PopularGalleryHistory.PREVIOUS).isEmpty());
        assertEquals(Collections.singletonList(30L), ids(history.galleries(PopularGalleryHistory.UPDATES)));
    }

    @Test public void snapshotsSurviveRestartWithMetadataAndStaySeparateBySite() throws Exception {
        PopularGalleryHistory history = history(0);
        List<GalleryInfo> previous = galleries(10);
        GalleryInfo gallery = previous.get(0);
        gallery.title = "上次的标题";
        gallery.thumb = "https://example.com/thumb.jpg";
        gallery.simpleTags = new String[]{"language:chinese"};
        gallery.pages = 42;
        gallery.rating = 4.5f;
        history.record(previous);
        previous.clear();
        history.record(galleries(20));
        history.persist();
        PopularGalleryHistory.IO.submit(() -> {}).get(5, TimeUnit.SECONDS);
        PopularGalleryHistory restored = history(0);
        restored.load();
        assertEquals(Collections.singletonList(20L), ids(restored.galleries(PopularGalleryHistory.CURRENT)));
        GalleryInfo old = restored.galleries(PopularGalleryHistory.PREVIOUS).get(0);
        assertEquals("上次的标题", old.title);
        assertEquals("token10", old.token);
        assertEquals(gallery.thumb, old.thumb);
        assertArrayEquals(gallery.simpleTags, old.simpleTags);
        assertEquals(42, old.pages);
        assertEquals(4.5f, old.rating, 0f);
        PopularGalleryHistory otherSite = history(1);
        otherSite.load();
        assertFalse(otherSite.hasCurrent());
    }

    @Test public void damagedCacheDoesNotBlockFreshRequests() throws Exception {
        Files.write(new File(folder.getRoot(), "popular-history-0.json").toPath(),
                "{broken".getBytes(StandardCharsets.UTF_8));
        PopularGalleryHistory history = history(0);
        history.load();
        assertFalse(history.hasCurrent());
        history.record(galleries(50));
        assertEquals(Collections.singletonList(50L), ids(history.galleries(PopularGalleryHistory.CURRENT)));
    }

    private PopularGalleryHistory history(int site) {
        return new PopularGalleryHistory(folder.getRoot(), site);
    }

    private static List<GalleryInfo> galleries(long... gids) {
        List<GalleryInfo> result = new ArrayList<>();
        for (long gid : gids) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.gid = gid;
            gallery.token = "token" + gid;
            result.add(gallery);
        }
        return result;
    }

    private static List<Long> ids(List<GalleryInfo> galleries) {
        List<Long> result = new ArrayList<>();
        for (GalleryInfo gallery : galleries) result.add(gallery.gid);
        return result;
    }
}
