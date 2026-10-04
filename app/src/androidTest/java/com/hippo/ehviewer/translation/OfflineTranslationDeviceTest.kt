package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.hippo.ehviewer.translation.engine.PageResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in preparation downloads ~300 MB. The smoke test itself never requests a download. */
@RunWith(AndroidJUnit4::class)
class OfflineTranslationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun prepareModels() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prepareModels") == "true")
        val models = TranslationModels(context)
        assertTrue("Import image models first", models.ready())
        models.verified()
        OfflineTranslator("zh").use { it.prepare() }
        assertTrue(OfflineTranslator.isReady("zh"))
    }

    @Test fun translatesJapanesePageOnDevice() = runBlocking(Dispatchers.IO) {
        val models = TranslationModels(context)
        assertTrue("Run prepareModels on Wi-Fi first", models.ready())
        assertTrue("Japanese and Chinese language models required", OfflineTranslator.isReady("zh"))
        val input = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
        Canvas(input).apply {
            drawText("こんにちは。", 120f, 260f, paint)
            drawText("明日は学校へ行きます。", 120f, 480f, paint)
            drawText("今日はいい天気ですね。", 120f, 700f, paint)
        }
        val baseline = input.copy(Bitmap.Config.ARGB_8888, false)
        val artifactDir = File(context.getExternalFilesDir(null), "translation-smoke").apply { mkdirs() }
        File(artifactDir, "original.png").outputStream().use { input.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            OfflineTranslator("zh").use { translator ->
                val source = "明日は学校へ行きます。"
                val result = translator.translate(listOf(source)).single()
                assertTrue(result.isNotBlank())
                assertNotEquals(source, result)
                TranslationEngineFactory.create(context, models.verified(),
                    TranslationOptions(backend = TranslationBackend.ML_KIT), translator).use { engine ->
                    val page = engine.translatePage(input)
                    assertTrue("Expected translated page, got $page", page is PageResult.Translated)
                    page as PageResult.Translated
                    try {
                        assertTrue("OCR must find Japanese text", page.stats.lines > 0)
                        assertTrue("At least one translated region", page.stats.kept > 0)
                        assertFalse(input.sameAs(page.page))
                        assertTrue("Original bitmap must stay intact", input.sameAs(baseline))
                        File(artifactDir, "translated.png").outputStream().use { page.page.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(artifactDir, "report.txt").writeText("$source -> $result\n${page.stats}\nOriginal unchanged: true\n")
                    } finally { page.page.recycle() }
                }
            }
        } finally { input.recycle(); baseline.recycle() }
    }
}
