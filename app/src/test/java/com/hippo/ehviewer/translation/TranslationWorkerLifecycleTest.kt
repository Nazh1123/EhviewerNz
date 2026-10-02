package com.hippo.ehviewer.translation

import android.app.Application
import android.content.Context
import android.os.Looper
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Real worker coroutines with gated GIF sources, without native models or API requests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class TranslationWorkerLifecycleTest {
    @Test fun modelMaintenanceWaitsForParkedModelsToCloseOffMain() {
        val first = acquire(Source("maintenance-a", CopyOnWriteArrayList()), 0)
        val second = acquire(Source("maintenance-b", CopyOnWriteArrayList()), 0)
        val closed = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for (session in listOf(first, second)) {
                val retained = ReflectionHelpers.getField<RetainedTranslationModels<AutoCloseable>>(session, "retainedModels")
                retained.park(AutoCloseable {
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    closed.incrementAndGet()
                })
            }
            val operation = scope.async {
                TranslationRuntime.withModelMaintenance {
                    assertTrue(TranslationRuntime.lock.isLocked)
                    assertEquals(2, closed.get())
                }
            }
            await { operation.isCompleted }
            runBlocking { operation.await() }
            assertNull(first.takeIdleModels())
            assertNull(second.takeIdleModels())
        } finally { scope.cancel(); release(first, second) }
    }

    @Test fun aDifferentGalleryReleasesParkedModelsBeforeReadingItsSource() {
        val parked = acquire(Source("parked-gallery", CopyOnWriteArrayList()), 0)
        val source = Source("next-gallery", CopyOnWriteArrayList(), blockFirst = true)
        val next = acquire(source, 0)
        val closed = AtomicInteger()
        try {
            val retained = ReflectionHelpers.getField<RetainedTranslationModels<AutoCloseable>>(parked, "retainedModels")
            retained.park(AutoCloseable { closed.incrementAndGet() })
            next.onPageChanged(0)
            await { source.saves.get() > 0 }
            assertEquals(1, closed.get())
        } finally { source.finish.countDown(); release(parked, next) }
    }

    @Test fun hiddenReaderFinishesItsActivePageAndLookaheadWithoutStoppingTheSource() {
        val source = Source("hidden", CopyOnWriteArrayList(), blockFirst = true)
        val session = acquire(source, ahead = 2)
        val reader = Reader()
        try {
            session.attach(reader)
            session.onPageChanged(3)
            await { source.saves.get() == 1 }
            session.detach(reader, false)
            assertTrue(session.enabled)
            assertTrue(session.readerOpen)
            assertEquals(0, source.stops.get())
            source.finish.countDown()
            await { !session.working && worker(session) == null }
            assertEquals(listOf("hidden:3", "hidden:4", "hidden:5"), source.order.toList())
            assertEquals(3, session.completed)
            assertTrue(session.enabled)
        } finally {
            source.finish.countDown()
            release(session)
        }
    }

    @Test fun readerExitCancelsTheActiveWorkerWithoutDispatchingLookahead() {
        val source = Source("exit", CopyOnWriteArrayList(), blockFirst = true)
        val session = acquire(source, ahead = 2)
        val reader = Reader()
        try {
            session.attach(reader)
            session.onPageChanged(3)
            await { source.saves.get() == 1 }
            session.detach(reader)
            assertFalse(session.enabled)
            assertFalse(session.readerOpen)
            assertEquals(1, source.stops.get())
            source.finish.countDown()
            await { worker(session) == null }
            assertEquals(listOf("exit:3"), source.order.toList())
            assertNotEquals(R.string.translation_animation, session.states[3])
            assertFalse(session.working)
        } finally {
            source.finish.countDown()
            release(session)
        }
    }

    @Test fun fullGalleryWorkerCompletesAfterTheReaderExits() {
        val source = Source("full-exit", CopyOnWriteArrayList(), blockFirst = true)
        val session = acquire(source, ahead = 2)
        val reader = Reader()
        val previousListener = TranslationTasks.completionListener
        var completions = 0
        TranslationTasks.completionListener = { completed, total, failed, _ ->
            assertSame(session, completed)
            assertEquals(8, total)
            assertEquals(0, failed)
            completions++
        }
        try {
            session.attach(reader)
            session.onPageChanged(3)
            ReflectionHelpers.setField(session, "fullGallery", true)
            await { source.saves.get() == 1 }
            session.detach(reader)
            assertTrue(session.enabled)
            assertFalse(session.readerOpen)
            assertEquals(0, source.stops.get())
            source.finish.countDown()
            await { !session.working && worker(session) == null }
            assertEquals(listOf(3, 4, 5, 6, 7, 0, 1, 2).map { "full-exit:$it" }, source.order.toList())
            assertEquals(8, session.states.values.count { it == R.string.translation_animation })
            assertEquals(1, completions)
        } finally {
            source.finish.countDown()
            release(session)
            TranslationTasks.completionListener = previousListener
        }
    }

    @Test fun stoppingBeforeIoDispatchDoesNotStrandTheWorkerWhenReenabled() {
        val source = Source("early-stop", CopyOnWriteArrayList())
        val session = acquire(source, ahead = 0)
        try {
            session.onPageChanged(0)
            session.disable()
            ReflectionHelpers.setField(session, "enabled", true)
            session.enqueue(0, false)
            await { !session.working && worker(session) == null }
            assertEquals(R.string.translation_animation, session.states[0])
            assertEquals(1, source.saves.get())
            assertEquals(source.starts.get(), source.stops.get())
        } finally { release(session) }
    }

    @Test fun waitingReaderStartsBeforeDetachedLookaheadAndDoesNotStartItsSourceEarly() {
        val order = CopyOnWriteArrayList<String>()
        val backgroundSource = Source("background", order, blockFirst = true)
        val readerSource = Source("reader", order)
        val background = acquire(backgroundSource, ahead = 2)
        val reader = acquire(readerSource, ahead = 0)
        try {
            background.onPageChanged(0)
            await { order.isNotEmpty() }
            reader.reader = Reader()
            reader.onPageChanged(4)
            await { ReflectionHelpers.getField<List<*>>(TranslationTasks.scheduler, "waiting").isNotEmpty() }
            assertEquals(0, readerSource.starts.get())
            assertEquals(listOf("background:0"), order.toList())
            backgroundSource.finish.countDown()
            await { !background.working && !reader.working }
            assertEquals(listOf("background:0", "reader:4", "background:1", "background:2"), order.toList())
            assertEquals(R.string.translation_animation, reader.states[4])
        } finally {
            backgroundSource.finish.countDown()
            release(background, reader)
        }
    }

    @Test fun disableThenReenableWaitsForOldCallAndClosesItsSourceBeforeReplacement() {
        val order = CopyOnWriteArrayList<String>()
        val source = Source("reopen", order, blockFirst = true)
        val session = acquire(source, ahead = 0)
        try {
            session.onPageChanged(0)
            await { order.isNotEmpty() }
            val oldWorker = worker(session)
            session.disable()
            assertSame(oldWorker, worker(session))
            assertEquals(1, source.stops.get())
            // Model preparation is bypassed only for this synthetic source test.
            ReflectionHelpers.setField(session, "enabled", true)
            session.enqueue(0, false)
            assertSame(oldWorker, worker(session))
            assertTrue(session.working)
            assertEquals(1, source.starts.get())
            source.finish.countDown()
            await { !session.working && worker(session) == null }
            assertEquals(2, source.starts.get())
            assertEquals(2, source.stops.get())
            assertEquals(listOf("reopen:0", "reopen:0"), order.toList())
            assertEquals(R.string.translation_animation, session.states[0])
        } finally {
            source.finish.countDown()
            release(session)
        }
    }

    @Test fun reenabledFullGalleryStillCompletesWhenAllPagesWereAlreadySettled() {
        val source = Source("full-reopen", CopyOnWriteArrayList(), blockFirst = true)
        val session = acquire(source, ahead = 0)
        var completions = 0
        val previousListener = TranslationTasks.completionListener
        TranslationTasks.completionListener = { completed, total, failed, _ ->
            assertSame(session, completed)
            assertEquals(8, total)
            assertEquals(0, failed)
            completions++
        }
        try {
            session.onPageChanged(0)
            await { source.saves.get() == 1 }
            session.disable()
            ReflectionHelpers.setField(session, "enabled", true)
            ReflectionHelpers.setField(session, "fullGallery", true)
            (0 until 8).forEach { session.states[it] = R.string.translation_no_text }
            session.enqueue(0, false)
            source.finish.countDown()
            await { !session.working && worker(session) == null }
            assertFalse(session.fullGallery)
            assertEquals(1, completions)
            assertEquals(1, source.saves.get())
        } finally {
            source.finish.countDown()
            release(session)
            TranslationTasks.completionListener = previousListener
        }
    }

    private fun acquire(source: Source, ahead: Int): GalleryTranslationSession =
        TranslationTasks.acquire(RuntimeEnvironment.getApplication(), Provider(source), null).also {
            it.activeOptions = TranslationOptions(ahead = ahead)
            ReflectionHelpers.setField(it, "enabled", true)
            ReflectionHelpers.setField(it, "activated", true)
        }

    private fun worker(session: GalleryTranslationSession) = ReflectionHelpers.getField<Job?>(session, "worker")
    private fun release(vararg sessions: GalleryTranslationSession) {
        sessions.forEach { TranslationTasks.release(it) }
        await { sessions.all { worker(it) == null } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Timed out waiting for translation worker", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private class Reader : GalleryTranslationSession.Reader {
        override fun changed() = Unit
        override fun hasTranslatedPage(page: Int) = false
        override fun display(page: Int, image: Image) = image.recycle()
    }

    private class Source(val identity: String, val order: MutableList<String>, val blockFirst: Boolean = false) {
        val finish = CountDownLatch(1)
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val saves = AtomicInteger()
    }

    private class Provider(private val source: Source) : GalleryProvider2() {
        override fun getTranslationIdentity() = source.identity
        override fun createTranslationProvider(context: Context) = Provider(source)
        override fun start() { super.start(); source.starts.incrementAndGet() }
        override fun stop() { super.stop(); source.stops.incrementAndGet() }
        override fun size() = 8
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index.gif"
        override fun save(index: Int, file: UniFile): Boolean {
            source.order.add("${source.identity}:$index")
            if (source.saves.incrementAndGet() == 1 && source.blockFirst) check(source.finish.await(5, TimeUnit.SECONDS))
            file.openOutputStream().use { it.write("GIF89a".toByteArray()) }
            return true
        }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
