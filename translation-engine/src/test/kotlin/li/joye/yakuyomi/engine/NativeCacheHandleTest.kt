package li.joye.yakuyomi.engine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeCacheHandleTest {
    @Test fun modelLoadDoesNotBlockCacheClear() {
        val released = CopyOnWriteArrayList<Long>()
        val owner = NativeCacheHandle(1, { 2 }, { released.add(it) })
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val model = executor.submit<Long> {
                owner.withLease { borrowed ->
                    loading.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    borrowed
                }
            }
            assertTrue(loading.await(1, TimeUnit.SECONDS))
            // Main's trimMemory must be able to clear snapshots while GGUF IO is blocked.
            assertEquals(1L, executor.submit<Long> { owner.withHandle { it } }.get(1, TimeUnit.SECONDS))
            assertFalse(model.isDone)
            finish.countDown()
            assertEquals(2L, model.get(1, TimeUnit.SECONDS))
            assertEquals(listOf(2L), released.toList())
        } finally {
            finish.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            owner.close()
        }
    }

    @Test fun closingCacheDuringModelLoadKeepsTheBorrowedReferenceUntilReturn() {
        val released = CopyOnWriteArrayList<Long>()
        val owner = NativeCacheHandle(1, { 2 }, { released.add(it) })
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val model = executor.submit<Long> {
                owner.withLease { borrowed ->
                    loading.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    assertEquals(listOf(1L), released.toList())
                    borrowed
                }
            }
            assertTrue(loading.await(1, TimeUnit.SECONDS))
            executor.submit { owner.close(); owner.close() }.get(1, TimeUnit.SECONDS)
            assertEquals(listOf(1L), released.toList())
            assertThrows(IllegalStateException::class.java) { owner.withHandle { it } }
            finish.countDown()
            assertEquals(2L, model.get(1, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), released.toList())
        } finally {
            finish.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            owner.close()
        }
    }

    @Test fun failedModelLoadReleasesItsBorrowedReferenceAndLeavesTheCacheUsable() {
        val released = mutableListOf<Long>()
        val owner = NativeCacheHandle(1, { 2 }, { released.add(it) })
        assertThrows(IllegalArgumentException::class.java) {
            owner.withLease<Unit> { throw IllegalArgumentException("Model load failed") }
        }
        assertEquals(listOf(2L), released)
        assertEquals(1L, owner.withHandle { it })
        owner.close()
        owner.close()
        assertEquals(listOf(2L, 1L), released)
        assertThrows(IllegalStateException::class.java) { owner.withLease { it } }
    }
}
