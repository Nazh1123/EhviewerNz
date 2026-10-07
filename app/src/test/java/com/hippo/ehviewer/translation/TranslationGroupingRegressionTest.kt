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
class TranslationGroupingRegressionTest {
    private fun line(x: Float, y: Float, width: Float, text: String) = TextLine(
        listOf(Pt(x, y), Pt(x + width, y), Pt(x + width, y + 20), Pt(x, y + 20)), 1f,
    ).apply { this.text = text }

    private fun mask(page: Bitmap) = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.BLACK) }

    private fun pipeline(lines: List<TextLine>, mask: Bitmap) = ResumablePipeline(
        detect = { Detection(lines, mask) }, recognize = { _, _ -> },
        inpaint = { _, _, _ -> error("Preparation must not inpaint") },
        translator = object : Translator {
            override suspend fun translate(queries: List<String>): List<String> = error("Preparation must not translate")
        },
        cfg = EngineConfig(), release = {}, warm = {}, sourceSeparator = " ",
    )

    @Test fun closedBubbleKeepsItsDialogueTogetherRegardlessOfOcrIterationOrder() = runBlocking {
        val page = Bitmap.createBitmap(400, 320, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xff404040.toInt()) }
        try {
            Canvas(page).drawCircle(180f, 160f, 90f, Paint().apply { color = Color.WHITE })
            for (reverse in listOf(false, true)) {
                // The narrow line's former search window cut off the bottom of this bubble.
                val lines = listOf(line(160f, 110f, 40f, "first half"), line(140f, 140f, 80f, "second half"))
                    .let { if (reverse) it.reversed() else it }
                pipeline(lines, mask(page)).use { engine ->
                    engine.prepare(page).use { prepared ->
                        assertEquals("first half second half", prepared.regions.single().sourceText)
                    }
                }
            }
        } finally { page.recycle() }
    }

    @Test fun connectedBubbleLobesKeepIndependentDialoguePositions() = runBlocking {
        val page = Bitmap.createBitmap(400, 320, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xff404040.toInt()) }
        try {
            val canvas = Canvas(page); val paint = Paint().apply { color = Color.WHITE }
            canvas.drawCircle(100f, 150f, 60f, paint)
            canvas.drawCircle(217f, 150f, 60f, paint)
            val lines = listOf(line(50f, 140f, 100f, "left dialogue"), line(187f, 140f, 60f, "right dialogue"))
            pipeline(lines, mask(page)).use { engine ->
                engine.prepare(page).use { prepared ->
                    assertEquals(listOf("left dialogue", "right dialogue"), prepared.regions.map { it.sourceText })
                    assertEquals(listOf(100f, 217f), prepared.regions.map { (it.x0 + it.x1) / 2 })
                }
            }
        } finally { page.recycle() }
    }

    @Test fun connectedWhiteBubblesWithPaleEdgesUseFlatFillWithoutLoadingAot() = runBlocking {
        val page = Bitmap.createBitmap(480, 400, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xff404040.toInt()) }
        val textMask = mask(page)
        try {
            val canvas = Canvas(page)
            for ((x, y) in listOf(100f to 100f, 230f to 180f)) {
                canvas.drawCircle(x, y, 80f, Paint().apply { color = 0xfff5f5f5.toInt() })
                canvas.drawCircle(x, y, 79f, Paint().apply { color = Color.WHITE })
                for (yy in y.toInt() - 2..y.toInt() + 2) for (xx in x.toInt() - 10..x.toInt() + 10) {
                    page.setPixel(xx, yy, Color.BLACK); textMask.setPixel(xx, yy, Color.WHITE)
                }
            }
            val lines = listOf(line(45f, 90f, 110f, "first dialogue"), line(175f, 170f, 110f, "second dialogue"))
            pipeline(lines, textMask).use { engine ->
                engine.prepare(page).use { prepared ->
                    Inpainter("/missing/model.param").use { inpainter ->
                        val cleaned = inpainter.inpaint(page, prepared.regions, prepared.mask)
                        try {
                            assertEquals(2, prepared.regions.size)
                            assertTrue(prepared.regions.none { it.onArt })
                            assertEquals(Color.WHITE, cleaned.getPixel(100, 100))
                            assertEquals(Color.WHITE, cleaned.getPixel(230, 180))
                            assertEquals(page.getPixel(0, 0), cleaned.getPixel(0, 0))
                        } finally { cleaned.recycle() }
                    }
                }
            }
        } finally { if (!textMask.isRecycled) textMask.recycle(); page.recycle() }
    }
}
