package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class TranslationPagePipelineTest {
    @Test fun coldPageRunsAloneThenPagesOverlapAndRefillOnlyFreeSlots() = runBlocking<Unit> {
        withTimeout(3000) {
            var nextPage = 0
            var warm = false
            val starts = Channel<Int>(Channel.UNLIMITED)
            val gates = List(6) { CompletableDeferred<Unit>() }
            val worker = async {
                runTranslationPages(3, { warm }, { (nextPage++).takeIf { it < 6 } }) { page ->
                    starts.send(page)
                    gates[page].await()
                    warm = true
                }
            }
            assertEquals(0, starts.receive())
            yield()
            assertTrue(starts.tryReceive().isFailure)
            gates[0].complete(Unit)
            assertEquals(setOf(1, 2, 3), List(3) { starts.receive() }.toSet())
            assertTrue(starts.tryReceive().isFailure)
            // Page 2 can finish before page 1; its slot immediately admits page 4.
            gates[2].complete(Unit)
            assertEquals(4, starts.receive())
            gates[4].complete(Unit)
            assertEquals(5, starts.receive())
            gates.forEach { it.complete(Unit) }
            worker.await()
        }
    }

    @Test fun sequentialBackendsNeverOverlapEvenOnAWarmEngine() = runBlocking<Unit> {
        withTimeout(3000) {
            var nextPage = 0
            val starts = Channel<Int>(Channel.UNLIMITED)
            val gates = List(3) { CompletableDeferred<Unit>() }
            val worker = async {
                runTranslationPages(1, { true }, { (nextPage++).takeIf { it < 3 } }) { page ->
                    starts.send(page)
                    gates[page].await()
                }
            }
            for (page in 0..2) {
                assertEquals(page, starts.receive())
                yield()
                assertTrue(starts.tryReceive().isFailure)
                gates[page].complete(Unit)
            }
            worker.await()
        }
    }

    @Test fun supersedingOneConcurrentRequestCancelsOnlyItsHttpCall() = runBlocking<Unit> {
        withTimeout(3000) {
            val pages = List(2) { TranslationPageRequest(it, false) }
            val pending = pages.iterator()
            val starts = Channel<Int>(Channel.UNLIMITED)
            val cancelled = CompletableDeferred<Unit>()
            val secondResponse = CompletableDeferred<String>()
            val responses = mutableMapOf<Int, String>()
            val worker = async {
                runTranslationPages(2, { true }, { if (pending.hasNext()) pending.next() else null }) { request ->
                    withContext(request) {
                        // The shared translator must obtain the caller's page, including across IO dispatch.
                        val active = withContext(Dispatchers.Default) {
                            checkNotNull(currentCoroutineContext()[TranslationPageRequest])
                        }
                        try {
                            responses[active.page] = active.apiCall {
                                starts.send(active.page)
                                if (active.page == 0) try { awaitCancellation() }
                                finally { cancelled.complete(Unit) }
                                else secondResponse.await()
                            }
                        } catch (_: SupersededTranslationPage) { }
                    }
                }
            }
            assertEquals(setOf(0, 1), List(2) { starts.receive() }.toSet())
            pages[0].supersede()
            cancelled.await()
            assertTrue(worker.isActive)
            secondResponse.complete("second page")
            worker.await()
            assertEquals(mapOf(1 to "second page"), responses)
        }
    }

    @Test fun cancellationWaitsForEveryAdmittedPagesNativeCleanup() = runBlocking<Unit> {
        withTimeout(3000) {
            var nextPage = 0
            val starts = Channel<Int>(Channel.UNLIMITED)
            val cleaning = Channel<Int>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val cleaned = mutableSetOf<Int>()
            val worker = launch {
                runTranslationPages(2, { true }, { nextPage++ }) { page ->
                    starts.send(page)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            cleaning.send(page)
                            release.await()
                            cleaned.add(page)
                        }
                    }
                }
            }
            assertEquals(setOf(0, 1), List(2) { starts.receive() }.toSet())
            worker.cancel()
            assertEquals(setOf(0, 1), List(2) { cleaning.receive() }.toSet())
            assertFalse(worker.isCompleted)
            assertEquals(2, nextPage)
            release.complete(Unit)
            worker.join()
            assertEquals(setOf(0, 1), cleaned)
        }
    }

    @Test fun yieldingStopsAdmissionAndDrainsAlreadyRunningPages() = runBlocking<Unit> {
        withTimeout(3000) {
            var nextPage = 0
            var yielded = false
            val starts = Channel<Int>(Channel.UNLIMITED)
            val gates = List(2) { CompletableDeferred<Unit>() }
            val worker = async {
                runTranslationPages(2, { true }, { if (yielded) null else nextPage++ }) { page ->
                    starts.send(page)
                    gates[page].await()
                }
            }
            repeat(2) { starts.receive() }
            yielded = true
            gates[1].complete(Unit)
            yield()
            assertFalse(worker.isCompleted)
            assertEquals(2, nextPage)
            gates[0].complete(Unit)
            worker.await()
            assertEquals(2, nextPage)
        }
    }
}
