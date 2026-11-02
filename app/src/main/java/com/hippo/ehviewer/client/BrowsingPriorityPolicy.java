package com.hippo.ehviewer.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One download budget shared by gallery images and archives, independent of Android/OkHttp. */
final class BrowsingPriorityPolicy {
    static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    static final int DOWNLOAD_BYTES_PER_SECOND = 128 * 1024;
    static final int READ_QUANTUM = 8 * 1024;

    interface WaitHooks {
        void pause();
        void resume();
    }

    interface Waiter {
        void await(Object lock, long nanos) throws InterruptedException;
    }

    private final LongSupplier clock;
    private final Waiter waiter;
    private volatile boolean enabled;
    private volatile boolean cellular;
    private int browsers;
    private long idleUntil;
    private boolean hasBrowsed;
    private boolean limiting;
    private long refilledAt;
    private double tokens;

    BrowsingPriorityPolicy() {
        this(System::nanoTime, (lock, nanos) ->
                TimeUnit.NANOSECONDS.timedWait(lock, nanos));
    }

    BrowsingPriorityPolicy(LongSupplier clock, Waiter waiter) {
        this.clock = clock;
        this.waiter = waiter;
    }

    synchronized void setEnabled(boolean value) {
        enabled = value;
        limiting = false;
        notifyAll();
    }

    synchronized void setCellular(boolean value) {
        if (cellular == value) return;
        cellular = value;
        limiting = false;
        notifyAll();
    }

    synchronized AutoCloseable beginBrowsing() {
        browsers++;
        hasBrowsed = true;
        notifyAll();
        return new AutoCloseable() {
            private boolean closed;

            @Override public void close() {
                synchronized (BrowsingPriorityPolicy.this) {
                    if (closed) return;
                    closed = true;
                    if (--browsers == 0) idleUntil = clock.getAsLong() + IDLE_NANOS;
                    BrowsingPriorityPolicy.this.notifyAll();
                }
            }
        };
    }

    synchronized boolean isThrottling() {
        return enabled && cellular && (browsers > 0
                || hasBrowsed && idleUntil - clock.getAsLong() > 0);
    }

    /** Wait before reading, never while holding the network's socket/source locks. */
    long readAllowance(long requested, BooleanSupplier cancelled, BooleanSupplier foreground,
                       WaitHooks hooks) throws IOException {
        // The default-off and Wi-Fi paths do not acquire the shared limiter lock.
        if (requested == 0 || !enabled || !cellular || foreground.getAsBoolean()) return requested;
        synchronized (this) {
            while (true) {
                if (cancelled.getAsBoolean()) throw new IOException("Canceled");
                if (foreground.getAsBoolean()) return requested;
                if (!isThrottling()) {
                    limiting = false;
                    return requested;
                }
                long now = clock.getAsLong();
                if (!limiting) {
                    limiting = true;
                    tokens = READ_QUANTUM;
                    refilledAt = now;
                } else {
                    tokens = Math.min(READ_QUANTUM, tokens
                            + (now - refilledAt) * (double) DOWNLOAD_BYTES_PER_SECOND / 1_000_000_000L);
                    refilledAt = now;
                }
                long amount = Math.min(requested, READ_QUANTUM);
                if (tokens >= amount) {
                    tokens -= amount;
                    return amount;
                }
                long wait = Math.max(1, (long) Math.ceil((amount - tokens)
                        * 1_000_000_000L / DOWNLOAD_BYTES_PER_SECOND));
                // Bound cancellation and current-page promotion latency even without a notification.
                wait = Math.min(wait, TimeUnit.MILLISECONDS.toNanos(100));
                if (browsers == 0) wait = Math.min(wait, Math.max(1, idleUntil - now));
                hooks.pause();
                try {
                    waiter.await(this, wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while yielding to browsing");
                } finally {
                    hooks.resume();
                }
            }
        }
    }
}
