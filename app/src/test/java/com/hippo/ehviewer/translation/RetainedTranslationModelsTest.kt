package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetainedTranslationModelsTest {
    private class Model : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun drainedQueueRetainsModelsAndResumedQueueReusesThem() = runBlocking {
        val lock = Mutex()
        val model = Model()
        val cache = RetainedTranslationModels<Model>(this, lock, { 0 }, { true }, 30)
        lock.withLock { cache.park(model) }
        cache.idle()
        cache.queued()
        val resumed = lock.withLock { cache.take() }
        assertSame(model, resumed)
        delay(60)
        assertEquals(0, model.closes.get())
        lock.withLock { cache.park(model) }
        cache.idle()
        withTimeout(2000) { while (model.closes.get() == 0) delay(5) }
        assertEquals(1, model.closes.get())
        assertNull(lock.withLock { cache.take() })
    }

    @Test fun anotherGalleryQueuePostponesExpiryWithoutHoldingInferenceLock() = runBlocking {
        val lock = Mutex()
        val model = Model()
        val checked = CompletableDeferred<Unit>()
        var otherQueue = true
        val cache = RetainedTranslationModels<Model>(this, lock, {
            checked.complete(Unit)
            if (otherQueue) 30 else 0
        }, { true }, 10)
        lock.withLock { cache.park(model) }
        cache.idle()
        checked.await()
        assertEquals(0, model.closes.get())
        assertTrue(lock.tryLock())
        lock.unlock()
        otherQueue = false
        withTimeout(2000) { while (model.closes.get() == 0) delay(5) }
        assertEquals(1, model.closes.get())
    }

    @Test fun disablingCannotCloseModelsWhileNativeWorkOwnsThemOrCloseTheirReplacement() = runBlocking {
        val lock = Mutex()
        val original = Model()
        val replacement = Model()
        val cache = RetainedTranslationModels<Model>(this, lock, { 0 }, { true }, 30)
        lock.withLock { cache.park(original) }
        lock.lock()
        cache.release()
        yield() // Disposal waits for the native lock.
        assertEquals(0, original.closes.get())
        assertSame(original, cache.take())
        cache.park(replacement)
        original.close()
        lock.unlock()
        yield()
        assertEquals(0, replacement.closes.get())
        cache.release()
        withTimeout(2000) { while (replacement.closes.get() == 0) delay(5) }
        assertEquals(1, original.closes.get())
        assertEquals(1, replacement.closes.get())
    }

    @Test fun backgroundModelsExpireWithoutWaitingForOtherQueues() = runBlocking {
        val model = Model()
        val lock = Mutex()
        val cache = RetainedTranslationModels<Model>(this, lock, { 1000 }, { false }, 10)
        lock.withLock { cache.park(model) }
        cache.idle()
        withTimeout(2000) { while (model.closes.get() == 0) delay(5) }
        assertEquals(1, model.closes.get())
    }

    @Test fun imageStagesReuseWeightsAndReopenAfterBackgroundRelease() {
        val created = mutableListOf<Model>()
        val stage = TranslationStageModel { Model().also { created.add(it) } }
        val first = stage.get()
        assertSame(first, stage.get())
        assertEquals(1, created.size)
        stage.close()
        stage.close()
        assertEquals(1, first.closes.get())
        val second = stage.get()
        assertNotSame(first, second)
        assertEquals(2, created.size)
        stage.close()
        assertEquals(1, second.closes.get())
    }
}
