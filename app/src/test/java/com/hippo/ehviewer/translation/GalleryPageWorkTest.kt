package com.hippo.ehviewer.translation

import com.hippo.ehviewer.translation.GalleryPageWork.Stage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GalleryPageWorkTest {
    private class Fixture {
        val work = GalleryPageWork()
        var translation = listOf(0, 1, 2)
        var sr = listOf(0, 1, 2)
        var translationEnabled = true
        var srEnabled = true
        val translated = mutableSetOf<Int>()
        val enhanced = mutableSetOf<Int>()
        val translationQueue = GalleryPageWork.Queue({ translationEnabled }, { translation }, { it in translated })
        val srQueue = GalleryPageWork.Queue({ srEnabled }, { sr }, { it in enhanced })
        init { work.register(TRANSLATION, translationQueue); work.register(SUPER_RESOLUTION, srQueue) }
        fun finish(stage: GalleryPageWork.Stage, page: Int) {
            (if (stage == TRANSLATION) translated else enhanced).add(page)
            work.changed()
        }
        suspend fun drain(): List<String> = coroutineScope {
            val order = mutableListOf<String>()
            val jobs = GalleryPageWork.Stage.entries.map { stage -> launch {
                while (true) {
                    val page = work.await(stage) ?: break
                    order += "$stage:$page"
                    yield() // A slow stage must keep the other worker off the next page.
                    finish(stage, page)
                }
            } }
            withTimeout(2000) { jobs.joinAll() }
            order
        }
    }

    @Test fun bothWorkersFinishEachPageBeforeAdmittingTheNext() = runBlocking {
        assertEquals(listOf("TRANSLATION:0", "SUPER_RESOLUTION:0", "TRANSLATION:1",
            "SUPER_RESOLUTION:1", "TRANSLATION:2", "SUPER_RESOLUTION:2"), Fixture().drain())
    }

    @Test fun independentlySizedWindowsDoNotCreatePhantomStages() = runBlocking {
        val longerSr = Fixture().apply { translation = listOf(0) }
        assertEquals(listOf("TRANSLATION:0", "SUPER_RESOLUTION:0", "SUPER_RESOLUTION:1", "SUPER_RESOLUTION:2"), longerSr.drain())
        val longerTranslation = Fixture().apply { sr = listOf(0) }
        assertEquals(listOf("TRANSLATION:0", "SUPER_RESOLUTION:0", "TRANSLATION:1", "TRANSLATION:2"), longerTranslation.drain())
    }

    @Test fun cachedTranslationStillWaitsForItsUnfinishedSrBeforeNextTranslation() = runBlocking {
        val f = Fixture().apply { translated.add(0) }
        assertEquals(listOf("SUPER_RESOLUTION:0", "TRANSLATION:1", "SUPER_RESOLUTION:1",
            "TRANSLATION:2", "SUPER_RESOLUTION:2"), f.drain())
    }

    @Test fun disablingOrRemovingTheOtherQueueReleasesACancellableWait() = runBlocking {
        val f = Fixture()
        f.finish(TRANSLATION, 0)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { f.work.await(TRANSLATION) }
        assertFalse(waiting.isCompleted)
        f.srEnabled = false; f.work.changed()
        assertEquals(1, withTimeout(1000) { waiting.await() })
        f.srEnabled = true; f.work.changed()
        val canceled = async(start = CoroutineStart.UNDISPATCHED) { f.work.await(TRANSLATION) }
        canceled.cancelAndJoin()
        f.work.remove(SUPER_RESOLUTION, f.srQueue)
        assertEquals(1, f.work.await(TRANSLATION))
    }

    @Test fun navigationDropsObsoletePageAndFailureCountsAsACompletedStage() = runBlocking {
        val f = Fixture()
        assertEquals(0, f.work.await(TRANSLATION))
        f.translation = listOf(2); f.sr = listOf(2); f.work.changed()
        assertEquals(2, f.work.await(TRANSLATION))
        f.finish(TRANSLATION, 2) // terminal failed/skipped statuses use the same settled predicate
        assertEquals(2, f.work.await(SUPER_RESOLUTION))
        f.finish(SUPER_RESOLUTION, 2)
        assertNull(f.work.await(TRANSLATION)); assertNull(f.work.await(SUPER_RESOLUTION))
    }

    @Test fun fullGalleryWrapOrderAndSeparateGalleryIdentitiesArePreserved() = runBlocking {
        val f = Fixture().apply { translation = listOf(2, 0, 1); sr = listOf(2, 0, 1) }
        assertEquals(listOf("TRANSLATION:2", "SUPER_RESOLUTION:2", "TRANSLATION:0",
            "SUPER_RESOLUTION:0", "TRANSLATION:1", "SUPER_RESOLUTION:1"), f.drain())
        val other = Fixture()
        assertEquals(0, other.work.await(TRANSLATION))
        assertFalse(other.translated.contains(2))
    }

    @Test fun unknownOtherWindowIsInitializedBeforeAdmittingTheFirstPage() = runBlocking {
        val f = Fixture()
        var known = false
        f.work.register(SUPER_RESOLUTION, GalleryPageWork.Queue({ true }, { if (known) listOf(0, 1) else emptyList() },
            { it in f.enhanced }, { known }))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { f.work.await(TRANSLATION) }
        assertFalse(waiting.isCompleted)
        known = true; f.work.changed()
        assertEquals(0, withTimeout(1000) { waiting.await() })
        f.finish(TRANSLATION, 0)
        assertEquals(0, f.work.await(SUPER_RESOLUTION))
        assertNull(f.work.next(TRANSLATION))
    }
}
