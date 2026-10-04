package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationImageWorkTest {
    @Test fun supersededOcrFinishesOnlyItsCurrentBatchBeforeReleasingNativeWork() = runBlocking {
        val request = TranslationPageRequest(0, false)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val recognized = mutableListOf<Int>()
        val work = async(request) {
            runCatching {
                withContext(NonCancellable) {
                    recognizeTranslationBatches((0 until 20).toList(), 4) { batch ->
                        started.complete(Unit)
                        finish.await()
                        recognized.addAll(batch)
                    }
                }
            }.exceptionOrNull()
        }
        started.await()
        request.supersede()
        assertFalse("Native work must retain its images until it returns", work.isCompleted)
        finish.complete(Unit)
        assertTrue(work.await() is SupersededTranslationPage)
        assertEquals(listOf(0, 1, 2, 3), recognized)
    }

    @Test fun supersededImageWaiterNeverStartsAndLatestPageStillRuns() = runBlocking {
        val gate = TranslationImageWork()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val first = async {
            gate.run {
                entered.add(0)
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        val obsolete = TranslationPageRequest(1, false)
        val skipped = async(obsolete, start = CoroutineStart.UNDISPATCHED) {
            runCatching { withContext(NonCancellable) { gate.run { entered.add(1) } } }.exceptionOrNull()
        }
        val latest = async(TranslationPageRequest(5, false), start = CoroutineStart.UNDISPATCHED) {
            gate.run { entered.add(5) }
        }
        obsolete.supersede()
        finish.complete(Unit)
        first.await()
        assertTrue(skipped.await() is SupersededTranslationPage)
        latest.await()
        assertEquals(listOf(0, 5), entered)
    }

    @Test fun cancelledDecodesFinishSafelyAndCancelledWaitersDoNotMultiplyWork() = runBlocking {
        val gate = TranslationImageWork()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val first = launch {
            gate.run {
                entered.add(0)
                started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
            }
        }
        started.await()
        first.cancel()
        val cancelled = (1..25).map { page ->
            launch(start = CoroutineStart.UNDISPATCHED) { gate.run { entered.add(page) } }.also { it.cancel() }
        }
        val latest = async(start = CoroutineStart.UNDISPATCHED) { gate.run { entered.add(26) } }
        yield()
        assertFalse(latest.isCompleted)
        assertEquals(listOf(0), entered)
        finish.complete(Unit)
        first.join()
        cancelled.joinAll()
        latest.await()
        assertEquals(listOf(0, 26), entered)
    }

    @Test fun navigationWaitTracksTheEndOfTheBurstAndBackgroundWorkBypassesIt() = runBlocking {
        var now = 1000L
        val waits = mutableListOf<Long>()
        lateinit var settler: TranslationNavigationSettler
        settler = TranslationNavigationSettler({ now }) { duration ->
            waits.add(duration)
            now += duration
            if (waits.size == 1) settler.pageChanged()
        }
        settler.pageChanged()
        settler.await { true }
        assertEquals(listOf(150L, 150L), waits)
        settler.pageChanged()
        settler.await { false }
        assertEquals(2, waits.size)
    }
}
