package com.hippo.ehviewer.gallery

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native archive extraction, using only a unique app-cache fixture. No user galleries/models. */
@RunWith(AndroidJUnit4::class)
class ArchiveGalleryProviderDeviceTest {
    @Test(timeout = 45000) fun actualReaderDecodesArchiveAndRestoresBothResultsAfterMove() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "archive-reader-test-${System.nanoTime()}").apply { check(mkdir()) }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadPreference = preferences.contains(GalleryResultIndex.PREFER_EXISTING)
        val previousPreference = preferences.getBoolean(GalleryResultIndex.PREFER_EXISTING, false)
        val previousGuide = Settings.getGuideGallery()
        var activity: GalleryActivity? = null
        fun png(width: Int, height: Int, color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                finally { bitmap.recycle() }
        }
        fun open(archive: File): GalleryActivity {
            val monitor = instrumentation.addMonitor(GalleryActivity::class.java.name, null, false)
            try {
                val command = "am start -W -n ${context.packageName}/${GalleryActivity::class.java.name} " +
                    "-a ${Intent.ACTION_VIEW} -d ${Uri.fromFile(archive)} -t application/zip --ei ${GalleryActivity.KEY_PAGE} 0"
                instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
                return checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? GalleryActivity) {
                    "Archive reader did not open"
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        fun readerProvider(reader: GalleryActivity) = GalleryActivity::class.java.getDeclaredField("mGalleryProvider")
            .apply { isAccessible = true }.get(reader) as ArchiveGalleryProvider
        fun awaitResults(reader: GalleryActivity): ArchiveGalleryProvider {
            val provider = readerProvider(reader)
            val deadline = SystemClock.elapsedRealtime() + 12000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (provider.getSuperResolutionDimensions(0).contentEquals(intArrayOf(80, 120)) &&
                    provider.hasTranslatedPage(0) && provider.hasEnhancedPage(0)) return provider
                SystemClock.sleep(50)
            }
            fail("Reader did not load original + TL + SR: uri=${provider.archiveUri}, error=${provider.error}, size=${provider.size()}, original=" +
                "${provider.getSuperResolutionDimensions(0).toList()}, TL=${provider.hasTranslatedPage(0)}, SR=${provider.hasEnhancedPage(0)}")
            return provider
        }
        try {
            preferences.edit().putBoolean(GalleryResultIndex.PREFER_EXISTING, true).commit()
            Settings.putGuideGallery(false)
            val folder = File(root, "original").apply { check(mkdir()) }
            val archive = File(folder, "reader.zip")
            val originalBytes = png(80, 120, android.graphics.Color.WHITE)
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("chapter/002.png", "chapter/010.png")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(originalBytes); zip.closeEntry()
                }
            }
            val seed = ArchiveGalleryProvider(context, Uri.fromFile(archive))
            try {
                assertTrue(seed.save(0, checkNotNull(UniFile.fromFile(File(root, "staged.image")))))
                val directory = checkNotNull(TranslationStorage.resultDirectory(context, null, seed, true))
                val index = GalleryResultIndex.open(context, GalleryResultIndex.identity(seed, null), null, directory, true)
                val source = GalleryResultIndex.source(seed, 0, null)
                index.write(source, GalleryResultIndex.Kind.TL, "png") { it.write(png(80, 120, android.graphics.Color.GREEN)) }
                index.write(source, GalleryResultIndex.Kind.SR, "png", 2) { it.write(png(160, 240, android.graphics.Color.BLUE)) }
            } finally { instrumentation.runOnMainSync { seed.stop() } }
            activity = open(archive)
            val first = awaitResults(activity!!)
            repeat(3) {
                val saved = first.saveWithResult(0, checkNotNull(UniFile.fromFile(root)), "concurrent-save")
                assertNotNull(saved)
                assertArrayEquals(originalBytes, File(root, "concurrent-save.png").readBytes())
            }
            instrumentation.runOnMainSync { activity!!.finish() }
            instrumentation.waitForIdleSync()
            activity = null
            val moved = File(root, "moved").also { assertTrue(folder.renameTo(it)) }
            GalleryResultIndex.dropMemory()
            activity = open(File(moved, "reader.zip"))
            val restored = awaitResults(activity!!)
            assertEquals("chapter/002.png", restored.getResultPageName(0))
            val live = activity!!
            instrumentation.runOnMainSync { live.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(250)
            val evidence = File(context.getExternalFilesDir(null), "archive-support-evidence").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(evidence, "reader-restored.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { screenshot.recycle() }
            }
            File(evidence, "reader-report.txt").writeText("Original decoded: 80x120\nTranslation overlay restored: true\nSuper-resolution restored: true\nMoved pair reused: true\nConcurrent original saves: 3\n")
        } finally {
            activity?.let { reader -> instrumentation.runOnMainSync { reader.finish() } }
            instrumentation.waitForIdleSync()
            preferences.edit().also {
                if (hadPreference) it.putBoolean(GalleryResultIndex.PREFER_EXISTING, previousPreference)
                else it.remove(GalleryResultIndex.PREFER_EXISTING)
            }.commit()
            Settings.putGuideGallery(previousGuide)
            ReadingHistory.list(context).filter { it.uri?.contains(root.name) == true || it.title?.contains(root.name) == true }
                .forEach { ReadingHistory.remove(context, it) }
            root.deleteRecursively()
        }
    }

    @Test fun extractOriginalAndReusePortableResultsAfterMovingPair() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "archive-support-test-${System.nanoTime()}").apply { check(mkdir()) }
        var provider: ArchiveGalleryProvider? = null
        var reopened: ArchiveGalleryProvider? = null
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        val png = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
            finally { bitmap.recycle() }
        try {
            val folder = File(root, "original").apply { check(mkdir()) }
            val archive = File(folder, "target.cbz")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("chapter/010.png", "chapter/002.png", "other/002.png")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(png); zip.closeEntry()
                }
            }
            val reader = ArchiveGalleryProvider(context, Uri.fromFile(archive)).also { provider = it }
            val original = File(root, "original.image")
            assertTrue(reader.save(0, checkNotNull(UniFile.fromFile(original))))
            assertArrayEquals(png, original.readBytes())
            assertEquals("chapter/002.png", reader.getResultPageName(0))
            assertEquals("chapter/010.png", reader.getResultPageName(1))
            val saved = reader.saveWithResult(2, checkNotNull(UniFile.fromFile(root)), "saved")
            assertEquals("saved.png", saved?.file?.name)
            assertArrayEquals(png, File(root, "saved.png").readBytes())
            assertTrue(reader.saveWithResult(2, checkNotNull(UniFile.fromFile(root)), "saved")!!.overwritten)
            val directory = checkNotNull(TranslationStorage.resultDirectory(context, null, reader, true))
            assertEquals("target.cbz.results", directory.name)
            val source = GalleryResultIndex.source(reader, 0, null)
            val index = GalleryResultIndex.open(context, GalleryResultIndex.identity(reader, null), null, directory, true)
            index.write(source, GalleryResultIndex.Kind.TL, "png") { it.write(png) }
            index.write(source, GalleryResultIndex.Kind.SR, "png", 2) { it.write(png) }
            val manifest = File(folder, "target.cbz.results/${GalleryResultIndex.MANIFEST}").readText()
            assertFalse(manifest.contains(folder.absolutePath)); assertFalse(manifest.contains("file://"))
            instrumentation.runOnMainSync { reader.stop() }
            provider = null
            val moved = File(root, "moved")
            assertTrue(folder.renameTo(moved))
            val next = ArchiveGalleryProvider(context, Uri.fromFile(File(moved, "target.cbz"))).also { reopened = it }
            assertTrue(next.save(0, checkNotNull(UniFile.fromFile(original))))
            GalleryResultIndex.dropMemory()
            val movedDirectory = checkNotNull(TranslationStorage.resultDirectory(context, null, next, false))
            val movedIndex = GalleryResultIndex.open(context, GalleryResultIndex.identity(next, null), null, movedDirectory, false)
            val movedSource = GalleryResultIndex.source(next, 0, null)
            assertNotNull(movedIndex.find(movedSource, GalleryResultIndex.Kind.TL)?.file)
            assertEquals(2, movedIndex.find(movedSource, GalleryResultIndex.Kind.SR)?.scale)
        } finally {
            instrumentation.runOnMainSync { provider?.stop(); reopened?.stop() }
            root.deleteRecursively()
        }
    }
}
