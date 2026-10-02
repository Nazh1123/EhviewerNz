package com.hippo.ehviewer.translation

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.lib.glview.view.GLRootView
import com.hippo.lib.image.Image
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real gallery/GL lifecycle, with an isolated service queue and optional real imported GGUF. */
@RunWith(AndroidJUnit4::class)
class ReaderBackgroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var watchdog: Thread? = null

    @Before fun recordThreadsIfTheDeviceTestStalls() {
        watchdog = Thread {
            try {
                Thread.sleep(15000)
                File(context.cacheDir, "reader-background-test-threads.txt").writeText(
                    Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, frames) ->
                        "${thread.name} ${thread.state}\n${frames.joinToString("\n")}"
                    })
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    @After fun stopWatchdog() { watchdog?.interrupt() }

    @Test(timeout = 30000) fun notificationStopWithVisibleTranslatedImagesDoesNotBlockHome() = withReader { scenario, session, _ ->
        await { notice() != null }
        assertEquals(Stage.RESUMED, scenario.state)
        // Use the exact PendingIntent exposed by the ongoing notification while the reader is visible.
        notice()!!.notification.actions[0].actionIntent.send()
        await { !onMainResult { session.enabled } }
        home()
        await { scenario.state == Stage.STOPPED }
        assertFalse(onMainResult { session.working })
        await { notice() == null }
    }

    @Test(timeout = 120000) fun importedGgufTranslatesAfterHomeWhileTheReaderServiceRemainsActive() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativeBackground") == "true")
        val options = TranslationSettings(context).read()
        assumeTrue(options.backend == TranslationBackend.NATIVE_LLM && NativeModelStore(context).ready(options))
        withReader { scenario, session, _ ->
            await { notice() != null }
            home()
            await { scenario.state == Stage.STOPPED }
            assertTrue(onMainResult { session.enabled && session.readerOpen && !session.browsing })
            // Loading, prompt decoding and every output token happen after the actual HOME transition.
            val began = SystemClock.elapsedRealtime()
            runBlocking(Dispatchers.IO) {
                NativeTranslator(context, options) { check(session.enabled) }.use { translator ->
                    val source = listOf("明日は学校へ行きます。", "この本を読んでください。")
                    val result = translator.translate(source)
                    assertEquals(source.size, result.size)
                    result.forEachIndexed { index, text ->
                        assertTrue(text.isNotBlank())
                        assertNotEquals(source[index], text)
                    }
                }
            }
            assertNotNull("Foreground protection disappeared during JNI inference", notice())
            assertTrue(onMainResult { session.enabled })
            File(context.getExternalFilesDir(null), "translation-smoke/native-after-home.txt")
                .apply { parentFile!!.mkdirs() }.writeText(
                    "Uncached production NativeTranslator after HOME: ${SystemClock.elapsedRealtime() - began}ms; " +
                        "reader=STOPPED; service=active\n")
        }
    }

    private fun withReader(test: (ReaderHost, GalleryTranslationSession, GLRootView) -> Unit) {
        val directory = File(context.cacheDir, "reader-background-${System.nanoTime()}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            File(directory, "0.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val repeat = AtomicBoolean(true)
        var activity: GalleryActivity? = null
        val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
        try {
            // Shell starts are reliable on devices which intercept Instrumentation.startActivitySync.
            shell("am start -W -n ${context.packageName}/${GalleryActivity::class.java.name} " +
                "-a ${GalleryActivity.ACTION_DIR} --es ${GalleryActivity.KEY_FILENAME} '${directory.absolutePath}' " +
                "--ei ${GalleryActivity.KEY_PAGE} 0")
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity
            val selectedActivity = checkNotNull(activity) { "Device did not open the test gallery" }
            val scenario = ReaderHost(selectedActivity)
            run {
                lateinit var root: GLRootView
                lateinit var session: GalleryTranslationSession
                instrumentation.runOnMainSync {
                    val controller = field<ReaderTranslationController>(selectedActivity, "mTranslationController")
                    session = controller.session
                    val selected = session
                    root = selectedActivity.findViewById(R.id.gl_root_view)
                    // No worker/model preparation: this isolates visible-reader cancellation from inference.
                    setField(selected, "enabled", true)
                    setField(selected, "working", true)
                    selected.onPageChanged(0)
                    val provider = field<com.hippo.ehviewer.gallery.GalleryProvider2>(selectedActivity, "mGalleryProvider")
                    provider.setTranslatedPage(0, requireNotNull(Image.create(
                        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888))))
                    // Model the repeating uploader that previously starved the platform pause handshake.
                    root.addOnGLIdleListener { _, _ -> repeat.get() }
                    TranslationTasks.wake(context, selected)
                }
                try { test(scenario, session, root) } finally {
                    repeat.set(false)
                    instrumentation.runOnMainSync { TranslationTasks.release(session) }
                }
            }
        } finally {
            repeat.set(false)
            instrumentation.removeMonitor(monitor)
            activity?.let { reader -> instrumentation.runOnMainSync { reader.finish() } }
            directory.deleteRecursively()
        }
    }

    private fun notice() = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .activeNotifications.firstOrNull { it.notification.channelId == "${context.packageName}.translation" &&
            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }

    private fun home() = shell("input keyevent KEYCODE_HOME")

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition()) {
            assertTrue("Timed out waiting for reader/service lifecycle", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25)
        }
    }

    private fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private fun setField(target: Any, name: String, value: Any) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private inner class ReaderHost(val activity: GalleryActivity) {
        val state get() = onMainResult { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) }
    }
}
