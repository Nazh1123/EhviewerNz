package com.hippo.ehviewer.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.lib.glgallery.GalleryView
import com.hippo.lib.glview.image.ImageTexture
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real archive extraction and JNI decoding; all fixtures stay in the app cache. */
@RunWith(AndroidJUnit4::class)
class ArchiveAnimationDeviceTest {
    @Test(timeout = 90000) fun actualReaderControlsMixedArchiveAndPreservesSavedAndProcessingSources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val root = File(context.cacheDir, "archive-animation-reader-${System.nanoTime()}").apply { check(mkdir()) }
        val evidence = File(context.getExternalFilesDir(null), "archive-animation-qa").apply { mkdirs() }
        val report = StringBuilder()
        val oldEnabled = Settings.getExperimentalAnimatedWebpEnabled()
        val oldDirection = Settings.getReadingDirection()
        val oldGuide = Settings.getGuideGallery()
        val oldAutoAdvance = Settings.getAnimatedWebpAutoAdvance()
        val oldSeek = Settings.getAnimatedWebpAllowSeek()
        val oldControls = Settings.getAnimatedWebpShowControls()
        val oldTime = Settings.getAnimatedWebpShowTime()
        var activity: GalleryActivity? = null
        fun <T> main(block: () -> T): T {
            val value = AtomicReference<T>()
            instrumentation.runOnMainSync { value.set(block()) }
            return value.get()
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
                SystemClock.sleep(40)
            }
        }
        fun field(reader: GalleryActivity, name: String): Any? = GalleryActivity::class.java
            .getDeclaredField(name).apply { isAccessible = true }.get(reader)
        fun staticBytes(format: Bitmap.CompressFormat): ByteArray {
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            return try { ByteArrayOutputStream().also { check(bitmap.compress(format, 100, it)) }.toByteArray() }
            finally { bitmap.recycle() }
        }
        fun node(id: String): AccessibilityNodeInfo? = automation.rootInActiveWindow
            ?.takeIf { it.packageName?.toString() == context.packageName }
            ?.findAccessibilityNodeInfosByViewId("${context.packageName}:id/$id")?.firstOrNull()
        fun requireReaderForeground() {
            check(main { activity?.hasWindowFocus() == true }) { "Reader lost foreground; stop input and screenshots" }
            check(automation.rootInActiveWindow?.packageName?.toString() == context.packageName) {
                "Another application is in front; stop input and screenshots"
            }
        }
        fun click(id: String) {
            await("visible clickable $id") { node(id)?.let { it.isVisibleToUser && it.isClickable } == true }
            requireReaderForeground()
            check(checkNotNull(node(id)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Click failed: $id" }
        }
        fun screenshot(name: String) {
            requireReaderForeground()
            checkNotNull(automation.takeScreenshot()).let { bitmap ->
                try { File(evidence, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
        val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
        try {
            Settings.putExperimentalAnimatedWebpEnabled(true)
            Settings.putReadingDirection(0)
            Settings.putGuideGallery(false)
            Settings.putAnimatedWebpAutoAdvance(false)
            Settings.putAnimatedWebpAllowSeek(true)
            Settings.putAnimatedWebpShowControls(true)
            Settings.putAnimatedWebpShowTime(true)
            val names = listOf("001.png", "002.gif", "003.webp", "004.webp", "005.jpg")
            val originals = listOf(staticBytes(Bitmap.CompressFormat.PNG),
                instrumentation.context.assets.open("animation/disposal.gif").use { it.readBytes() },
                staticBytes(Bitmap.CompressFormat.WEBP),
                instrumentation.context.assets.open("animation/blend.webp").use { it.readBytes() },
                staticBytes(Bitmap.CompressFormat.JPEG))
            val archive = File(root, "mixed.cbz")
            ZipOutputStream(archive.outputStream()).use { zip ->
                names.forEachIndexed { page, name ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(originals[page]); zip.closeEntry()
                }
            }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "am start -W -n ${context.packageName}/${GalleryActivity::class.java.name} " +
                    "-a ${Intent.ACTION_VIEW} -d ${Uri.fromFile(archive)} -t application/zip --ei ${GalleryActivity.KEY_PAGE} 0"
            )).use { it.readBytes() }
            val reader = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity)
            activity = reader
            main { reader.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            val provider = main { field(reader, "mGalleryProvider") as ArchiveGalleryProvider }
            val gallery = main { field(reader, "mGalleryView") as GalleryView }
            fun texture(page: Int): ImageTexture? = main {
                if (field(reader, "mCurrentIndex") == page) gallery.findPageByIndex(page)?.imageTexture else null
            }
            fun loaded(page: Int, controls: Boolean): ImageTexture {
                await("page $page controls=$controls") {
                    texture(page)?.let { it.isControllableAnimation == controls &&
                        it.isControllableAnimationSource == (page == 1 || page == 3) } == true
                }
                return checkNotNull(texture(page))
            }
            fun sliders(show: Boolean) {
                if (main { reader.findViewById<View>(R.id.seek_bar_panel).visibility == View.VISIBLE } != show) {
                    main { reader.onTapSliderArea() }
                }
                await("sliders=$show") {
                    main { reader.findViewById<View>(R.id.seek_bar_panel).visibility == View.VISIBLE } == show
                }
                SystemClock.sleep(250)
            }
            for (page in names.indices) {
                main { gallery.setCurrentPage(page) }
                val animated = page == 1 || page == 3
                var image = loaded(page, animated)
                if (animated) {
                    await("animation playback started") { image.isPlaybackPlaying && image.playbackDuration > 0 }
                    val initial = image.playbackPosition
                    await("animation position advances") { image.playbackPosition != initial }
                    sliders(true)
                    click("animated_webp_play_pause")
                    await("pause") { !image.isPlaybackPlaying }
                    val paused = image.playbackPosition
                    SystemClock.sleep(200)
                    assertEquals(paused, image.playbackPosition)
                    val oldSpeed = image.playbackSpeed
                    click("animated_webp_speed")
                    await("speed button") { image.playbackSpeed != oldSpeed }
                    sliders(false)
                    // Use the page control region, away from Android's bottom quick-switch gesture.
                    val bounds = Rect().also { checkNotNull(automation.rootInActiveWindow).getBoundsInScreen(it) }
                    val dragY = bounds.top + bounds.height() * 0.7f
                    val dragStartX = bounds.left + bounds.width() * 0.45f
                    main {
                        val location = IntArray(2)
                        reader.findViewById<View>(R.id.gl_root_view).getLocationOnScreen(location)
                        val viewport = gallery.bounds()
                        assertTrue(gallery.isAnimatedPageControlArea(dragStartX - location[0] - viewport.left,
                            dragY - location[1] - viewport.top))
                    }
                    val down = SystemClock.uptimeMillis()
                    requireReaderForeground()
                    for (step in 0..8) {
                        check(main { reader.hasWindowFocus() }) { "Reader lost foreground during drag" }
                        val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 8 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                            dragStartX + bounds.width() * step * 0.0175f, dragY, 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        try { check(automation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                        SystemClock.sleep(25)
                    }
                    val seekId = main { field(reader, "mAnimatedWebpSeekRequestId") as Long }
                    await("reader drag seek acknowledged") { image.hasCompletedSeek(seekId) }
                    assertTrue("Seek did not move playback", image.playbackPosition != paused)
                    screenshot("page-${page + 1}-paused-seek.png")
                    report.append("${names[page]}: playback advanced; pause held; speed=$oldSpeed -> ${image.playbackSpeed}; drag seek=$paused -> ${image.playbackPosition}\n")
                    sliders(true)
                    click("quick_animated_webp")
                    image = loaded(page, false)
                    assertFalse(image.wasAnimationControlRequested())
                    assertEquals(View.GONE, main { reader.findViewById<View>(R.id.animated_webp_panel).visibility })
                    screenshot("page-${page + 1}-system.png")
                    click("quick_animated_webp")
                    image = loaded(page, true)
                    assertTrue(image.wasAnimationControlRequested())
                    click("animated_webp_play_pause")
                    await("resume after reload") { image.isPlaybackPlaying }
                    report.append("${names[page]}: quick switch OFF -> system; ON -> native; resume passed\n")
                    sliders(false)
                } else {
                    assertEquals(listOf(96, 128), provider.getSuperResolutionDimensions(page).toList())
                    report.append("${names[page]}: static original dimensions 96x128\n")
                }
                val saved = provider.saveWithResult(page, checkNotNull(UniFile.fromFile(root)), "saved-$page")
                assertNotNull(saved)
                assertArrayEquals(originals[page], File(checkNotNull(saved).file.uri.path!!).readBytes())
                for (stage in listOf("translation", "super-resolution")) {
                    val source = checkNotNull(provider.createTranslationProvider(context))
                    try {
                        val input = File(root, "$stage.image")
                        assertTrue(source.save(page, checkNotNull(UniFile.fromFile(input))))
                        assertArrayEquals(originals[page], input.readBytes())
                        assertEquals(names[page], source.getResultPageName(page))
                    } finally { main { source.stop() } }
                }
                report.append("${names[page]}: saved/translation/SR source bytes match original\n")
            }
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("archive-reader-") })
            report.append("No archive-reader temporary files remain.\n")
        } finally {
            File(evidence, "reader-report.txt").writeText(report.toString())
            instrumentation.removeMonitor(monitor)
            activity?.let { reader -> main { reader.finish() } }
            instrumentation.waitForIdleSync()
            Settings.putExperimentalAnimatedWebpEnabled(oldEnabled)
            Settings.putReadingDirection(oldDirection)
            Settings.putGuideGallery(oldGuide)
            Settings.putAnimatedWebpAutoAdvance(oldAutoAdvance)
            Settings.putAnimatedWebpAllowSeek(oldSeek)
            Settings.putAnimatedWebpShowControls(oldControls)
            Settings.putAnimatedWebpShowTime(oldTime)
            // Also remove this fixture's visits if an earlier instrumentation run was interrupted.
            val fixturePrefix = Uri.fromFile(File(context.cacheDir, "archive-animation-reader-")).toString()
            ReadingHistory.list(context).filter { it.uri?.startsWith(fixturePrefix) == true }
                .forEach { ReadingHistory.remove(context, it) }
            root.deleteRecursively()
        }
    }

    @Test(timeout = 45000) fun archiveAnimationsFollowControlSwitchAndPerPageDecoderMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "archive-animation-test-${System.nanoTime()}")
            .apply { check(mkdir()) }
        val previousEnabled = Settings.getExperimentalAnimatedWebpEnabled()
        val previousDirection = Settings.getReadingDirection()
        val decoded = LinkedBlockingQueue<Image>()
        val failures = LinkedBlockingQueue<String>()
        var provider: ArchiveGalleryProvider? = null
        try {
            Settings.putReadingDirection(0)
            val archive = File(root, "animations.cbz")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("disposal.gif", "blend.webp")) {
                    zip.putNextEntry(ZipEntry(name))
                    instrumentation.context.assets.open("animation/$name").use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            fun load(page: Int, enabled: Boolean, mode: Int): Image {
                val reader = object : ArchiveGalleryProvider(context, Uri.fromFile(archive)) {
                    override fun notifyPageSucceed(index: Int, image: Image) { decoded.add(image) }
                    override fun notifyPageFailed(index: Int, error: String?) { failures.add("$index: $error") }
                }
                instrumentation.runOnMainSync {
                    provider?.stop()
                    provider = reader
                    Settings.putExperimentalAnimatedWebpEnabled(enabled)
                    reader.setAnimatedWebpDecodeMode(page, mode)
                    reader.start()
                    reader.request(page)
                }
                return checkNotNull(decoded.poll(5, TimeUnit.SECONDS)) {
                    "Archive page did not decode: ${failures.toList()}, size=${reader.size()}, error=${reader.error}"
                }
            }
            // Archive pages sort naturally: blend.webp, disposal.gif.
            for (page in 0..1) {
                for (enabled in listOf(false, true, false, true)) {
                    val image = load(page, enabled, Image.ANIMATED_WEBP_MODE_DEFAULT)
                    try {
                        assertTrue(image.animated)
                        assertTrue(image.controllableAnimationSource)
                        assertEquals(enabled, image.animationControlRequested)
                        assertEquals(enabled, image.controllableAnimation)
                        if (enabled) {
                            assertTrue(image.totalDuration > 0)
                            assertTrue(image.seekTo(image.totalDuration - 1) > 0)
                            assertEquals(0, image.seekTo(0))
                        }
                    } finally { image.recycle() }
                }
                val sampled = load(page, true, Image.ANIMATED_WEBP_MODE_SAMPLE_2)
                try {
                    assertTrue(sampled.controllableAnimation)
                    assertEquals(if (page == 0) 2 else 0, sampled.animatedWebpSampleSize)
                } finally { sampled.recycle() }
                val system = load(page, true, Image.ANIMATED_WEBP_MODE_SYSTEM)
                try {
                    assertTrue(system.animated)
                    assertTrue(system.controllableAnimationSource)
                    assertFalse(system.animationControlRequested)
                    assertFalse(system.controllableAnimation)
                } finally { system.recycle() }
            }
            assertTrue(failures.isEmpty())
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("archive-reader-") })
        } finally {
            instrumentation.runOnMainSync { provider?.stop() }
            decoded.forEach { it.recycle() }
            Settings.putExperimentalAnimatedWebpEnabled(previousEnabled)
            Settings.putReadingDirection(previousDirection)
            root.deleteRecursively()
        }
    }
}
