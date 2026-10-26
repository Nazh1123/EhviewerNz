package com.hippo.ehviewer.client;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class BrowsingPriorityPolicyTest {
    private final AtomicLong now = new AtomicLong();
    private final AtomicInteger pauses = new AtomicInteger();
    private final AtomicInteger resumes = new AtomicInteger();
    private final BrowsingPriorityPolicy.WaitHooks hooks = new BrowsingPriorityPolicy.WaitHooks() {
        @Override public void pause() { pauses.incrementAndGet(); }
        @Override public void resume() { resumes.incrementAndGet(); }
    };
    private final BrowsingPriorityPolicy policy = new BrowsingPriorityPolicy(now::get,
            (lock, nanos) -> now.addAndGet(nanos));

    private long read(long size) throws IOException {
        return policy.readAllowance(size, () -> false, () -> false, hooks);
    }

    private void enable() {
        policy.setEnabled(true);
        policy.setCellular(true);
    }

    @Test public void disabledWifiAndNoBrowsingDoNotLimitDownloads() throws Exception {
        AutoCloseable activity = policy.beginBrowsing();
        assertEquals(1_000_000, read(1_000_000));
        policy.setEnabled(true);
        assertEquals(1_000_000, read(1_000_000));
        policy.setCellular(true);
        activity.close();
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertEquals(1_000_000, read(1_000_000));
        assertEquals(0, pauses.get());
    }

    @Test public void allRequestsMustEndAndStayIdleBeforeRestoringSpeed() throws Exception {
        enable();
        AutoCloseable first = policy.beginBrowsing();
        AutoCloseable second = policy.beginBrowsing();
        first.close();
        first.close(); // close/failure/callEnd may converge on the same request.
        now.addAndGet(TimeUnit.SECONDS.toNanos(10));
        assertTrue(policy.isThrottling());
        second.close();
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS - 1);
        assertTrue(policy.isThrottling());
        now.incrementAndGet();
        assertFalse(policy.isThrottling());
        assertEquals(1_000_000, read(1_000_000));
    }

    @Test public void anotherBrowseDuringGraceStartsANewIdlePeriod() throws Exception {
        enable();
        policy.beginBrowsing().close();
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        AutoCloseable next = policy.beginBrowsing();
        now.addAndGet(TimeUnit.SECONDS.toNanos(5));
        assertTrue(policy.isThrottling());
        next.close();
        assertTrue(policy.isThrottling());
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertFalse(policy.isThrottling());
    }

    @Test public void parallelDownloadsShareOneRateAndHaveOnlyOneSmallBurst() throws Exception {
        enable();
        policy.beginBrowsing();
        // These calls represent alternating workers; no per-worker bucket is created.
        for (int i = 0; i < 17; i++) assertEquals(8192, read(64 * 1024));
        assertEquals(TimeUnit.SECONDS.toNanos(1), now.get());
        assertEquals(pauses.get(), resumes.get());
    }

    @Test public void foregroundReadNeverConsumesDownloadQuota() throws Exception {
        enable();
        policy.beginBrowsing();
        assertEquals(8192, read(8192));
        assertEquals(1_000_000, policy.readAllowance(1_000_000, () -> false, () -> true, hooks));
        read(8192);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(62) + 500_000, now.get());
    }

    @Test public void switchingToWifiOrDisablingReleasesWaitingDownloads() throws Exception {
        for (boolean wifi : new boolean[]{true, false}) {
            AtomicBoolean waited = new AtomicBoolean();
            BrowsingPriorityPolicy changing = new BrowsingPriorityPolicy(now::get, (lock, nanos) -> {
                waited.set(true);
                if (wifi) ((BrowsingPriorityPolicy) lock).setCellular(false);
                else ((BrowsingPriorityPolicy) lock).setEnabled(false);
            });
            changing.setEnabled(true);
            changing.setCellular(true);
            changing.beginBrowsing();
            changing.readAllowance(8192, () -> false, () -> false, hooks);
            assertEquals(64 * 1024, changing.readAllowance(64 * 1024,
                    () -> false, () -> false, hooks));
            assertTrue(waited.get());
        }
    }

    @Test public void downloadAlreadyWaitingCanBePromotedToCurrentPage() throws Exception {
        AtomicBoolean visible = new AtomicBoolean();
        BrowsingPriorityPolicy waiting = new BrowsingPriorityPolicy(now::get,
                (lock, nanos) -> visible.set(true));
        waiting.setEnabled(true);
        waiting.setCellular(true);
        waiting.beginBrowsing();
        waiting.readAllowance(8192, () -> false, visible::get, hooks);
        assertEquals(64 * 1024, waiting.readAllowance(64 * 1024,
                () -> false, visible::get, hooks));
        assertEquals(1, pauses.get());
        assertEquals(pauses.get(), resumes.get());
    }

    @Test public void cancelledWaitAndThreadInterruptionDoNotConsumeOrLeakWaits() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        BrowsingPriorityPolicy waiting = new BrowsingPriorityPolicy(now::get,
                (lock, nanos) -> cancelled.set(true));
        waiting.setEnabled(true);
        waiting.setCellular(true);
        waiting.beginBrowsing();
        waiting.readAllowance(8192, cancelled::get, () -> false, hooks);
        assertThrows(IOException.class, () -> waiting.readAllowance(8192,
                cancelled::get, () -> false, hooks));
        assertEquals(pauses.get(), resumes.get());

        BrowsingPriorityPolicy interrupted = new BrowsingPriorityPolicy(now::get,
                (lock, nanos) -> { throw new InterruptedException(); });
        interrupted.setEnabled(true);
        interrupted.setCellular(true);
        interrupted.beginBrowsing();
        interrupted.readAllowance(8192, () -> false, () -> false, hooks);
        try {
            assertThrows(InterruptedIOException.class, () -> interrupted.readAllowance(8192,
                    () -> false, () -> false, hooks));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(pauses.get(), resumes.get());
        } finally {
            Thread.interrupted();
        }
    }
}
