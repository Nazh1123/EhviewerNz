package com.hippo.ehviewer.gallery;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ReadingProgressCacheTest {
    @Test public void overlappingBindingsShareOneReadAndThenUseCache() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        AtomicInteger reads = new AtomicInteger();
        List<Integer> values = new ArrayList<>();
        ReadingProgressCache cache = new ReadingProgressCache(jobs::add,
                gid -> { reads.incrementAndGet(); return 12; });
        cache.request(1L, values::add);
        cache.request(1L, values::add);
        assertEquals(1, jobs.size());
        jobs.remove().run();
        cache.request(1L, values::add);
        assertEquals(List.of(12, 12, 12), values);
        assertEquals(1, reads.get());
        assertTrue(jobs.isEmpty());
    }

    @Test public void recycledHoldersDoNotReadFromDisk() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        ReadingProgressCache cache = new ReadingProgressCache(jobs::add,
                gid -> { fail("Cancelled binding must not access storage"); return 0; });
        cache.request(1L, page -> fail("Cancelled callback")).cancel();
        jobs.remove().run();
    }

    @Test public void cancellingOneHolderPreservesOtherSubscribers() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        List<Integer> values = new ArrayList<>();
        ReadingProgressCache cache = new ReadingProgressCache(jobs::add, gid -> 7);
        cache.request(1L, page -> fail("Cancelled callback")).cancel();
        cache.request(1L, values::add);
        jobs.remove().run();
        assertEquals(List.of(7), values);
    }

    @Test public void returnFromReaderInvalidatesPendingAndCachedValues() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        AtomicInteger currentPage = new AtomicInteger(2);
        List<Integer> values = new ArrayList<>();
        ReadingProgressCache cache = new ReadingProgressCache(jobs::add, gid -> currentPage.get());
        cache.request(1L, page -> fail("Old list callback"));
        cache.clear();
        currentPage.set(19);
        cache.request(1L, values::add);
        while (!jobs.isEmpty()) jobs.remove().run();
        assertEquals(List.of(19), values);
        cache.clear();
        currentPage.set(5);
        cache.request(1L, values::add);
        jobs.remove().run();
        assertEquals(List.of(19, 5), values);
    }

    @Test public void invalidatedInFlightReadCannotOverwriteNewResult() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        ReadingProgressCache[] cache = new ReadingProgressCache[1];
        AtomicInteger reads = new AtomicInteger();
        List<Integer> values = new ArrayList<>();
        cache[0] = new ReadingProgressCache(jobs::add, gid -> {
            if (reads.getAndIncrement() == 0) {
                cache[0].clear();
                cache[0].request(gid, values::add);
                return 3;
            }
            return 17;
        });
        cache[0].request(1L, page -> fail("Invalidated read completed"));
        while (!jobs.isEmpty()) jobs.remove().run();
        cache[0].request(1L, values::add);
        assertEquals(List.of(17, 17), values);
    }

    @Test public void expiresAndBoundsStoredEntries() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        ReadingProgressCache cache = new ReadingProgressCache(Runnable::run,
                gid -> reads.incrementAndGet(), clock::get);
        cache.request(1, page -> {});
        clock.set(TimeUnit.SECONDS.toNanos(6));
        cache.request(1, page -> {});
        assertEquals(2, reads.get());
        for (long gid = 2; gid <= 257; gid++) cache.request(gid, page -> {});
        cache.request(1, page -> {});
        assertEquals(259, reads.get());
    }

    @Test public void failureIsNotCachedAsUnread() {
        AtomicInteger attempts = new AtomicInteger();
        ReadingProgressCache cache = new ReadingProgressCache(Runnable::run, gid -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("storage offline");
            return 4;
        });
        List<Integer> values = new ArrayList<>();
        cache.request(1, values::add);
        cache.request(1, values::add);
        assertEquals(List.of(0, 4), values);
    }
}
