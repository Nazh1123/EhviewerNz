package com.hippo.ehviewer.client;

import android.app.Application;
import android.os.Looper;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class GalleryDisplayMetadataLoaderTest {
    private GalleryInfo gallery(long gid) {
        GalleryInfo info = new GalleryInfo(); info.gid = gid; info.token = "token"; return info;
    }
    @SuppressWarnings("unchecked") private void succeed(EhRequest request) {
        List<GalleryInfo> items = (List<GalleryInfo>) request.getArgs()[0];
        for (GalleryInfo item : items) {
            item.firstGid = item.gid; item.pages = 42; item.simpleTags = new String[]{"other:uncensored"};
        }
        request.getCallback().onSuccess(items);
    }
    @Test public void batchesVisibleRequestsAndNeverMutatesBeforeMainCallback() {
        List<EhRequest> sent = new ArrayList<>();
        GalleryDisplayMetadataLoader loader = new GalleryDisplayMetadataLoader(sent::add, () -> "scope");
        AtomicInteger changes = new AtomicInteger();
        GalleryInfo first = gallery(1), recycled = gallery(2);
        loader.request(first, changes::incrementAndGet);
        loader.request(recycled, changes::incrementAndGet).cancel();
        assertTrue(sent.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
        assertEquals(1, sent.size());
        assertEquals(1, ((List<?>) sent.get(0).getArgs()[0]).size());
        assertEquals(0, first.pages);
        succeed(sent.get(0));
        assertEquals(42, first.pages); assertEquals(0, recycled.pages); assertEquals(1, changes.get());
        loader.clear();
    }
    @Test public void cancelledAndOldAccountCallbacksCannotChangeRows() {
        List<EhRequest> sent = new ArrayList<>();
        String[] scope = {"a"};
        GalleryDisplayMetadataLoader loader = new GalleryDisplayMetadataLoader(sent::add, () -> scope[0]);
        GalleryInfo first = gallery(1);
        loader.request(first, () -> fail("stale callback"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
        scope[0] = "b";
        succeed(sent.get(0));
        assertEquals(0, first.pages);
        loader.request(first, () -> fail("detached callback"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
        loader.clear();
        succeed(sent.get(1));
        assertEquals(0, first.pages);
    }
    @Test public void optionalFailureKeepsKnownDataAndLaterBindCanRetry() {
        List<EhRequest> sent = new ArrayList<>();
        GalleryDisplayMetadataLoader loader = new GalleryDisplayMetadataLoader(sent::add, () -> "scope");
        GalleryInfo first = gallery(1); first.title = "Known"; first.favoriteSlot = 3;
        loader.request(first, () -> fail("failed request must not publish"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
        sent.get(0).getCallback().onFailure(new java.io.IOException("offline"));
        assertEquals("Known", first.title);
        loader.request(first, () -> {});
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
        first.pages = 99; // A detail response arrived after dispatch.
        succeed(sent.get(1));
        assertEquals(99, first.pages); assertEquals(3, first.favoriteSlot);
        loader.clear();
    }
}
