package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererLayoutTest {
    @Test fun unchangedOcrCandidatesAndFailedRegionsPreserveOriginalArtInBothRenderPaths() = runBlocking<Unit> {
        for (overlapLayout in listOf(false, true)) for (failed in listOf(false, true)) {
            val original = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            Canvas(original).apply {
                val ink = Paint().apply { color = Color.BLACK }
                drawCircle(120f, 40f, 5f, ink)
                drawRect(96f, 35f, 100f, 52f, ink)
            }
            fun bubble(text: String, x0: Float, x1: Float) = TextRegion(listOf(
                TextLine(listOf(Pt(x0, 30f), Pt(x1, 30f), Pt(x1, 50f), Pt(x0, 50f)), 1f).apply { this.text = text }
            ), "h")
            val regions = listOf(bubble("Hello", 20f, 100f), bubble("ん。", 110f, 130f))
            val translator = object : DetailedTranslator {
                override suspend fun translateDetailed(queries: List<String>) = LlmTranslator.TranslateResult(
                    listOf("你好", queries[1]), error = if (failed) "Missing region" else null,
                    missingIndices = if (failed) setOf(1) else emptySet())
            }
            try {
                ResumablePipeline(detect = { error("Already prepared") }, recognize = { _, _ -> error("Already prepared") },
                    inpaint = { _, _, _ -> Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) } },
                    translator = translator, cfg = EngineConfig(), release = {}, warm = {},
                    overlapInpainting = false, overlapLayout = overlapLayout).use { pipeline ->
                    PreparedPage(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888), regions, 2, 0, 0).use { prepared ->
                        val result = pipeline.translatePrepared(original, prepared, false) as PageResult.Translated
                        try {
                            assertEquals(1, result.stats.kept)
                            assertEquals(Color.BLACK, result.page.getPixel(120, 40))
                            assertEquals("Overlapping preservation must not restore translated source glyphs",
                                Color.WHITE, result.page.getPixel(98, 40))
                            assertEquals("A tight OCR box must also exclude the original glyph's descender",
                                Color.WHITE, result.page.getPixel(98, 51))
                            for (y in 30..50) for (x in 110..130)
                                assertEquals("Preserve artwork instead of drawing an OCR guess", original.getPixel(x, y), result.page.getPixel(x, y))
                            assertFalse("The valid dialogue must still be translated", original.sameAs(result.page))
                        } finally { result.page.recycle() }
                    }
                }
            } finally { original.recycle() }
        }
    }

    private fun region(x: Float, direction: String, angle: Float, text: String) = TextRegion(
        listOf(TextLine(listOf(Pt(x, 30f), Pt(x + 90f, 30f), Pt(x + 90f, 200f), Pt(x, 200f)), 1f)
            .apply { this.text = "source" }), direction, angle, x + 45f, 115f, 90f, 170f,
    ).apply { translatedText = text }

    @Test fun recordedLayoutMatchesImmediateRendererAcrossDirectionsRotationAndBorders() {
        val regions = listOf(region(20f, "h", 0f, "Hello world\n你好！"),
            region(160f, "v", 13f, "测试2020（AB）!?结束"))
        for (orientation in TextOrientation.entries) for (border in listOf(false, true))
            for (colorMode in listOf("mono", "auto")) {
                val cfg = RenderConfig(orientation = orientation, fontBorder = border, colorMode = colorMode)
                val layout = Renderer.prepareLayout(regions, cfg)
                for (background in listOf(Color.WHITE, Color.BLACK)) {
                    val page = Bitmap.createBitmap(300, 250, Bitmap.Config.ARGB_8888).apply { eraseColor(background) }
                    // Inpainting decides this after layout has already been prepared.
                    regions[0].onArt = background == Color.BLACK
                    regions[1].onArt = background != Color.BLACK
                    val expected = Renderer.render(page, regions, cfg)
                    val actual = Renderer.compose(page, layout)
                    try {
                        assertTrue("$orientation border=$border $colorMode bg=$background", expected.sameAs(actual))
                        if (background == Color.WHITE)
                            assertFalse("Fixture must draw visible glyphs", actual.sameAs(page))
                        assertEquals(background, page.getPixel(0, 0))
                    } finally { expected.recycle(); actual.recycle(); page.recycle() }
                }
            }
    }

    @Test fun compositionResolvesBackgroundColorAfterLayoutPreparation() {
        val regions = listOf(region(20f, "h", 0f, "Hello"))
        val cfg = RenderConfig(fontBorder = false, colorMode = "auto")
        val layout = Renderer.prepareLayout(regions, cfg)
        val page = Bitmap.createBitmap(150, 250, Bitmap.Config.ARGB_8888)
        try {
            for ((background, textColor) in listOf(Color.WHITE to Color.BLACK, Color.BLACK to Color.WHITE)) {
                page.eraseColor(background)
                val result = Renderer.compose(page, layout)
                try {
                    val pixels = IntArray(result.width * result.height)
                    result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
                    assertTrue("Text must contrast with the completed background", pixels.contains(textColor))
                } finally { result.recycle() }
            }
        } finally { page.recycle() }
    }
}
