package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.util.Log;
import android.util.LruCache;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/** Latest-window decoding and a progressively prepared, bounded gallery overview. */
public final class ReaderPreviewLoader implements AutoCloseable {
    private final Context context;
    private final GalleryProvider2 provider;
    private final BiConsumer<Integer, Bitmap> listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> posters = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    // Keep small versions of distant pages after the detailed neighborhood leaves the LRU.
    // No bitmap is recycled here: a preview ImageView may still hold it.
    private final LruCache<Integer, Bitmap> overview = new LruCache<>((int) Math.max(1024 * 1024,
            Math.min(16 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32))) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Object lock = new Object();
    private final Set<Integer> loading = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private final BitSet prepared = new BitSet();
    private final Worker[] workers;
    private final int prepareConcurrency;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10), runnable -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "Reader-Preview-Cache"), new ThreadPoolExecutor.DiscardOldestPolicy());
    private int[] pages = new int[0]; // Guarded by lock; ordered with the current target first.
    private int edge;
    private volatile int generation;
    private int gallerySize;
    private int prepareCenter;
    private int prepareCursor;
    private int preparing; // Leave workers available for seeking while the overview is built.
    private volatile boolean active;
    private volatile boolean interactive;
    private volatile boolean closed;

    public ReaderPreviewLoader(Context context, GalleryProvider2 provider, BiConsumer<Integer, Bitmap> listener) {
        this.context = context.getApplicationContext();
        this.provider = provider;
        this.listener = listener;
        workers = new Worker[Math.max(1, Math.min(4, provider.getPreviewConcurrency()))];
        prepareConcurrency = Math.max(1, Math.min(2, workers.length - 1));
        for (int i = 0; i < workers.length; i++) workers[i] = new Worker(i);
    }

    public Bitmap get(int page) {
        Bitmap bitmap = posters.get(page);
        return bitmap != null ? bitmap : overview.get(page);
    }

    /** A website thumbnail is a fallback only after the corresponding local attempt fails. */
    public boolean isUnavailable(int page) {
        synchronized (lock) { return failed.contains(page); }
    }

    /** Prepare the neighborhood first, then cover the gallery with at most two background readers. */
    public void prepare(int[] desired, int edge, int count) {
        if (closed || count <= 0) return;
        synchronized (lock) {
            int center = desired.length > 0 ? Math.max(0, desired[0]) : 0;
            if (!active || gallerySize != count || prepareCenter != center) prepareCursor = 0;
            gallerySize = count;
            prepareCenter = center;
        }
        request(desired, edge, false);
    }

    /** Called on the main thread. Updating a window never queues its obsolete remaining pages. */
    public void request(int[] desired, int edge, boolean interactive) {
        if (closed) return;
        synchronized (lock) {
            if (this.edge != edge) {
                generation++;
                posters.evictAll();
                overview.evictAll();
                prepared.clear();
                loading.clear();
                preparing = 0;
                prepareCursor = 0;
            }
            if (!active || this.edge != edge) failed.clear();
            // Keep a miss only while it remains in both windows. An obsolete worker can
            // finish after a jump; its failure must not poison a later visit to that page.
            else if (!Arrays.equals(pages, desired))
                failed.removeIf(page -> !contains(pages, page) || !contains(desired, page));
            pages = desired.clone();
            this.edge = edge;
            this.interactive = interactive;
            active = true;
        }
        startWorkers();
    }

    public boolean isLoading() {
        for (Worker worker : workers) if (worker.run != null) return true;
        return false;
    }

    private static boolean contains(int[] window, int page) {
        for (int candidate : window) if (candidate == page) return true;
        return false;
    }

    public void stop() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        for (Worker worker : workers) worker.stop();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        stop();
        for (Worker worker : workers) {
            worker.executor.execute(() -> {
                if (worker.source != null) try { worker.source.close(); } catch (IOException ignored) { }
                worker.source = null;
            });
            worker.executor.shutdown();
        }
        writer.shutdownNow();
        posters.evictAll();
        overview.evictAll();
    }

    private boolean available(int page) {
        return page >= 0 && posters.get(page) == null && (interactive || !prepared.get(page))
                && !loading.contains(page) && !failed.contains(page);
    }

    private void startWorkers() {
        if (!active || closed) return;
        synchronized (lock) {
            if (nextPage() < 0 && (interactive || preparing >= prepareConcurrency || prepareCursor >= (long) gallerySize * 2)) return;
        }
        for (Worker worker : workers) worker.start();
    }

    private Work claim() {
        synchronized (lock) {
            if (!active || closed) return null;
            int page = nextPage();
            if (page >= 0) {
                loading.add(page);
                return new Work(page, false, edge, generation);
            }
            if (interactive || preparing >= prepareConcurrency || gallerySize <= 0) return null;
            // Alternate forward/backward from the reader page. Never loop over evicted posters
            // indefinitely: a completed scan is represented by one bit per page.
            while (prepareCursor < (long) gallerySize * 2) {
                int step = prepareCursor++;
                int candidate = prepareCenter + (step % 2 == 0 ? step / 2 : -(step + 1) / 2);
                if (candidate < 0 || candidate >= gallerySize || prepared.get(candidate)
                        || loading.contains(candidate)) continue;
                if (posters.get(candidate) != null) { prepared.set(candidate); continue; }
                loading.add(candidate);
                preparing++;
                return new Work(candidate, true, edge, generation);
            }
            return null;
        }
    }

    private static final class Work {
        final int page;
        final boolean prepare;
        final int edge;
        final int generation;
        Work(int page, boolean prepare, int edge, int generation) {
            this.page = page; this.prepare = prepare;
            this.edge = edge; this.generation = generation;
        }
    }

    private void remember(int page, Bitmap bitmap) {
        posters.put(page, bitmap);
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap small = longest <= 96 ? bitmap : Bitmap.createScaledBitmap(bitmap,
                Math.max(1, bitmap.getWidth() * 96 / longest),
                Math.max(1, bitmap.getHeight() * 96 / longest), true);
        overview.put(page, small);
        synchronized (lock) { prepared.set(page); }
    }

    /** Guarded by lock. Leave spare workers available for a newer target while the center decodes. */
    private int nextPage() {
        if (interactive && pages.length > 0 && pages[0] >= 0
                && posters.get(pages[0]) == null && !failed.contains(pages[0])) {
            if (available(pages[0])) return pages[0];
            // Limit neighbors while the center is pending: four concurrent codecs can slow
            // the target itself. Spare workers can claim a newer target during a fast drag.
            if (workers.length > 1 && loading.size() >= workers.length / 2) return -1;
        }
        for (int page : pages) if (available(page)) return page;
        return -1;
    }

    private final class Worker {
        final ThreadPoolExecutor executor;
        volatile Object run;
        Future<?> task;
        ReaderPreviewSource source; // Accessed only by this worker's executor.

        Worker(int index) {
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                    runnable -> new Thread(runnable, "Reader-Preview-" + index));
        }

        void stop() {
            run = null;
            if (task != null) task.cancel(true);
            task = null;
            executor.getQueue().clear();
        }

        void start() {
            if (run != null) return;
            Object token = new Object();
            run = token;
            task = executor.submit(() -> {
                try {
                    while (active && !closed && run == token && !Thread.currentThread().isInterrupted()) {
                        Work work = claim();
                        if (work == null) return;
                        int page = work.page;
                        try {
                            try {
                                Process.setThreadPriority(interactive && !work.prepare
                                        ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                            } catch (SecurityException ignored) {
                                // Priority is only a scheduling hint; a restricted device must still decode the page.
                            }
                            Trace.beginSection("ReaderPreview.load");
                            ReaderPreviewBitmapCache.Result result;
                            try {
                                if (source == null) source = provider.createPreviewSource(context);
                                result = source.load(page, work.edge);
                            } finally { Trace.endSection(); }
                            if (closed || run != token || Thread.currentThread().isInterrupted()) return;
                            synchronized (lock) {
                                // A resize invalidates both cached posters and in-flight decodes.
                                if (work.generation != generation) continue;
                                if (result == null) {
                                    failed.add(page);
                                    continue;
                                }
                                remember(page, result.bitmap);
                            }
                            if (interactive) handler.post(() -> {
                                if (!active || closed || !interactive || work.generation != generation) return;
                                Trace.beginSection("ReaderPreview.bind");
                                try { listener.accept(page, result.bitmap); } finally { Trace.endSection(); }
                                startWorkers();
                            });
                            writer.execute(() -> result.persist(context));
                            // Yield between distant pages; preparation must not compete with page turns.
                            if (work.prepare) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(12));
                        } catch (IOException | RuntimeException failure) {
                            if (!(failure instanceof InterruptedIOException) && !Thread.currentThread().isInterrupted()) {
                                synchronized (lock) {
                                    if (work.generation == generation) failed.add(page);
                                }
                                Log.w("ReaderPreview", "Unable to load local preview, page=" + (page + 1), failure);
                            }
                        } finally {
                            synchronized (lock) {
                                if (work.generation == generation) {
                                    loading.remove(page);
                                    if (work.prepare) {
                                        preparing--;
                                        if (!Thread.currentThread().isInterrupted()) prepared.set(page);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    handler.post(() -> {
                        if (run == token) { run = null; task = null; }
                        // Covers updates arriving after claim() found no work, and completed cancellations.
                        startWorkers();
                    });
                }
            });
        }
    }
}
