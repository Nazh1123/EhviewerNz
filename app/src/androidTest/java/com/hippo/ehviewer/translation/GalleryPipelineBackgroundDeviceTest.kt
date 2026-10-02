package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.ui.GalleryActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in actual reader queue: real page pixels, NCNN OCR/inpainting, imported GGUF and PNG output. */
@RunWith(AndroidJUnit4::class)
class GalleryPipelineBackgroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 600000) fun completesThreeUncachedRealPagesAfterHome() {
        val path = InstrumentationRegistry.getArguments().getString("testGalleryPage")
        assumeTrue(path != null)
        val options = TranslationSettings(context).read()
        assumeTrue(options.backend == TranslationBackend.NATIVE_LLM && NativeModelStore(context).ready(options))
        assumeTrue(TranslationModels(context).ready())
        val input = requireNotNull(BitmapFactory.decodeFile(path)) { "Supply a readable real page" }
        val directory = File(context.cacheDir, "pipeline-gallery-${System.nanoTime()}").apply { mkdirs() }
        val seed = (System.nanoTime() and 0xffffff).toInt()
        try {
            // Give each copy a distinct cache identity; real text and layout remain unchanged.
            repeat(3) { page ->
                val copy = input.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    copy.setPixel(0, 0, 0xff000000.toInt() or ((seed + page) and 0xffffff))
                    File(directory, "$page.png").outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { copy.recycle() }
            }
        } finally { input.recycle() }
        val report = File(context.getExternalFilesDir(null), "translation-smoke/gallery-background-report.txt")
        report.parentFile!!.mkdirs()
        report.writeText("Real page source, three uncached copies; unchanged saved settings.\n")
        val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
        var activity: GalleryActivity? = null
        var session: GalleryTranslationSession? = null
        try {
            shell("am start -W -n ${context.packageName}/${GalleryActivity::class.java.name} " +
                "-a ${GalleryActivity.ACTION_DIR} --es ${GalleryActivity.KEY_FILENAME} '${directory.absolutePath}' --ei ${GalleryActivity.KEY_PAGE} 0")
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity
            val reader = checkNotNull(activity)
            val selected = onMain {
                val field = reader.javaClass.getDeclaredField("mTranslationController").apply { isAccessible = true }
                (field.get(reader) as ReaderTranslationController).session
            }
            session = selected
            // The activity monitor can fire during creation. Wait for the real initial
            // frame/page notification before HOME, just as opening the full-translation menu requires.
            await(10000) { onMain {
                val providerField = reader.javaClass.getDeclaredField("mGalleryProvider").apply { isAccessible = true }
                val readerSource = providerField.get(reader) as? GalleryProvider2
                ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(reader) == Stage.RESUMED &&
                    selected.current == 0 && readerSource?.size() == 3
            } }
            // HOME happens before enabling: every real pipeline stage runs with a stopped reader.
            shell("input keyevent KEYCODE_HOME")
            await(5000) { onMain { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(reader) == Stage.STOPPED } }
            assertTrue(onMain { selected.translateFullGallery() })
            val began = SystemClock.elapsedRealtime()
            var maxRssKb = 0L
            var previous = ""
            await(550000) {
                val rss = File("/proc/self/status").useLines { lines ->
                    lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                }
                maxRssKb = maxOf(maxRssKb, rss)
                val snapshot = onMain {
                    assertFalse("Reader returned to the foreground during the test", selected.browsing)
                    assertTrue("Translation stopped before completing the gallery", selected.enabled)
                    "${selected.completed}/3 page=${selected.activePage} progress=${selected.pageProgress.value}"
                }
                if (snapshot != previous) {
                    report.appendText("${SystemClock.elapsedRealtime() - began}ms $snapshot rss=${rss}KiB\n")
                    previous = snapshot
                }
                onMain { !selected.working && !selected.fullGallery && selected.completed == 3 }
            }
            onMain {
                assertEquals(3, selected.timings.size)
                repeat(3) { page ->
                    assertEquals(R.string.translation_done, selected.states[page])
                    assertTrue("OCR/GGUF must translate text, not just skip a page", selected.timings.getValue(page).kept > 0)
                    report.appendText("page=$page ${selected.timings.getValue(page)}\n")
                }
            }
            report.appendText("Complete after HOME: ${SystemClock.elapsedRealtime() - began}ms; peak RSS=${maxRssKb}KiB\n")
        } finally {
            session?.let { selected -> onMain { TranslationTasks.release(selected) } }
            activity?.let { reader -> onMain { reader.finish() } }
            instrumentation.removeMonitor(monitor)
            directory.deleteRecursively()
        }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            assertTrue("Full real-page background pipeline timed out", SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(250)
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
