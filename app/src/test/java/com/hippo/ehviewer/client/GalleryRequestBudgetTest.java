package com.hippo.ehviewer.client;

import static org.junit.Assert.*;
import com.hippo.ehviewer.client.exception.CancelledException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GalleryRequestBudgetTest {
    @Test public void eightTasksStartTogetherAndAllSourcesShareTheLimit() throws Exception {
        GalleryRequestBudget budget = new GalleryRequestBudget(8);
        ExecutorService workers = Executors.newFixedThreadPool(16);
        CountDownLatch firstEight = new CountDownLatch(8);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) jobs.add(workers.submit(() -> {
                try {
                    budget.acquire(() -> false);
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    firstEight.countDown();
                    finish.await();
                    active.decrementAndGet();
                    budget.release();
                } catch (Exception error) { throw new AssertionError(error); }
            }));
            assertTrue("No per-request interval may serialize the first eight starts",
                    firstEight.await(1, TimeUnit.SECONDS));
            assertEquals(8, active.get());
            finish.countDown();
            for (Future<?> job : jobs) job.get(2, TimeUnit.SECONDS);
            assertEquals(8, maximum.get());
            assertEquals(0, active.get());
        } finally { finish.countDown(); workers.shutdownNow(); }
    }

    @Test public void cancellingAQueuedTaskDoesNotConsumeAPermit() throws Exception {
        GalleryRequestBudget budget = new GalleryRequestBudget(1);
        budget.acquire(() -> false);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = worker.submit(() -> {
                try { budget.acquire(cancelled::get); return false; }
                catch (CancelledException expected) { return true; }
            });
            cancelled.set(true);
            assertTrue(result.get(1, TimeUnit.SECONDS));
            budget.release();
            Future<?> resumed = worker.submit(() -> {
                try { budget.acquire(() -> false); budget.release(); }
                catch (CancelledException error) { throw new AssertionError(error); }
            });
            resumed.get(1, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    @Test public void ordinaryAndSubscriptionListRequestsShareTheSameBudget() {
        for (int method : new int[]{EhClient.METHOD_GET_GALLERY_LIST, EhClient.METHOD_GET_GALLERY_LIST_HTML,
                EhClient.METHOD_PREPARE_GALLERY_LIST, EhClient.METHOD_PREPARE_SUBSCRIPTION_LIST,
                EhClient.METHOD_GET_DISPLAY_METADATA}) assertTrue(GalleryRequestBudget.appliesTo(method));
        assertFalse(GalleryRequestBudget.appliesTo(EhClient.METHOD_GET_GALLERY_DETAIL));
    }
}
