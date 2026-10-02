package com.hippo.ehviewer.translation

import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in service integration using synthetic animated sources; no gallery downloads or API calls. */
@RunWith(AndroidJUnit4::class)
class BackgroundTranslationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun ordinaryModeFinishesItsWindowWhenTheReaderIsTemporarilyHidden() = withSession { session, source ->
        val taskNoticeId = onMainResult { TranslationTasks.notificationId(session) }
        onMain {
            session.reader = Reader()
            session.onPageChanged(3)
            assertTrue(session.enable())
        }
        await { source.order.isNotEmpty() && notices().any { it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 } }
        onMain { session.detach(session.reader!!, false) }
        // Exercise real background execution; the logical reader remains open after HOME.
        backgroundApp()
        source.permits.release(8)
        await { onMainResult { !session.working } }
        val end = minOf(7, 3 + session.activeOptions.ahead)
        assertEquals((3..end).toList(), source.order.toList())
        onMain {
            assertTrue(session.enabled)
            assertTrue(session.readerOpen)
            assertNull(session.reader)
        }
        await { notices().none { it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 } }
        assertTrue("Ordinary windows must not post a gallery completion notice", notices().none { it.id == taskNoticeId })
    }

    @Test fun ordinaryModeStopsOnReaderExitWithoutStartingTheRemainingWindow() = withSession { session, source ->
        onMain {
            session.reader = Reader()
            session.onPageChanged(3)
            assertTrue(session.enable())
        }
        await { source.order.isNotEmpty() }
        onMain { session.detach(session.reader!!) }
        await { source.stopped && onMainResult { !session.enabled && !session.working } }
        assertEquals(listOf(3), source.order.toList())
        assertFalse(onMainResult { session.readerOpen })
        await { notices().none { it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 } }
    }

    @Test fun fullModeSurvivesReaderRemovalAndCompletesFromTheReadingPosition() = withSession { session, source ->
        onMain {
            session.reader = Reader()
            session.onPageChanged(3)
            assertTrue(session.translateFullGallery())
        }
        await { source.order.isNotEmpty() && notices().isNotEmpty() }
        onMain {
            session.detach(session.reader!!)
            assertTrue(session.enabled)
        }
        backgroundApp()
        source.permits.release(8)
        await { onMainResult { !session.working } }
        assertEquals(listOf(3, 4, 5, 6, 7, 0, 1, 2), source.order.toList())
        onMain {
            assertTrue(session.enabled)
            assertFalse(session.fullGallery)
            assertEquals(8, session.states.values.count { it == R.string.translation_animation })
        }
        await { notices().any { it.notification.actions.isNullOrEmpty() &&
            it.notification.extras.getString(android.app.Notification.EXTRA_TEXT) ==
            context.getString(R.string.translation_gallery_completed, 8, 0) } }
        assertTrue(source.stopped)
    }

    @Test fun notificationStopStopsTheSessionAndRemovesTheOngoingNotice() = withSession { session, source ->
        onMain {
            session.reader = Reader()
            session.onPageChanged(3)
            assertTrue(session.translateFullGallery())
        }
        await { source.order.isNotEmpty() && notices().any { !it.notification.actions.isNullOrEmpty() } }
        val stop = notices().first { !it.notification.actions.isNullOrEmpty() }.notification.actions[0]
        assertEquals(context.getString(R.string.translation_notification_stop), stop.title)
        stop.actionIntent.send()
        await { onMainResult { !session.enabled && !session.working } }
        await { notices().none { it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 } }
        assertEquals(listOf(3), source.order.toList())
        assertTrue(source.stopped)
    }

    private fun withSession(test: (GalleryTranslationSession, Source) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testTranslationBackground") == "true")
        assumeTrue("Prepare comic models first", TranslationModels(context).ready())
        val options = TranslationSettings(context).read()
        assumeTrue("Prepare selected language backend first", when (options.backend) {
            TranslationBackend.NATIVE_LLM -> NativeModelStore(context).ready(options)
            TranslationBackend.ML_KIT -> runBlocking { OfflineTranslator.isReady(options.target) }
            TranslationBackend.LLM_API -> true
        })
        val source = Source()
        val provider = Provider(source, "background-device-${UUID.randomUUID()}")
        val session = onMainResult { TranslationTasks.acquire(context, provider, null) }
        try { test(session, source) } finally {
            source.permits.release(20)
            onMain { TranslationTasks.release(session) }
        }
    }

    private fun notices() = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .activeNotifications.filter { it.notification.channelId == "${context.packageName}.translation" }

    private fun backgroundApp() {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
        ).bufferedReader().use { it.readText() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) {
            assertTrue("Timed out waiting for translation service", System.nanoTime() < deadline)
            Thread.sleep(100)
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        onMain { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private class Reader : GalleryTranslationSession.Reader {
        override fun changed() = Unit
        override fun hasTranslatedPage(page: Int) = false
        override fun display(page: Int, image: Image) = image.recycle()
    }

    private class Source {
        val order = CopyOnWriteArrayList<Int>()
        val permits = Semaphore(0)
        @Volatile var stopped = false
    }

    private class Provider(private val source: Source, private val identity: String) : GalleryProvider2() {
        override fun createTranslationProvider(context: Context) = Provider(source, identity)
        override fun getTranslationIdentity() = identity
        override fun size() = 8
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index.gif"
        override fun save(index: Int, file: UniFile): Boolean {
            source.order.add(index)
            check(source.permits.tryAcquire(60, TimeUnit.SECONDS))
            file.openOutputStream().use { it.write("GIF89a".toByteArray()) }
            return true
        }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
        override fun stop() {
            super.stop()
            source.stopped = true
            source.permits.release(20)
        }
    }
}
