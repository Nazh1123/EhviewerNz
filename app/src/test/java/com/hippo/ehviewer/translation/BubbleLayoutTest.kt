package com.hippo.ehviewer.translation

import android.graphics.*
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleLayoutTest {
    @Test fun bubbleBorderIsExcludedFromBackgroundSamplingAndRemovalDilation() = runBlocking {
        val page = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff404040.toInt()) }
        val mask = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        Canvas(page).drawOval(55f, 60f, 145f, 140f, Paint().apply { color = Color.WHITE })
        val line = TextLine(listOf(Pt(68f, 88f), Pt(132f, 88f), Pt(132f, 112f), Pt(68f, 112f)), 1f).apply { text = "original" }
        for (y in 94..105) for (x in 70..128 step 6) { page.setPixel(x, y, Color.BLACK); mask.setPixel(x, y, Color.WHITE) }
        try {
            BubbleBoundaries.attach(page, mask, listOf(line))
            assertNotNull(line.bubble)
            val region = Grouping.group(listOf(line)).single()
            Inpainter("/missing/model.param", InpainterConfig(maskRadius = 12, regionPad = 30)).use {
                val cleaned = it.inpaint(page, listOf(region), mask)
                try {
                    assertFalse(region.onArt)
                    for (y in 0 until page.height) for (x in 0 until page.width) {
                        if (page.getPixel(x, y) == 0xff404040.toInt()) assertEquals(page.getPixel(x, y), cleaned.getPixel(x, y))
                    }
                    assertEquals(Color.WHITE, cleaned.getPixel(76, 99))
                } finally { cleaned.recycle() }
            }
        } finally { page.recycle(); mask.recycle() }
    }
    @Test fun extractedBubbleGroupsItsLinesAndLongTranslationKeepsOutlineAndSurroundingArtIntact() {
        val page = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff909090.toInt()) }
        val mask = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(page); val fill = Paint().apply { color = Color.WHITE }
        canvas.drawOval(60f, 70f, 170f, 190f, fill)
        canvas.drawOval(178f, 100f, 288f, 220f, fill)
        fun line(left: Float, top: Float) = TextLine(listOf(Pt(left, top), Pt(left + 60, top),
            Pt(left + 60, top + 16), Pt(left, top + 16)), 1f).apply { text = "original" }
        val lines = listOf(line(82f, 100f), line(82f, 144f), line(202f, 134f))
        try {
            BubbleBoundaries.attach(page, mask, lines)
            assertNotNull(lines[0].bubble); assertSame(lines[0].bubble, lines[1].bubble)
            assertNotNull(lines[2].bubble); assertNotSame(lines[0].bubble, lines[2].bubble)
            val regions = Grouping.group(lines, " ")
            assertEquals(2, regions.size)
            regions.forEach { it.translatedText = "This is a much longer translation that must stay inside its own speech bubble." }
            val result = Renderer.render(page, regions)
            try {
                var changed = 0
                for (y in 0 until page.height) for (x in 0 until page.width) {
                    if (result.getPixel(x, y) != page.getPixel(x, y)) {
                        changed++
                        assertEquals("Text touched bubble boundary or artwork", Color.WHITE, page.getPixel(x, y))
                    }
                }
                assertTrue("No translation rendered", changed > 20)
                assertTrue("Original was mutated", page.getPixel(0, 0) == 0xff909090.toInt())
                System.getenv("TRANSLATION_TEST_ARTIFACTS")?.let { directory ->
                    java.io.File(directory).mkdirs()
                    java.io.File(directory, "bubble-layout.png").outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { result.recycle() }
        } finally { page.recycle(); mask.recycle() }
    }
}
