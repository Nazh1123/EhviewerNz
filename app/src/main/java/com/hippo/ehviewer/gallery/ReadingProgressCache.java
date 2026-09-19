package com.hippo.ehviewer.gallery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongToIntFunction;

/** Small, cancellable cache owned by a list adapter. No Activity or View is retained by entries. */
public final class ReadingProgressCache {
    private static final int MAX_ENTRIES = 256;
    private static final long TTL = TimeUnit.SECONDS.toNanos(5);
    private record Entry(int page, long time) {}
    private final LinkedHashMap<Long, Entry> cache = new LinkedHashMap<>(32, .75f, true);
    private final Map<Long, Load> pending = new HashMap<>();
    private final Executor executor;
    private final LongToIntFunction loader;
    private final LongSupplier clock;

    private static final class Load {
        final List<Request> requests = new ArrayList<>();
    }

    public static final class Request {
        private volatile boolean cancelled;
        private final IntConsumer callback;
        private Request(IntConsumer callback) { this.callback = callback; }
        public void cancel() { cancelled = true; }
        private void deliver(int page) {
            if (!cancelled) callback.accept(page);
        }
    }

    public ReadingProgressCache(Executor executor, LongToIntFunction loader) {
        this(executor, loader, System::nanoTime);
    }

    ReadingProgressCache(Executor executor, LongToIntFunction loader, LongSupplier clock) {
        this.executor = executor;
        this.loader = loader;
        this.clock = clock;
    }

    public Request request(long gid, IntConsumer callback) {
        Request request = new Request(callback);
        Load load;
        synchronized (this) {
            Entry hit = cache.get(gid);
            if (hit != null && clock.getAsLong() - hit.time < TTL) {
                request.deliver(hit.page);
                return request;
            }
            load = pending.get(gid);
            if (load != null) {
                load.requests.add(request);
                return request;
            }
            load = new Load();
            load.requests.add(request);
            pending.put(gid, load);
        }
        Load scheduled = load;
        try {
            executor.execute(() -> load(gid, scheduled));
        } catch (RejectedExecutionException e) {
            finish(gid, scheduled, 0, false);
        }
        return request;
    }

    private void load(long gid, Load load) {
        synchronized (this) {
            if (pending.get(gid) != load) return;
            // Fast scrolling should not turn recycled holders into a backlog of disk reads.
            if (load.requests.stream().allMatch(request -> request.cancelled)) {
                pending.remove(gid);
                return;
            }
        }
        int page = 0;
        boolean success = false;
        try {
            page = Math.max(0, loader.applyAsInt(gid));
            success = true;
        } catch (RuntimeException ignored) {
            // Failure is not a cached unread position; the next binding can retry.
        }
        finish(gid, load, page, success);
    }

    private void finish(long gid, Load load, int page, boolean success) {
        List<Request> requests;
        synchronized (this) {
            if (pending.get(gid) != load) return;
            pending.remove(gid);
            if (success) {
                cache.put(gid, new Entry(page, clock.getAsLong()));
                while (cache.size() > MAX_ENTRIES) cache.remove(cache.keySet().iterator().next());
            }
            requests = new ArrayList<>(load.requests);
        }
        for (Request request : requests) request.deliver(page);
    }

    /** Invalidate on return from reading, and cancel callbacks when the adapter is detached. */
    public synchronized void clear() {
        cache.clear();
        for (Load load : pending.values()) {
            for (Request request : load.requests) request.cancel();
        }
        pending.clear();
    }
}
