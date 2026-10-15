package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImageWorkPriorityTest {
    @Test fun translationOvertakesQueuedEnhancementAndExposesPreemption() = runBlocking {
        val gate = ImageWorkPriority()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(false) { order += "sr1"; release.await() }
        }
        val sr = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(false) { order += "sr2" } }
        val translation = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(true) { order += "translation" } }
        assertTrue(gate.translationPending)
        release.complete(Unit)
        joinAll(running, sr, translation)
        assertEquals(listOf("sr1", "translation", "sr2"), order)
        assertFalse(gate.translationPending)
    }

    @Test fun canceledTranslationDoesNotKeepEnhancementPaused() = runBlocking {
        val gate = ImageWorkPriority()
        gate.run(false) {
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(true) { fail("Canceled request admitted") } }
            assertTrue(gate.translationPending)
            waiting.cancelAndJoin()
            assertFalse(gate.translationPending)
        }
        withTimeout(1000) { gate.run(false) { } }
    }

    @Test fun cancellationDuringGrantAndOwnerFailureReleaseTheSlot() = runBlocking {
        val gate = ImageWorkPriority()
        lateinit var canceled: Job
        gate.run(false) { canceled = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(true) { fail("Canceled grant ran") } } }
        canceled.cancelAndJoin()
        assertFalse(gate.translationPending)
        runCatching { gate.run(true) { error("failed") } }
        withTimeout(1000) { gate.run(false) { } }
    }

    @Test fun nativeTileYieldRequeuesAfterTranslationWithoutLeakingAdmission() = runBlocking {
        val gate = ImageWorkPriority()
        val order = mutableListOf<String>()
        lateinit var translation: Job
        try {
            gate.run(false) {
                order += "tile"
                translation = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(true) { order += "translation" } }
                if (gate.translationPending) throw CancellationException("yield")
            }
        } catch (_: CancellationException) { }
        gate.run(false) { order += "resumed" }
        translation.join()
        assertEquals(listOf("tile", "translation", "resumed"), order)
    }
}
