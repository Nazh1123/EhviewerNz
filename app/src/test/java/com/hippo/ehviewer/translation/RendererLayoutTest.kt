package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.hippo.ehviewer.translation.engine.*
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
