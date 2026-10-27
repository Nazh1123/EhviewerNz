package com.hippo.ehviewer.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import okhttp3.*;
import okio.Timeout;

/** A bounded refresh of a slow subscription HTML GET, with at most two extra calls app-wide. */
final class SubscriptionPageCall implements Call {
    private static final long REFRESH_AFTER_MS = 3000;
    private static final long CALL_BUDGET_MS = 12000;
    private static final Semaphore SPARES = new Semaphore(2);
    private static final Pattern GALLERY_LIST = Pattern.compile("class\\s*=\\s*[\"'][^\"']*\\bitg\\b", Pattern.CASE_INSENSITIVE);
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1,
            runnable -> daemon(runnable, "SubscriptionRefreshTimer"));
    private static final ExecutorService REFRESH = Executors.newFixedThreadPool(2,
            runnable -> daemon(runnable, "SubscriptionRefresh"));
    static { TIMER.setRemoveOnCancelPolicy(true); }

    private final OkHttpClient client;
    private final Call primary;
    private final long refreshAfterMs;
    private final long budgetMs;
    private final Semaphore spares;
    private final AtomicBoolean executed = new AtomicBoolean();
    private final AtomicReference<Response> winner = new AtomicReference<>();
    private final CountDownLatch refreshed = new CountDownLatch(1);
    private volatile boolean cancelled;
    private boolean finished;
    private Call backup;

    SubscriptionPageCall(OkHttpClient client, Request request) {
        this(client, request, REFRESH_AFTER_MS, budget(client), SPARES);
    }

    static OkHttpClient boundedClient(OkHttpClient client) {
        if (client.callTimeoutMillis() > 0 && client.callTimeoutMillis() <= CALL_BUDGET_MS) return client;
        return client.newBuilder().callTimeout(CALL_BUDGET_MS, TimeUnit.MILLISECONDS).build();
    }

    private static long budget(OkHttpClient client) {
        return client.callTimeoutMillis() > 0 ? Math.min(CALL_BUDGET_MS, client.callTimeoutMillis()) : CALL_BUDGET_MS;
    }

    SubscriptionPageCall(OkHttpClient client, Request request, long refreshAfterMs,
                         long budgetMs, Semaphore spares) {
        if (!request.method().equals("GET")) throw new IllegalArgumentException("Only HTML GETs may refresh");
        this.client = client;
        primary = client.newCall(request);
        primary.timeout().timeout(budgetMs, TimeUnit.MILLISECONDS);
        this.refreshAfterMs = refreshAfterMs;
        this.budgetMs = budgetMs;
        this.spares = spares;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override public Response execute() throws IOException {
        if (!executed.compareAndSet(false, true)) throw new IllegalStateException("Already executed");
        if (cancelled) throw new IOException("Canceled");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        ScheduledFuture<?> timer = TIMER.schedule(() -> refresh(deadline), refreshAfterMs, TimeUnit.MILLISECONDS);
        try {
            try {
                select(read(primary));
            } catch (IOException original) {
                boolean waiting;
                synchronized (this) { waiting = backup != null; }
                if (!cancelled && waiting && winner.get() == null) {
                    try {
                        refreshed.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Subscription request interrupted");
                    }
                }
                if (winner.get() == null) throw original;
            }
            if (cancelled) throw new IOException("Canceled");
            Response response = winner.getAndSet(null);
            if (response == null) throw new IOException("Subscription request timed out");
            return response;
        } finally {
            timer.cancel(false);
            synchronized (this) {
                finished = true;
                primary.cancel();
                if (backup != null) backup.cancel();
            }
            Response unused = winner.getAndSet(null);
            if (unused != null) unused.close();
        }
    }

    private void refresh(long deadline) {
        synchronized (this) {
            if (finished || cancelled || winner.get() != null || !spares.tryAcquire()) return;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { spares.release(); return; }
            backup = client.newCall(request());
            backup.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
        }
        REFRESH.execute(() -> {
            try {
                Response response = read(backup);
                if (!isGalleryPage(response)) {
                    response.close();
                    return;
                }
                if (select(response)) primary.cancel();
            } catch (IOException ignored) {
                // A failed refresh must leave the original request running.
            } finally {
                refreshed.countDown();
                spares.release();
            }
        });
    }

    private static boolean isGalleryPage(Response response) throws IOException {
        if (!response.isSuccessful() || response.body() == null) return false;
        // An HTTP 200 challenge, login error or image must not replace a valid original page.
        String html = response.peekBody(2L * 1024 * 1024).string();
        return GALLERY_LIST.matcher(html).find() || html.contains("No hits found")
                || html.contains("You do not have any watched tags");
    }

    private synchronized boolean select(Response response) {
        if (!finished && !cancelled && winner.compareAndSet(null, response)) return true;
        response.close();
        return false;
    }

    private static Response read(Call call) throws IOException {
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            return body == null ? response.newBuilder().build()
                    : response.newBuilder().body(ResponseBody.create(body.contentType(), body.bytes())).build();
        }
    }

    @Override public synchronized void cancel() {
        cancelled = true;
        primary.cancel();
        if (backup != null) backup.cancel();
        refreshed.countDown();
    }
    @Override public boolean isCanceled() { return cancelled; }
    @Override public boolean isExecuted() { return executed.get(); }
    @Override public Request request() { return primary.request(); }
    @Override public Timeout timeout() { return primary.timeout(); }
    @Override public SubscriptionPageCall clone() {
        return new SubscriptionPageCall(client, request(), refreshAfterMs, budgetMs, spares);
    }
    @Override public void enqueue(Callback callback) {
        throw new UnsupportedOperationException("Subscription pages use synchronous execution");
    }
}
