package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuperResolutionUpscalerTest {
    // A context-sensitive 2x kernel exercises inter-pass halos without native model files.
    private fun render(source: Bitmap, region: Rect, progress: (Int, Int) -> Unit): Bitmap {
        val result = Bitmap.createBitmap(region.width() * 2, region.height() * 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until result.height) for (x in 0 until result.width) {
            var sum = 0
            for (dy in -7..7) {
                val sx = (region.left + x / 2 + if (x % 2 == 0) -7 else 7).coerceIn(0, source.width - 1)
                val sy = (region.top + y / 2 + dy).coerceIn(0, source.height - 1)
                sum += Color.red(source.getPixel(sx, sy))
            }
            val gray = sum / 15
            result.setPixel(x, y, Color.rgb(gray, gray, gray))
        }
        progress(1, 1)
        return result
    }

    @Test fun cascadedRegionsMatchWholeImageAcrossEdgesAndOddDimensions() = runBlocking {
        val source = Bitmap.createBitmap(69, 67, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val gray = (x * 31 + y * 17) % 256
            source.setPixel(x, y, Color.rgb(gray, gray, gray))
        }
        try {
            for (scale in listOf(2, 4, 8)) {
                val upscaler = SuperResolutionUpscaler(::render)
                val whole = upscaler.upscale(source, Rect(0, 0, source.width, source.height), scale) { _, _ -> }
                val geometry = EnhancedTilePage(RuntimeEnvironment.getApplication().cacheDir, source.width, source.height, scale)
                try {
                    for (index in 0 until geometry.count) {
                        val region = geometry.region(index)
                        val updates = mutableListOf<Int>()
                        val tile = upscaler.upscale(source, Rect(region.left / scale, region.top / scale,
                            region.right / scale, region.bottom / scale), scale) { done, total -> updates += done * 100 / total }
                        try {
                            assertTrue(tile.width <= 512 && tile.height <= 512)
                            for (y in 0 until tile.height) for (x in 0 until tile.width) {
                                assertEquals("scale=$scale tile=$index pixel=$x,$y", whole.getPixel(region.left + x, region.top + y), tile.getPixel(x, y))
                            }
                            assertEquals(100, updates.last())
                            assertEquals(updates.sorted(), updates)
                        } finally { tile.recycle() }
                    }
                } finally { whole.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun failedLaterPassRecyclesIntermediateAndLeavesOriginalUsable() = runBlocking {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        var intermediate: Bitmap? = null
        val upscaler = SuperResolutionUpscaler { input, region, callback ->
            if (input !== source) throw CancellationException("Yield to translation")
            render(input, region, callback).also { intermediate = it }
        }
        try {
            upscaler.upscale(source, Rect(5, 5, 15, 15), 4) { _, _ -> }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(checkNotNull(intermediate).isRecycled)
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }
}
