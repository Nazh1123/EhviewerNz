package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationSchedulerTest {
    @Test fun currentPageOvertakesPreviouslyQueuedBackgroundWork() = runBlocking {
        val scheduler = TranslationScheduler()
        val owner = scheduler.acquire { 2 }
        val background = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 2 } }
        val current = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 0 } }
        assertFalse(background.isCompleted)
        assertFalse(current.isCompleted)
        assertTrue(scheduler.shouldYield(owner))
        scheduler.release(owner)
        val reader = current.await()
        assertFalse(background.isCompleted)
        scheduler.release(reader)
        scheduler.release(background.await())
    }

    @Test fun pageBoundaryReevaluatesNavigationAndSettledReaderPriority() = runBlocking {
        val scheduler = TranslationScheduler()
        var readerPriority = 0
        val reader = scheduler.acquire { readerPriority }
        val background = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 2 } }
        repeat(8) { scheduler.pageFinished(reader) }
        assertFalse(scheduler.shouldYield(reader))
        readerPriority = 3 // All reader pages completed or restored independently.
        assertTrue(scheduler.shouldYield(reader))
        scheduler.release(reader)
        scheduler.release(background.await())
    }

    @Test fun navigationReordersAlreadySuspendedWorkers() = runBlocking {
        val scheduler = TranslationScheduler()
        val owner = scheduler.acquire { 2 }
        var newPriority = 2
        val first = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 1 } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { newPriority } }
        newPriority = 0
        scheduler.release(owner)
        val next = second.await()
        assertFalse(first.isCompleted)
        scheduler.release(next)
        scheduler.release(first.await())
    }

    @Test fun equalPriorityGalleriesYieldAfterABoundedNumberOfPages() = runBlocking {
        val scheduler = TranslationScheduler(pageQuantum = 4)
        val first = scheduler.acquire { 2 }
        val second = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 2 } }
        repeat(3) {
            scheduler.pageFinished(first)
            assertFalse(scheduler.shouldYield(first))
        }
        scheduler.pageFinished(first)
        assertTrue(scheduler.shouldYield(first))
        scheduler.release(first)
        scheduler.release(second.await())
    }

    @Test fun singleGalleryKeepsItsModelsAcrossPages() = runBlocking {
        val scheduler = TranslationScheduler()
        val turn = scheduler.acquire { 2 }
        repeat(100) {
            scheduler.pageFinished(turn)
            assertFalse(scheduler.shouldYield(turn))
        }
        scheduler.release(turn)
    }

    @Test fun cancellingAWaitingWorkerDoesNotConsumeTheSlot() = runBlocking {
        val scheduler = TranslationScheduler()
        val owner = scheduler.acquire { 2 }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 0 } }
        val survivor = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 2 } }
        cancelled.cancelAndJoin()
        assertFalse(scheduler.shouldYield(owner))
        scheduler.release(owner)
        scheduler.release(withTimeout(1000) { survivor.await() })
    }

    @Test fun cancellationDuringHandoffReleasesTheGrantedSlot() = runBlocking {
        val scheduler = TranslationScheduler()
        val owner = scheduler.acquire { 2 }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 0 } }
        val survivor = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 2 } }
        scheduler.release(owner)
        cancelled.cancelAndJoin()
        scheduler.release(withTimeout(1000) { survivor.await() })
    }

    @Test fun releasingAnOldTurnCannotReleaseTheNextOwner() = runBlocking {
        val scheduler = TranslationScheduler()
        val old = scheduler.acquire { 2 }
        scheduler.release(old)
        val owner = scheduler.acquire { 2 }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { scheduler.acquire { 0 } }
        scheduler.release(old)
        assertFalse(waiting.isCompleted)
        scheduler.release(owner)
        scheduler.release(waiting.await())
    }
}
