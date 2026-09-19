package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Short-lived API metadata, shared across overlapping subscription sources. */
public final class SubscriptionMetadataCache {
    public interface Loader { void load(List<GalleryInfo> items) throws Throwable; }
    private record Key(String scope, long gid, String token) {}
    private record Cached(GalleryInfo value, long completed) {}
    private static final class Pending {
        final CountDownLatch ready = new CountDownLatch(1);
        GalleryInfo value;
        Throwable error;
    }
    private final LinkedHashMap<Key, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private final Map<Key, Pending> loading = new HashMap<>();
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final java.util.function.LongSupplier clock;
    public SubscriptionMetadataCache() { this(System::nanoTime); }
    SubscriptionMetadataCache(java.util.function.LongSupplier clock) { this.clock = clock; }

    public void fill(String scope, List<GalleryInfo> galleries, Loader loader) throws Throwable {
        Map<Key, Pending> waits = new LinkedHashMap<>();
        Map<Key, GalleryInfo> owned = new LinkedHashMap<>();
        synchronized (this) {
            long now = clock.getAsLong();
            for (GalleryInfo gallery : galleries) {
                Key key = new Key(scope, gallery.gid, gallery.token);
                Cached hit = cache.get(key);
                if (hit != null && now - hit.completed < TTL_NANOS) {
                    applyMetadata(hit.value, gallery);
                    continue;
                }
                Pending pending = loading.get(key);
                if (pending == null) {
                    pending = new Pending();
                    loading.put(key, pending);
                    GalleryInfo item = new GalleryInfo();
                    item.gid = gallery.gid; item.token = gallery.token;
                    owned.put(key, item);
                }
                waits.put(key, pending);
            }
        }
        if (!owned.isEmpty()) {
            Throwable failure = null;
            try { loader.load(new ArrayList<>(owned.values())); }
            catch (Throwable error) { failure = error; }
            synchronized (this) {
                for (Map.Entry<Key, GalleryInfo> entry : owned.entrySet()) {
                    Pending pending = loading.remove(entry.getKey());
                    GalleryInfo value = entry.getValue();
                    pending.error = failure;
                    if (failure == null && value.firstGid != null && value.firstGid > 0
                            && value.uploader != null && value.simpleTags != null) {
                        pending.value = value;
                        cache.put(entry.getKey(), new Cached(value, clock.getAsLong()));
                    } else if (failure == null && value.firstGid != null && value.firstGid < 0) {
                        pending.value = value;
                    } else if (failure == null) {
                        pending.error = new IOException("Subscription metadata is unavailable");
                    }
                    pending.ready.countDown();
                }
                while (cache.size() > 512) cache.remove(cache.keySet().iterator().next());
            }
        }
        for (GalleryInfo gallery : galleries) {
            Pending pending = waits.get(new Key(scope, gallery.gid, gallery.token));
            if (pending == null) continue;
            if (!pending.ready.await(15, TimeUnit.SECONDS)) throw new IOException("Metadata lookup timed out");
            if (pending.error != null) throw pending.error;
            applyMetadata(pending.value, gallery);
        }
    }
    private static void applyMetadata(GalleryInfo from, GalleryInfo to) {
        if (from.firstGid != null && from.firstGid < 0) {
            to.firstGid = from.firstGid;
            return;
        }
        copyMetadata(from, to);
    }
    static void copyMetadata(GalleryInfo from, GalleryInfo to) {
        to.title = from.title; to.titleJpn = from.titleJpn; to.uploader = from.uploader;
        to.thumb = from.thumb; to.category = from.category; to.posted = from.posted;
        to.rating = from.rating; to.pages = from.pages; to.firstGid = from.firstGid;
        to.simpleTags = from.simpleTags != null ? from.simpleTags.clone() : null;
        to.simpleLanguage = from.simpleLanguage;
        // Preserve local favorites, rated state, display state and download state.
    }
}
