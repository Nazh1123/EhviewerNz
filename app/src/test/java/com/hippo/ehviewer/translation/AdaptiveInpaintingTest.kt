package com.hippo.ehviewer.translation

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
@Config(sdk = [28], application = android.app.Application::class)
class AdaptiveInpaintingTest {
    @Test fun aiModeWithFlatBubbleDoesNotLoadMissingNativeModelAndPreservesUnmaskedPixels() = runBlocking {
        val page = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffe9f0fa.toInt()) }
        val mask = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        for (y in 40..49) for (x in 40..49) { page.setPixel(x, y, Color.BLACK); mask.setPixel(x, y, Color.WHITE) }
        val region = TextRegion(listOf(TextLine(listOf(Pt(38f, 38f), Pt(52f, 38f), Pt(52f, 52f), Pt(38f, 52f)), 1f)), "h")
        Inpainter("/missing/model.param", InpainterConfig(maskRadius = 2, regionPad = 8)).use {
            it.warmUp()
            val result = it.inpaint(page, listOf(region), mask)
            assertEquals(0xffe9f0fa.toInt(), result.getPixel(44, 44))
            assertEquals(page.getPixel(0, 0), result.getPixel(0, 0))
            assertEquals(Color.BLACK, page.getPixel(44, 44))
            assertFalse(region.onArt)
            result.recycle()
        }
        page.recycle(); mask.recycle()
    }
}
