package com.hippo.ehviewer.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hippo.ehviewer.translation.engine.PageResult
import com.hippo.ehviewer.translation.engine.EngineTrace
import java.io.File

/** Explicit opt-in: calls the phone's running llama server, without changing saved settings. */
@RunWith(AndroidJUnit4::class)
class ApiTranslationDeviceTest {
    @Test fun translatesPageWithLocalApiAndExistingOcrModels() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testLocalApi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = TranslationModels(context)
        assertTrue("Prepare OCR models first", models.ready())
        val options = TranslationOptions(backend = TranslationBackend.LLM_API, apiModel = "hy-mt")
        val output = File(context.getExternalFilesDir(null), "translation-smoke").apply { mkdirs() }
        val trace = File(output, "llm-trace.txt").apply { writeText("Starting local API pipeline\n") }
        EngineTrace.sink = { synchronized(trace) { trace.appendText("$it\n") } }
        val watchdog = Thread {
            try {
                Thread.sleep(30000)
                File(output, "llm-stacks.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") {
                    "${it.key.name} ${it.key.state}\n${it.value.joinToString("\n")}" })
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        val input = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
        Canvas(input).apply {
            drawText("こんにちは。", 120f, 260f, paint)
            drawText("明日は学校へ行きます。", 120f, 480f, paint)
        }
        val original = input.copy(Bitmap.Config.ARGB_8888, false)
        try {
            ApiTranslator(options).use { translator ->
                TranslationEngineFactory.create(context, models.verified(), options, translator).use { engine ->
                    val result = engine.translatePage(input)
                    assertTrue("Expected translated page: $result", result is PageResult.Translated)
                    result as PageResult.Translated
                    try {
                        assertTrue(result.stats.kept > 0)
                        assertTrue(original.sameAs(input))
                        assertFalse(input.sameAs(result.page))
                        File(output, "llm-translated.png").outputStream().use { result.page.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        android.util.Log.i("ApiTranslationSmoke", result.stats.toString())
                    } finally { result.page.recycle() }
                }
            }
        } finally { watchdog.interrupt(); EngineTrace.sink = null; input.recycle(); original.recycle() }
    }

    @Test fun translatesWithPhoneLocalApi() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testLocalApi") == "true")
        val source = listOf("こんにちは。", "明日は学校へ行きます。")
        ApiTranslator(TranslationOptions(backend = TranslationBackend.LLM_API, apiModel = "hy-mt")).use {
            val translations = it.translate(source)
            assertEquals(source.size, translations.size)
            translations.forEachIndexed { index, text ->
                assertTrue(text.isNotBlank())
                assertNotEquals(source[index], text)
                assertFalse(text.contains("<think>"))
            }
            android.util.Log.i("ApiTranslationSmoke", translations.joinToString(" / "))
        }
    }
}
