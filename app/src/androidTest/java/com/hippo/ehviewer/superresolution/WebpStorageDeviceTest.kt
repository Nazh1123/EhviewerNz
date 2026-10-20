package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.image.WebpEncoder
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebpStorageDeviceTest {
    @Test fun losslessBandsPreserveRgbAcrossOddBoundariesAndPropagateCancellation() {
        assertTrue(WebpEncoder.nativeAvailable)
        assertEquals(0L, WebpEncoder.createLossless(16384, 1))
        val width = 521; val height = 515
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            Color.rgb((x * 17 + y * 3) % 256, (x * 5 + y * 7) % 256, (x + y * 11) % 256)
        }
        val handle = WebpEncoder.createLossless(width, height)
        assertNotEquals(0L, handle)
        try {
            assertFalse(WebpEncoder.finish(handle, ByteArrayOutputStream(), Runnable {}))
            for (top in 0 until height step 67) {
                val rows = minOf(67, height - top)
                assertTrue(WebpEncoder.append(handle, pixels.copyOfRange(top * width, (top + rows) * width), rows))
            }
            val bytes = ByteArrayOutputStream().also {
                assertTrue(WebpEncoder.finish(handle, it, Runnable {}, 75))
            }.toByteArray()
            assertEquals("VP8L", String(bytes, 12, 4, Charsets.US_ASCII))
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(width, decoded.width); assertEquals(height, decoded.height)
                val restored = IntArray(pixels.size)
                decoded.getPixels(restored, 0, width, 0, 0, width, height)
                assertArrayEquals(pixels, restored)
            } finally { decoded.recycle() }
            try {
                WebpEncoder.finish(handle, ByteArrayOutputStream(), Runnable { throw IllegalStateException("cancelled") }, 75)
                fail("Lossless encoder swallowed cancellation")
            } catch (expected: IllegalStateException) { assertEquals("cancelled", expected.message) }
        } finally { WebpEncoder.release(handle) }
    }

    @Test fun selectedQualityReachesNativeEncoderAndLosslessOutputPreservesPixels() = runBlocking {
        assertTrue(WebpEncoder.nativeAvailable)
        val width = 128; val height = 128
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            Color.rgb((x * 3 + y * 5) % 256, (x * 7 + y * 2) % 256, (x * 11 + y * 13) % 256)
        }
        val handle = WebpEncoder.create(width, height)
        assertNotEquals(0L, handle)
        try {
            assertTrue(WebpEncoder.append(handle, pixels, height))
            val encoded = listOf(80, 85, 90, 95, 100).map { quality ->
                ByteArrayOutputStream().also {
                    assertTrue(WebpEncoder.finish(handle, it, Runnable {}, quality))
                }.toByteArray()
            }
            assertEquals(5, encoded.map { it.toList() }.toSet().size)
            assertTrue(encoded.last().size > encoded.first().size)
            for (bytes in encoded) {
                val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try { assertEquals(width, decoded.width); assertEquals(height, decoded.height) }
                finally { decoded.recycle() }
            }
        } finally { WebpEncoder.release(handle) }

        val directory = File.createTempFile("sr-quality-", ".tiles",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        check(directory.delete() && directory.mkdirs())
        val page = EnhancedTilePage(directory, width / 2, height / 2)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            page.file(0).outputStream().use { WebpEncoder.writeLossless(bitmap, it) }
            val output = File(directory, "result.webp")
            val result = SuperResolutionExport.encode(page, SuperResolutionWebp.MAX_WORKSPACE,
                SuperResolutionQuality.LOSSLESS) { extension, writer ->
                assertEquals("webp", extension)
                output.outputStream().use { writer(it) }
                checkNotNull(UniFile.fromFile(output))
            }
            val decoded = checkNotNull(result.openInputStream().use { BitmapFactory.decodeStream(it) })
            try {
                val restored = IntArray(pixels.size)
                decoded.getPixels(restored, 0, width, 0, 0, width, height)
                assertArrayEquals(pixels, restored)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle(); page.trim(); directory.deleteRecursively() }
    }

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
