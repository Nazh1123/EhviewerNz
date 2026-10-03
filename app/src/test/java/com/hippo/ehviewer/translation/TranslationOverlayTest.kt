package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationOverlayTest {
    @Test fun sparsePngContainsRepairsAndAntialiasedTextAndRecreatesTheRenderExactly() {
        val original = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(original).drawText("original", 10f, 35f, Paint().apply { color = Color.BLACK; textSize = 22f })
        val rendered = original.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(rendered).apply {
            drawRect(8f, 10f, 110f, 40f, Paint().apply { color = Color.WHITE })
            drawText("translated", 10f, 37f, Paint().apply { color = Color.BLACK; textSize = 20f; isAntiAlias = true })
        }
        val expected = rendered.copy(Bitmap.Config.ARGB_8888, false)
        try {
            assertSame(rendered, TranslationOverlay.extract(original, rendered))
            assertEquals(Color.TRANSPARENT, rendered.getPixel(150, 90))
            assertEquals(Color.WHITE, original.getPixel(150, 90))
            val bytes = ByteArrayOutputStream().apply { assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
            val reopened = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val combined = original.copy(Bitmap.Config.ARGB_8888, true)
            try {
                Canvas(combined).drawBitmap(reopened, 0f, 0f, null)
                assertTrue(expected.sameAs(combined))
            } finally { reopened.recycle(); combined.recycle() }
        } finally { original.recycle(); rendered.recycle(); expected.recycle() }
    }

    @Test fun unchangedRenderProducesAnEntirelyTransparentOverlay() {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val rendered = original.copy(Bitmap.Config.ARGB_8888, true)
        try {
            TranslationOverlay.extract(original, rendered)
            val pixels = IntArray(256)
            rendered.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            assertTrue(pixels.all { it == 0 })
            assertTrue(rendered.hasAlpha())
        } finally { original.recycle(); rendered.recycle() }
    }
}
