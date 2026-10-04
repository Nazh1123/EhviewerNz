package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Trace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.lib.glgallery.GalleryView
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.glview.image.ImageWrapper
import com.hippo.lib.glview.view.GLRootView
import com.hippo.lib.image.Image
import android.util.LruCache
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real reader, NCNN/GGUF and overlay restoration; only an isolated generated gallery. */
@RunWith(AndroidJUnit4::class)
class TranslationNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 240000) fun rapidPageChangesDuringInferenceResumeAndRestoreTheFinalPage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativeNavigation") == "true")
        val options = TranslationSettings(context).read().copy(
            backend = TranslationBackend.NATIVE_LLM, ahead = 0, source = "ja", target = "zh-CN",
            persistDownloaded = false,
        )
        assumeTrue(NativeModelStore(context).ready(options) && TranslationModels(context).ready())
        val directory = File(context.cacheDir, "navigation-gallery-${System.nanoTime()}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
        val seed = (System.nanoTime() and 0xffffff).toInt()
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            canvas.drawText("ありがとう。", 80f, 180f, paint)
            canvas.drawText("明日は学校へ行きます。", 80f, 260f, paint)
            repeat(8) { page ->
                bitmap.setPixel(0, 0, 0xff000000.toInt() or ((seed + page) and 0xffffff))
                File(directory, "$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { bitmap.recycle() }
        val report = File(context.getExternalFilesDir(null), "translation-smoke/navigation-report.txt")
        report.parentFile!!.mkdirs()
        report.writeText("Generated 8-page gallery; real NCNN/GGUF; no saved-setting changes.\n")
        val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
        var activity: GalleryActivity? = null
        var session: GalleryTranslationSession? = null
        try {
            shell("am start -W -n ${context.packageName}/${GalleryActivity::class.java.name} " +
                "-a ${GalleryActivity.ACTION_DIR} --es ${GalleryActivity.KEY_FILENAME} ${directory.absolutePath} " +
                "--ei ${GalleryActivity.KEY_PAGE} 0")
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity
            val reader = checkNotNull(activity)
            val selected = onMain { field<ReaderTranslationController>(reader, "mTranslationController").session }
            session = selected
            val view = onMain { field<GalleryView>(reader, "mGalleryView") }
            val provider = onMain { field<GalleryProvider2>(reader, "mGalleryProvider") }
            try {
                await(10000) { onMain { selected.current == 0 && provider.size() == 8 } }
            } catch (error: Throwable) {
                report.appendText(onMain { "Initial reader: current=${selected.current}, size=${provider.size()}, " +
                    "error=${provider.error}, browsing=${selected.browsing}\n" })
                throw error
            }
            onMain {
                selected.activeOptions = options
                setField(selected, "enabled", true)
                setField(selected, "activated", true)
                selected.enqueue(0, false)
            }
            await(30000) { onMain { selected.activePage == 0 && selected.pageProgress.value > 0f } }
            val began = SystemClock.elapsedRealtime()
            var longestMainDispatch = 0L
            repeat(40) { turn ->
                val start = SystemClock.elapsedRealtime()
                onMain {
                    Trace.beginSection("translation-navigation")
                    try { view.setCurrentPage((turn * 3) % 8) } finally { Trace.endSection() }
                    assertTrue(selected.enabled)
                }
                longestMainDispatch = maxOf(longestMainDispatch, SystemClock.elapsedRealtime() - start)
                SystemClock.sleep(35)
            }
            onMain { view.setCurrentPage(7) }
            await(180000) { onMain {
                assertTrue("Navigation disabled translation", selected.enabled)
                selected.current == 7 && selected.states[7] == R.string.translation_done && !selected.working &&
                    provider.hasTranslatedPage(7)
            } }
            assertTrue("Generated Japanese text was not translated", onMain { selected.timings.getValue(7).kept > 0 })
            assertTrue("Main dispatch stalled during page changes", longestMainDispatch < 2000L)
            // Evict the visible overlay and repeat the burst while restoring the existing result.
            onMain { provider.clearTranslatedPages() }
            repeat(40) { turn ->
                onMain { view.setCurrentPage(if (turn % 2 == 0) 6 else 7) }
                SystemClock.sleep(20)
            }
            onMain { view.setCurrentPage(7) }
            await(30000) { onMain { selected.current == 7 && provider.hasTranslatedPage(7) && !selected.working } }
            // SpiderQueen broadcasts one decoded Image to both reader and translation providers.
            // Repeatedly stop the second provider while the real GL reader still needs that image.
            var shared: Image? = null
            repeat(20) {
                onMain {
                    shared = requireNotNull(Image.create(Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)))
                    val background = SharedImageProvider()
                    background.start()
                    try {
                        provider.notifyPageSucceed(7, shared)
                        background.notifyPageSucceed(7, shared)
                    } finally { background.stop() }
                    assertFalse("Stopping translation recycled the reader's shared source", shared!!.isRecycled)
                }
                SystemClock.sleep(35)
            }
            // Also recover an explicitly invalidated cache entry without a GL success/retry loop.
            onMain {
                val root = reader.findViewById<GLRootView>(R.id.gl_root_view)
                root.lockRenderThread()
                try { shared!!.recycle(); provider.request(7) } finally { root.unlockRenderThread() }
            }
            await(10000) { onMain {
                val cache = field<LruCache<Int, ImageWrapper>>(provider, "mImageCache")
                cache.get(7)?.isImageRecycled == false &&
                    !field<Boolean>(reader.findViewById<GLRootView>(R.id.gl_root_view), "mRenderRequested")
            } }
            report.appendText("PASS: 80 rapid changes; last page translated and restored; " +
                "20 shared-provider stop cycles and invalid-cache recovery; " +
                "elapsed=${SystemClock.elapsedRealtime() - began}ms; longest main dispatch=${longestMainDispatch}ms\n")
            File(report.parentFile, "navigation-gfxinfo.txt").writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
        } finally {
            session?.let { selected ->
                onMain { TranslationTasks.release(selected) }
                await(30000) { onMain { field<kotlinx.coroutines.Job?>(selected, "worker") == null } }
            }
            activity?.let { reader -> onMain { reader.finish() } }
            instrumentation.removeMonitor(monitor)
            directory.deleteRecursively()
        }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            assertTrue("Navigation failed to resume before timeout", SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(25)
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(target) as T }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing field $name")
    }

    private fun setField(target: Any, name: String, value: Any) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private class SharedImageProvider : GalleryProvider() {
        override fun size() = 8
        override fun getError() = ""
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
    }
}
