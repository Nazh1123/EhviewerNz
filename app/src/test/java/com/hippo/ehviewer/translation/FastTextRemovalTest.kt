package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class FastTextRemovalTest {
    @Test fun fastModeNeedsNoModelAndChangesOnlyDetectedStrokesNearRecognizedText() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE); setPixel(8, 8, Color.BLACK); setPixel(20, 20, Color.GREEN)
        }
        val mask = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK); setPixel(8, 8, Color.WHITE); setPixel(20, 20, Color.WHITE)
        }
        val region = TextRegion(listOf(TextLine(listOf(Pt(6f, 6f), Pt(14f, 6f), Pt(14f, 14f), Pt(6f, 14f)), 1f)), "h")
        try {
            Inpainter("model-does-not-exist.param", InpainterConfig(method = "boxfill", maskRadius = 2, regionPad = 2)).use {
                val cleaned = it.inpaint(source, listOf(region), mask)
                try {
                    assertEquals(Color.BLACK, source.getPixel(8, 8))
                    assertEquals(Color.WHITE, cleaned.getPixel(8, 8))
                    assertEquals(Color.GREEN, cleaned.getPixel(20, 20))
                    assertFalse(region.onArt)
                    assertNotSame(source, cleaned)
                } finally { cleaned.recycle() }
            }
        } finally { source.recycle(); mask.recycle() }
    }
}
