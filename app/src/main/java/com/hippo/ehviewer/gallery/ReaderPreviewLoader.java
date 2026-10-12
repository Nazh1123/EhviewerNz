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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Latest-window decoding, with independent sources and bounded background cache writes. */
public final class ReaderPreviewLoader implements AutoCloseable {
    private final Context context;
    private final GalleryProvider2 provider;
    private final BiConsumer<Integer, Bitmap> listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> posters = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Object lock = new Object();
    private final Set<Integer> loading = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private final Worker[] workers;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10), runnable -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "Reader-Preview-Cache"), new ThreadPoolExecutor.DiscardOldestPolicy());
    private int[] pages = new int[0]; // Guarded by lock; ordered with the current target first.
    private int edge;
    private volatile boolean active;
    private volatile boolean interactive;
    private volatile boolean closed;

    public ReaderPreviewLoader(Context context, GalleryProvider2 provider, BiConsumer<Integer, Bitmap> listener) {
        this.context = context.getApplicationContext();
        this.provider = provider;
        this.listener = listener;
        workers = new Worker[Math.max(1, Math.min(4, provider.getPreviewConcurrency()))];
        for (int i = 0; i < workers.length; i++) workers[i] = new Worker(i);
    }

    public Bitmap get(int page) { return posters.get(page); }

    /** Called on the main thread. Updating a window never queues its obsolete remaining pages. */
    public void request(int[] desired, int edge, boolean interactive) {
        if (closed) return;
        synchronized (lock) {
            if (!active || !Arrays.equals(pages, desired)) failed.clear();
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
    }

    private boolean available(int page) {
        return page >= 0 && posters.get(page) == null && !loading.contains(page) && !failed.contains(page);
    }

    private void startWorkers() {
        if (!active || closed) return;
        synchronized (lock) {
            if (nextPage() < 0) return;
        }
        for (Worker worker : workers) worker.start();
    }

    private int claim() {
        synchronized (lock) {
            if (!active || closed) return -1;
            int page = nextPage();
            if (page >= 0) loading.add(page);
            return page;
        }
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
                        int page = claim();
                        if (page < 0) return;
                        try {
                            try {
                                Process.setThreadPriority(interactive ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                            } catch (SecurityException ignored) {
                                // Priority is only a scheduling hint; a restricted device must still decode the page.
                            }
                            Trace.beginSection("ReaderPreview.load");
                            ReaderPreviewBitmapCache.Result result;
                            try {
                                if (source == null) source = provider.createPreviewSource(context);
                                result = source.load(page, edge);
                            } finally { Trace.endSection(); }
                            if (closed || run != token || Thread.currentThread().isInterrupted()) return;
                            if (result == null) {
                                synchronized (lock) { failed.add(page); }
                                continue;
                            }
                            posters.put(page, result.bitmap);
                            handler.post(() -> {
                                if (!active || closed) return;
                                Trace.beginSection("ReaderPreview.bind");
                                try { listener.accept(page, result.bitmap); } finally { Trace.endSection(); }
                                startWorkers();
                            });
                            writer.execute(() -> result.persist(context));
                        } catch (IOException | RuntimeException failure) {
                            if (!(failure instanceof InterruptedIOException) && !Thread.currentThread().isInterrupted()) {
                                synchronized (lock) { failed.add(page); }
                                Log.w("ReaderPreview", "Unable to load local preview, page=" + (page + 1), failure);
                            }
                        } finally {
                            synchronized (lock) { loading.remove(page); }
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
