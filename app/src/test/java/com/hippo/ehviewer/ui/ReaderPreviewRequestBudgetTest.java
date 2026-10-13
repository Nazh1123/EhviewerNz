package com.hippo.ehviewer.ui;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class ReaderPreviewRequestBudgetTest {
    @Test public void burstIsLimitedAndReopensAtExactBoundary() {
        ReaderPreviewRequestBudget budget = new ReaderPreviewRequestBudget(8, 5000);
        for (int i = 0; i < 8; i++) assertTrue(budget.acquire(100));
        assertFalse(budget.acquire(100));
        assertEquals(1, budget.retryAfter(5099));
        assertFalse(budget.acquire(5099));
        assertTrue(budget.acquire(5100));
    }

    @Test public void slidingWindowDoesNotResetAllSlotsAtOnce() {
        ReaderPreviewRequestBudget budget = new ReaderPreviewRequestBudget(2, 5000);
        assertTrue(budget.acquire(0));
        assertTrue(budget.acquire(1000));
        assertTrue(budget.acquire(5000));
        assertFalse(budget.acquire(5000));
        assertEquals(1000, budget.retryAfter(5000));
    }

    @Test public void networkWorkersShareOneAtomicBudget() throws Exception {
        ReaderPreviewRequestBudget budget = new ReaderPreviewRequestBudget(8, 5000);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(80);
        try {
            for (int i = 0; i < 80; i++) workers.execute(() -> {
                if (budget.acquire(100)) granted.incrementAndGet();
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(8, granted.get());
        } finally { workers.shutdownNow(); }
    }

}
