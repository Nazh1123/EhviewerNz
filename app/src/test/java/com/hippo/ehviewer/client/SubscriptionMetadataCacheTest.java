package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class SubscriptionMetadataCacheTest {
    private static GalleryInfo gallery(long gid) {
        GalleryInfo item = new GalleryInfo(); item.gid = gid; item.token = "token";
        return item;
    }
    private static void populate(List<GalleryInfo> list) {
        for (GalleryInfo item : list) {
            item.firstGid = item.gid; item.uploader = "alice"; item.title = "Title";
            item.simpleTags = new String[]{"language:english"}; item.pages = 25;
        }
    }

    @Test public void overlappingRequestsCoalesceInFlightAndKeepCopiesIndependent() throws Exception {
        SubscriptionMetadataCache cache = new SubscriptionMetadataCache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        GalleryInfo a = gallery(1), b = gallery(1), extra = gallery(2);
        try {
            Future<?> first = pool.submit(() -> {
                try { cache.fill("account", Collections.singletonList(a), items -> {
                    loads.incrementAndGet(); entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS)); populate(items);
                }); } catch (Throwable t) { throw new AssertionError(t); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch secondLoaded = new CountDownLatch(1);
            Future<?> second = pool.submit(() -> {
                try { cache.fill("account", Arrays.asList(b, extra), items -> {
                    assertEquals(1, items.size()); assertEquals(2, items.get(0).gid);
                    loads.incrementAndGet(); populate(items); secondLoaded.countDown();
                }); } catch (Throwable t) { throw new AssertionError(t); }
            });
            assertTrue(secondLoaded.await(5, TimeUnit.SECONDS));
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(2, loads.get()); assertEquals(a.uploader, b.uploader);
            a.simpleTags[0] = "changed";
            assertEquals("language:english", b.simpleTags[0]);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void duplicatesAreBatchedAndMetadataDoesNotOverwriteLocalFlags() throws Throwable {
        SubscriptionMetadataCache cache = new SubscriptionMetadataCache();
        GalleryInfo a = gallery(1), b = gallery(1);
        a.favoriteSlot = 3; a.rated = true; a.spanIndex = 5;
        cache.fill("account", Arrays.asList(a,b), items -> { assertEquals(1,items.size()); populate(items); });
        assertEquals("alice",b.uploader); assertEquals(3,a.favoriteSlot); assertTrue(a.rated); assertEquals(5,a.spanIndex);
        a.simpleTags[0] = "mutated";
        GalleryInfo c = gallery(1);
        cache.fill("account", Collections.singletonList(c), items -> fail("cache should be reused"));
        assertEquals("language:english",c.simpleTags[0]);
    }

    @Test public void expiryAccountAndTokenChangesRequireNewMetadata() throws Throwable {
        AtomicLong now = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        SubscriptionMetadataCache cache = new SubscriptionMetadataCache(now::get);
        SubscriptionMetadataCache.Loader loader = items -> { calls.incrementAndGet(); populate(items); };
        cache.fill("a",Collections.singletonList(gallery(1)),loader);
        cache.fill("a",Collections.singletonList(gallery(1)),loader);
        assertEquals(1,calls.get());
        now.set(TimeUnit.SECONDS.toNanos(31));
        cache.fill("a",Collections.singletonList(gallery(1)),loader);
        cache.fill("b",Collections.singletonList(gallery(1)),loader);
        GalleryInfo changed = gallery(1); changed.token = "newToken";
        cache.fill("b",Collections.singletonList(changed),loader);
        assertEquals(4,calls.get());
    }

    @Test public void failureDoesNotPoisonRetryOrWaiters() throws Throwable {
        SubscriptionMetadataCache cache = new SubscriptionMetadataCache();
        try {
            cache.fill("a",Collections.singletonList(gallery(1)),items -> { throw new IOException("offline"); });
            fail("must propagate failure");
        } catch (IOException expected) { assertEquals("offline",expected.getMessage()); }
        GalleryInfo retry = gallery(1);
        cache.fill("a",Collections.singletonList(retry),SubscriptionMetadataCacheTest::populate);
        assertEquals("alice",retry.uploader);
    }

    @Test public void unavailableApiEntryDoesNotEraseKnownTitle() throws Throwable {
        SubscriptionMetadataCache cache = new SubscriptionMetadataCache();
        GalleryInfo gallery = gallery(1); gallery.title = "Known title";
        cache.fill("a",Collections.singletonList(gallery), items -> items.get(0).firstGid = -1L);
        assertEquals("Known title",gallery.title); assertEquals(Long.valueOf(-1),gallery.firstGid);
        cache.fill("a",Collections.singletonList(gallery),SubscriptionMetadataCacheTest::populate);
        assertEquals("alice",gallery.uploader);
    }

    @Test public void sharedListSnapshotsDoNotLeakLayoutOrArrayMutations() {
        GalleryInfo original = gallery(1); populate(Collections.singletonList(original));
        original.tgList = new ArrayList<>(Collections.singletonList("tag")); original.favoriteSlot = 3;
        GalleryInfo copy = SubscriptionGallerySnapshot.copy(Collections.singletonList(original)).get(0);
        copy.spanIndex = 9; copy.favoriteSlot = 7; copy.simpleTags[0] = "changed"; copy.tgList.clear();
        assertEquals(0,original.spanIndex); assertEquals(3,original.favoriteSlot);
        assertEquals("language:english",original.simpleTags[0]); assertEquals(1,original.tgList.size());
    }
}
