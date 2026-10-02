package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationPageRequestTest {
    @Test fun leavingWindowCancelsApiWithoutCancellingWorkerAndNextPageCanRun() = runBlocking<Unit> {
        val old = TranslationPageRequest(2, false)
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val worker = async {
            try {
                old.apiCall {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                fail("Old page must be abandoned")
            } catch (_: SupersededTranslationPage) { }
            assertTrue(currentCoroutineContext().isActive)
            TranslationPageRequest(19, false).apiCall { "page 20" }
        }
        entered.await()
        old.supersede()
        withTimeout(1000) {
            stopped.await()
            assertEquals("page 20", worker.await())
        }
    }

    @Test fun pageSupersededDuringNativeWorkNeverStartsApi() = runBlocking<Unit> {
        val page = TranslationPageRequest(2, false)
        page.supersede()
        var calls = 0
        try {
            page.apiCall { calls++; "unused" }
            fail("An obsolete page must not start translating")
        } catch (_: SupersededTranslationPage) { }
        assertEquals(0, calls)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun cancellationOfReaderStillPropagatesNormally() = runBlocking<Unit> {
        val page = TranslationPageRequest(2, false)
        val entered = CompletableDeferred<Unit>()
        var superseded = false
        val worker = launch {
            try {
                page.apiCall { entered.complete(Unit); awaitCancellation() }
            } catch (_: SupersededTranslationPage) { superseded = true }
        }
        entered.await()
        withTimeout(1000) { worker.cancelAndJoin() }
        assertTrue(worker.isCancelled)
        assertFalse(superseded)
    }
}
