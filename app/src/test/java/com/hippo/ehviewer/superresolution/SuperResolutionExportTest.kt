package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuperResolutionExportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun color(x: Int, y: Int) = Color.rgb(x % 251, y % 251, (x + y) % 251)

    private fun page(width: Int, height: Int, scale: Int): EnhancedTilePage {
        val page = EnhancedTilePage(temp.newFolder(), width, height, scale)
        for (index in 0 until page.count) {
            val core = page.core(index)
            val region = page.region(index)
            val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
            try {
                val pixels = IntArray(region.width() * region.height()) { offset ->
                    val x = region.left + offset % region.width()
                    val y = region.top + offset / region.width()
                    if (core.contains(x, y)) color(x, y) else Color.MAGENTA
                }
                bitmap.setPixels(pixels, 0, region.width(), 0, 0, region.width(), region.height())
                page.file(index).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        return page
    }

    @Test fun completePngHasCorrectDimensionsAndSeamPixelsAtEveryScale() = runBlocking {
        val gallery = checkNotNull(UniFile.fromFile(temp.newFolder()))
        for (scale in listOf(2, 4, 8)) {
            val page = page(259, 257, scale)
            val result = SuperResolutionExport.save(page, gallery, "Chapter/001")
            val decoded = checkNotNull(result.openInputStream().use { BitmapFactory.decodeStream(it) })
            try {
                assertEquals(page.width, decoded.width); assertEquals(page.height, decoded.height)
                val pixels = IntArray(decoded.width * decoded.height)
                decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                for (y in 0 until page.height) for (x in 0 until page.width)
                    if (pixels[y * page.width + x] != color(x, y)) fail("Wrong pixel at $x,$y for ${scale}x")
            } finally { decoded.recycle() }
        }
        assertEquals(listOf("001_sr.png"), gallery.findFile("_suepr_resolution")!!.findFile("Chapter")!!.listFiles()!!.map { it.name })
    }

    @Test fun widePageStreamsAcrossMultipleBands() = runBlocking {
        val page = page(2101, 253, 2)
        val result = File(temp.root, "wide.png")
        result.outputStream().use { SuperResolutionPng.write(page, it) }
        val decoded = checkNotNull(BitmapFactory.decodeFile(result.path))
        try {
            for (y in listOf(0, 498, 499, 500, 503, 504, 505))
                for (x in listOf(0, 503, 504, 1007, 4201)) assertEquals(color(x, y), decoded.getPixel(x, y))
        } finally { decoded.recycle() }
    }

    @Test fun failedOrCancelledReplacementKeepsPreviousImageAndRemovesTemporaryFile() = runBlocking {
        val root = temp.newFolder()
        val gallery = checkNotNull(UniFile.fromFile(root))
        SuperResolutionExport.publish(gallery, "001") { it.write("previous".toByteArray()) }
        try {
            SuperResolutionExport.publish(gallery, "001") { it.write(1); error("write failed") }
            fail("Expected failed export")
        } catch (_: IllegalStateException) { }
        val job = launch {
            SuperResolutionExport.publish(gallery, "001") { it.write(1); currentCoroutineContext().cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        val directory = File(root, "_suepr_resolution")
        assertEquals(listOf("001_sr.png"), directory.list()!!.toList())
        assertEquals("previous", File(directory, "001_sr.png").readText())
        try {
            SuperResolutionExport.publish(gallery, "../escape") { fail("Must reject traversal") }
            fail("Expected invalid path")
        } catch (_: IllegalArgumentException) { }
    }
}
