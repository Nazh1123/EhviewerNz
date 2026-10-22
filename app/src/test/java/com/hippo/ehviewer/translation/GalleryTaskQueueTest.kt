package com.hippo.ehviewer.translation

import com.hippo.ehviewer.translation.GalleryPageWork.Stage
import com.hippo.ehviewer.translation.GalleryPageWork.Stage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GalleryTaskQueueTest {
    private class Gallery(identity: String, queue: GalleryTaskQueue) {
        val work = GalleryPageWork(identity, queue)
        val full = mutableSetOf<Stage>()
        val finished = Stage.entries.associateWith { mutableSetOf<Int>() }
        val foreground = Stage.entries.associateWith { mutableListOf<Int>() }
        private val registered = mutableMapOf<Stage, GalleryPageWork.Queue>()
        var ready = true
        init {
            Stage.entries.forEach { stage ->
                val stageQueue = GalleryPageWork.Queue(
                    active = { stage in full }, pages = { if (ready) listOf(0, 1, 2) else emptyList() },
                    settled = { it in finished.getValue(stage) }, ready = { ready },
                    fullGallery = { stage in full }, foregroundPages = { foreground.getValue(stage) },
                )
                registered[stage] = stageQueue
                work.register(stage, stageQueue)
            }
        }
        fun start(stage: Stage) { full.add(stage); work.changed() }
        fun stop(stage: Stage) { full.remove(stage); work.changed() }
        fun remove(stage: Stage) { work.remove(stage, registered.getValue(stage)) }
        fun finish(stage: Stage, page: Int) { finished.getValue(stage).add(page); work.changed() }
        suspend fun drain(stage: Stage) {
            while (true) finish(stage, work.await(stage) ?: break)
            stop(stage)
        }
    }

    @Test fun mixedStagesRunInGallerySubmissionOrder() = runBlocking {
        val queue = GalleryTaskQueue()
        // Creating/opening a gallery must not reserve a place before submission.
        val third = Gallery("C", queue)
        val first = Gallery("A", queue)
        val second = Gallery("B", queue)
        first.start(TRANSLATION); second.start(SUPER_RESOLUTION); third.start(TRANSLATION)
        val next = async(start = CoroutineStart.UNDISPATCHED) { second.work.await(SUPER_RESOLUTION) }
        val last = async(start = CoroutineStart.UNDISPATCHED) { third.work.await(TRANSLATION) }
        assertFalse(next.isCompleted); assertFalse(last.isCompleted)
        first.drain(TRANSLATION)
        assertEquals(0, withTimeout(1000) { next.await() })
        assertFalse(last.isCompleted)
        second.drain(SUPER_RESOLUTION)
        assertEquals(0, withTimeout(1000) { last.await() })
    }

    @Test fun addingTheOtherStageKeepsTheGallerySlotUntilBothStagesEnd() = runBlocking {
        val queue = GalleryTaskQueue()
        val first = Gallery("A", queue); val second = Gallery("B", queue)
        first.start(SUPER_RESOLUTION); second.start(TRANSLATION)
        first.start(TRANSLATION) // Join A rather than append a separate translation task after B.
        val next = async(start = CoroutineStart.UNDISPATCHED) { second.work.await(TRANSLATION) }
        val order = mutableListOf<String>()
        repeat(3) { page ->
            assertNull(first.work.next(SUPER_RESOLUTION))
            assertEquals(page, first.work.await(TRANSLATION))
            order += "T:$page"; first.finish(TRANSLATION, page)
            assertEquals(page, first.work.await(SUPER_RESOLUTION))
            order += "S:$page"; first.finish(SUPER_RESOLUTION, page)
        }
        first.stop(TRANSLATION)
        yield()
        assertFalse(next.isCompleted) // SR cleanup/completion has not released its stage yet.
        first.stop(SUPER_RESOLUTION)
        assertEquals(0, withTimeout(1000) { next.await() })
        assertEquals(listOf("T:0", "S:0", "T:1", "S:1", "T:2", "S:2"), order)
    }

    @Test fun visiblePagesBypassTheQueueWithoutChangingSubmissionOrder() = runBlocking {
        val queue = GalleryTaskQueue()
        val first = Gallery("A", queue); val second = Gallery("B", queue); val third = Gallery("C", queue)
        first.start(SUPER_RESOLUTION); second.start(TRANSLATION); second.start(SUPER_RESOLUTION)
        third.start(TRANSLATION)
        second.foreground.getValue(SUPER_RESOLUTION).add(1)
        second.work.changed()
        assertEquals(1, second.work.await(TRANSLATION)) // Shared visible-page admission preserves T -> SR.
        assertNull(second.work.next(SUPER_RESOLUTION))
        second.finish(TRANSLATION, 1)
        assertEquals(1, second.work.await(SUPER_RESOLUTION))
        second.finish(SUPER_RESOLUTION, 1)
        val background = async(start = CoroutineStart.UNDISPATCHED) { second.work.await(TRANSLATION) }
        assertFalse(background.isCompleted)
        assertFalse(second.work.admits(TRANSLATION, 0))
        assertEquals(0, first.work.next(SUPER_RESOLUTION))
        assertNull(third.work.next(TRANSLATION))
        second.foreground.getValue(SUPER_RESOLUTION).clear(); second.work.changed()
        first.drain(SUPER_RESOLUTION)
        assertEquals(0, withTimeout(1000) { background.await() })
        assertNull(third.work.next(TRANSLATION))
    }

    @Test fun cancelingOneStageKeepsTheOtherAndCancelingAQueuedGallerySkipsIt() = runBlocking {
        val queue = GalleryTaskQueue()
        val first = Gallery("A", queue); val second = Gallery("B", queue); val third = Gallery("C", queue)
        first.start(TRANSLATION); first.start(SUPER_RESOLUTION)
        second.start(SUPER_RESOLUTION); third.start(TRANSLATION)
        first.stop(TRANSLATION)
        assertEquals(0, first.work.await(SUPER_RESOLUTION))
        assertNull(second.work.next(SUPER_RESOLUTION)); assertNull(third.work.next(TRANSLATION))
        second.stop(SUPER_RESOLUTION)
        val next = async(start = CoroutineStart.UNDISPATCHED) { third.work.await(TRANSLATION) }
        assertFalse(next.isCompleted)
        first.stop(SUPER_RESOLUTION)
        assertEquals(0, withTimeout(1000) { next.await() })
        second.start(SUPER_RESOLUTION) // A resubmission joins the tail.
        assertNull(second.work.next(SUPER_RESOLUTION))
        third.stop(TRANSLATION)
        assertEquals(0, second.work.await(SUPER_RESOLUTION))
    }

    @Test fun unknownSourcesWaitForTheirTurnAndRemovingTheOwnerWakesWaiters() = runBlocking {
        val queue = GalleryTaskQueue()
        val first = Gallery("A", queue); val second = Gallery("B", queue)
        first.start(TRANSLATION); second.ready = false; second.start(SUPER_RESOLUTION)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { second.work.awaitAdmission(SUPER_RESOLUTION) }
        assertFalse(waiting.isCompleted)
        first.remove(TRANSLATION)
        withTimeout(1000) { waiting.await() }
        second.ready = true; second.work.changed()
        assertEquals(0, second.work.await(SUPER_RESOLUTION))
    }

    @Test fun canceledPageWaiterDoesNotReleaseItsGalleryAndVisibleAdmissionTracksNavigation() = runBlocking {
        val queue = GalleryTaskQueue()
        val first = Gallery("A", queue); val second = Gallery("B", queue)
        first.start(TRANSLATION); second.start(SUPER_RESOLUTION)
        val canceled = async(start = CoroutineStart.UNDISPATCHED) { second.work.await(SUPER_RESOLUTION) }
        canceled.cancelAndJoin()
        assertNull(second.work.next(SUPER_RESOLUTION))
        second.foreground.getValue(SUPER_RESOLUTION).add(2); second.work.changed()
        assertEquals(2, second.work.await(SUPER_RESOLUTION))
        second.foreground.getValue(SUPER_RESOLUTION).clear(); second.work.changed()
        assertNull(second.work.next(SUPER_RESOLUTION))
        first.stop(TRANSLATION)
        assertEquals(0, second.work.await(SUPER_RESOLUTION))
    }
}
