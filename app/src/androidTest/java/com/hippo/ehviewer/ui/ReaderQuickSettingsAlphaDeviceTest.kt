package com.hippo.ehviewer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.android.resource.AttrResources
import com.hippo.lib.glgallery.GalleryView
import com.hippo.util.SystemUiHelper
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks composited screen pixels over the reader's actual GL surface. */
@RunWith(AndroidJUnit4::class)
class ReaderQuickSettingsAlphaDeviceTest {
    @Test(timeout = 60000) fun backgroundAlphaChangesActualReaderPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "quick-alpha-${System.nanoTime()}").apply { mkdirs() }
        val evidence = File(context.getExternalFilesDir(null), "quick-alpha-qa").apply { mkdirs() }
        val page = Bitmap.createBitmap(512, 8192, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        File(directory, "0.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        page.recycle()
        val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
        var activity: GalleryActivity? = null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${GalleryActivity::class.java.name} " +
                    "-a ${GalleryActivity.ACTION_DIR} --es ${GalleryActivity.KEY_FILENAME} ${directory.absolutePath} " +
                    "--ei ${GalleryActivity.KEY_PAGE} 0"
            )).bufferedReader().use { it.readText() }
            val reader = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity
                ?: error("Test gallery did not open")
            activity = reader
            lateinit var panel: View
            var initialAlpha = -1
            var systemAlpha = -1
            var legacyOpaque = false
            instrumentation.runOnMainSync {
                reader.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                panel = reader.findViewById(R.id.quick_settings_panel)
                initialAlpha = panel.background.alpha
                systemAlpha = GalleryActivity.getStatusBarBackgroundAlpha(reader.window)
                panel.setBackgroundColor(AttrResources.getAttrColor(reader, R.attr.gallerySliderBackgroundColor))
                panel.background.mutate().alpha = 60
                legacyOpaque = panel.isOpaque
                (GalleryActivity::class.java.getDeclaredField("mSystemUiHelper").apply { isAccessible = true }
                    .get(reader) as? SystemUiHelper)?.show()
                val gallery = GalleryActivity::class.java.getDeclaredField("mGalleryView").apply { isAccessible = true }
                    .get(reader) as GalleryView
                gallery.setScaleMode(GalleryView.SCALE_FIT_WIDTH)
                gallery.setBackgroundColor(Color.WHITE)
                reader.findViewById<View>(R.id.mask).visibility = View.GONE
                panel.visibility = View.VISIBLE
                panel.translationY = 0f
                GalleryActivity.setQuickSettingsBackgroundAlpha(panel, 0)
            }
            val location = IntArray(2)
            var x = 0
            var y = 0
            instrumentation.runOnMainSync {
                panel.getLocationOnScreen(location)
                x = location[0] + panel.width / 2
                y = location[1] + panel.height - 3
            }
            // Wait for asynchronous image decoding and GL texture upload.
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (true) {
                SystemClock.sleep(200)
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val pixel = screenshot.getPixel(x, y)
                File(evidence, "baseline.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                if (Color.red(pixel) > 245 && Color.green(pixel) > 245 && Color.blue(pixel) > 245) break
                File(evidence, "initial-state.txt").writeText(
                    "initialAlpha=$initialAlpha; systemAlpha=$systemAlpha; legacyOpaque=$legacyOpaque; " +
                        "sample=($x,$y); pixel=${Integer.toHexString(pixel)}")
                assertTrue("White test page was not rendered under the toolbar: ${Integer.toHexString(pixel)}",
                    SystemClock.elapsedRealtime() < deadline)
            }
            val pixels = mutableMapOf<Int, Int>()
            val report = StringBuilder("initialAlpha=$initialAlpha; systemAlpha=$systemAlpha; " +
                "legacyDrawableAlphaLeavesViewOpaque=$legacyOpaque; sample=($x,$y)\n")
            for (alpha in listOf(0, 60, 100, 255)) {
                instrumentation.runOnMainSync {
                    GalleryActivity.setQuickSettingsBackgroundAlpha(panel, alpha)
                    panel.visibility = View.VISIBLE
                    panel.translationY = 0f
                    assertEquals(alpha, panel.background.alpha)
                    assertEquals(alpha == 255, panel.isOpaque)
                }
                SystemClock.sleep(250)
                instrumentation.runOnMainSync {
                    panel.getLocationOnScreen(location)
                    x = location[0] + panel.width / 2
                    y = location[1] + panel.height - 3
                }
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                pixels[alpha] = screenshot.getPixel(x, y)
                File(evidence, "alpha-$alpha.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                report.append("alpha=$alpha; pixel=${Integer.toHexString(pixels.getValue(alpha))}\n")
            }
            File(evidence, "pixels.txt").writeText(report.toString())
            assertEquals("Initial toolbar alpha must match the status bar", systemAlpha, initialAlpha)
            val transparent = pixels.getValue(0)
            val opaque = pixels.getValue(255)
            assertTrue("Opaque background must differ from the white page", Color.red(opaque) < 100)
            for (alpha in listOf(60, 100)) {
                val expected = (Color.red(opaque) * alpha + Color.red(transparent) * (255 - alpha)) / 255
                assertEquals("Composited screenshot for alpha=$alpha", expected.toDouble(),
                    Color.red(pixels.getValue(alpha)).toDouble(), 3.0)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { reader -> instrumentation.runOnMainSync { reader.finish() } }
            directory.deleteRecursively()
        }
    }
}
