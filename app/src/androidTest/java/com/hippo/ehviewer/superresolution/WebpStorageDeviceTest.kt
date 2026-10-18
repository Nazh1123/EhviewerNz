package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hippo.ehviewer.image.WebpEncoder
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebpStorageDeviceTest {
    @Test fun losslessOverlayPreservesEveryAlphaAndPremultipliedColour() {
        assertTrue(WebpEncoder.nativeAvailable)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            for (alpha in 0..255) for (value in 0..255)
                bitmap.setPixel(value, alpha, Color.argb(alpha, value, 255 - value, value / 2))
            val bytes = ByteArrayOutputStream().also { WebpEncoder.writeLossless(bitmap, it) }.toByteArray()
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                for (alpha in 0..255) for (value in 0..255)
                    assertEquals("alpha=$alpha value=$value", bitmap.getPixel(value, alpha), decoded.getPixel(value, alpha))
            } finally { decoded.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun bandedLossyFrameHasNoExtraErrorAtBandBoundariesAndPropagatesCancellation() {
        assertTrue(WebpEncoder.nativeAvailable)
        val width = 520; val height = 514
        fun color(x: Int, y: Int): Int {
            val gray = if ((x / 29 + y / 31) % 2 == 0) 180 + (x + y) % 60 else 20 + (x * 3 + y) % 60
            return Color.rgb(gray, gray, gray)
        }
        assertEquals(0L, WebpEncoder.create(16384, 1))
        val handle = WebpEncoder.create(width, height)
        assertNotEquals(0L, handle)
        try {
            assertFalse(WebpEncoder.finish(handle, ByteArrayOutputStream(), Runnable {}))
            for (top in 0 until height step 128) {
                val rows = minOf(128, height - top)
                assertTrue(WebpEncoder.append(handle, IntArray(width * rows) { color(it % width, top + it / width) }, rows))
            }
            val bytes = ByteArrayOutputStream().also { assertTrue(WebpEncoder.finish(handle, it, Runnable {})) }.toByteArray()
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                var error = 0L; var seamError = 0L; var seamCount = 0
                for (y in 0 until height) for (x in 0 until width) {
                    val delta = Color.red(decoded.getPixel(x, y)) - Color.red(color(x, y))
                    error += delta * delta
                    if (y % 128 in listOf(0, 127)) { seamError += delta * delta; seamCount++ }
                }
                assertTrue(error.toDouble() / (width * height) < 15)
                assertTrue(seamError.toDouble() / seamCount < 25)
            } finally { decoded.recycle() }
            try {
                WebpEncoder.finish(handle, ByteArrayOutputStream(), Runnable { throw IllegalStateException("cancelled") })
                fail("Native encoder swallowed cancellation")
            } catch (expected: IllegalStateException) { assertEquals("cancelled", expected.message) }
        } finally { WebpEncoder.release(handle) }
    }
}
