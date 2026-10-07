package com.hippo.ehviewer.translation

import android.graphics.*
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TiledDetectionIntegrationTest {
    private val colors = listOf(Color.RED, Color.BLUE, Color.GREEN)
    private fun infer(crop: Bitmap): Detection {
        val mask = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val lines = colors.mapNotNull { color ->
            var left = crop.width; var top = crop.height; var right = -1; var bottom = -1
            for (y in 0 until crop.height) for (x in 0 until crop.width) if (crop.getPixel(x, y) == color) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                mask.setPixel(x, y, Color.WHITE)
            }
            if (right - left < 2 || bottom - top < 2) null else TextLine(listOf(Pt(left.toFloat(), top.toFloat()),
                Pt(right.toFloat(), top.toFloat()), Pt(right.toFloat(), bottom.toFloat()), Pt(left.toFloat(), bottom.toFloat())), .95f).apply {
                direction = if (bottom - top > right - left) "v" else "h"
            }
        }
        return Detection(lines, mask)
    }
    @Test fun stitchedCoordinatesAndMasksPreserveSeamTextAndJoinLongColumnsInBothAxes() {
        for (horizontal in listOf(false, true)) {
            val page = Bitmap.createBitmap(if (horizontal) 4096 else 128, if (horizontal) 128 else 4096, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.WHITE) }
            fun set(x: Int, y: Int, color: Int) = if (horizontal) page.setPixel(y, x, color) else page.setPixel(x, y, color)
            for (y in 241..273) for (x in 10..80) set(x, y, Color.RED)
            for (y in 100..1700) for (x in 100..108) set(x, y, Color.BLUE)
            for (y in 100..1700) for (x in 115..123) set(x, y, Color.GREEN)
            val snapshot = page.copy(Bitmap.Config.ARGB_8888, false)
            val crops = mutableListOf<Bitmap>(); val masks = mutableListOf<Bitmap>()
            try {
                val result = DetectionTiles.detect(page, DetectorConfig(inputSize = 256), { crop ->
                    crops.add(crop); assertTrue(maxOf(crop.width, crop.height) <= 256)
                    infer(crop).also { masks.add(it.textMask) }
                })
                try {
                    assertEquals(3, result.lines.size)
                    val long = result.lines.filter { it.direction == if (horizontal) "h" else "v" }
                    assertEquals(2, long.size)
                    for (line in long) {
                        assertEquals(100f, line.quad.minOf { if (horizontal) it.x else it.y }, .01f)
                        assertEquals(1700f, line.quad.maxOf { if (horizontal) it.x else it.y }, .01f)
                    }
                    for (y in 0 until page.height) for (x in 0 until page.width)
                        assertEquals(if (page.getPixel(x, y) == Color.WHITE) Color.BLACK else Color.WHITE, result.textMask.getPixel(x, y))
                    assertTrue(page.sameAs(snapshot)); assertTrue(crops.size > 1)
                    assertTrue(crops.all { it.isRecycled }); assertTrue(masks.all { it.isRecycled })
                } finally { result.textMask.recycle() }
            } finally { snapshot.recycle(); page.recycle() }
        }
    }
    @Test fun supersededPageStopsBeforeNextTileAndReleasesCurrentTileResources() {
        val page = Bitmap.createBitmap(64, 4096, Bitmap.Config.ARGB_8888)
        var inferenceCount = 0; var crop: Bitmap? = null; var mask: Bitmap? = null
        try {
            try {
                DetectionTiles.detect(page, DetectorConfig(inputSize = 256), {
                    inferenceCount++; crop = it
                    infer(it).also { detected -> mask = detected.textMask }
                }, { if (inferenceCount > 0) throw CancellationException("Page superseded") })
                fail("Continued after supersession")
            } catch (_: CancellationException) { }
            assertEquals(1, inferenceCount); assertTrue(crop!!.isRecycled); assertTrue(mask!!.isRecycled)
            assertFalse(page.isRecycled)
        } finally { page.recycle() }
    }
    @Test fun ordinaryPageUsesOriginalBitmapOnceAndTransfersItsDetectionMask() {
        val page = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888)
        var calls = 0
        try {
            val result = DetectionTiles.detect(page, DetectorConfig(inputSize = 256), { calls++; assertSame(page, it); infer(it) })
            try { assertEquals(1, calls); assertFalse(result.textMask.isRecycled); assertFalse(page.isRecycled) }
            finally { result.textMask.recycle() }
        } finally { page.recycle() }
    }
}
