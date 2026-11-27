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
    @Test fun croppedDilationPreservesEdgeHaloAndIgnoresTextOutsideRegions() = runBlocking<Unit> {
        val white = Color.WHITE
        val page = Bitmap.createBitmap(100, 110, Bitmap.Config.ARGB_8888).apply { eraseColor(white) }
        val mask = Bitmap.createBitmap(100, 110, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        fun region(left: Float, top: Float, right: Float, bottom: Float) = TextRegion(listOf(
            TextLine(listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom)), 1f)), "h")
        val regions = listOf(region(0f, 0f, 20f, 20f), region(25f, 28f, 40f, 45f))
        for ((x, y) in listOf(0 to 1, 38 to 43, 41 to 44, 62 to 62)) {
            page.setPixel(x, y, Color.BLACK)
            mask.setPixel(x, y, white)
        }
        try {
            Inpainter("/missing/model.param", InpainterConfig(method = "boxfill", maskRadius = 3, regionPad = 0)).use {
                val result = it.inpaint(page, regions, mask)
                try {
                    assertEquals(white, result.getPixel(0, 1))
                    assertEquals(white, result.getPixel(38, 43))
                    assertEquals(white, result.getPixel(41, 44))
                    assertEquals(Color.BLACK, result.getPixel(62, 62))
                } finally { result.recycle() }
            }
        } finally {
            page.recycle()
            mask.recycle()
        }
    }

    @Test fun obsoleteComplexPageStopsBeforeLoadingTheInpaintingModel() = runBlocking<Unit> {
        val page = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        for (y in 0 until 40) for (x in 0 until 40) {
            page.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
            if (x in 17..22 && y in 17..22) mask.setPixel(x, y, Color.WHITE)
        }
        val line = TextLine(listOf(Pt(14f, 14f), Pt(26f, 14f), Pt(26f, 26f), Pt(14f, 26f)), 1f)
        val region = TextRegion(listOf(line), "h")
        val request = TranslationPageRequest(0, false)
        try {
            Inpainter("/missing/model.param", InpainterConfig(tileSize = 32, maskRadius = 2, regionPad = 3)).use {
                var checks = 0
                val failure = runCatching {
                    it.inpaint(page, listOf(region), mask) {
                        if (++checks == 3) request.supersede()
                        request.ensureRelevant()
                    }.recycle()
                }.exceptionOrNull()
                assertTrue(region.onArt)
                assertTrue(failure is SupersededTranslationPage)
            }
        } finally {
            page.recycle()
            mask.recycle()
        }
    }

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
