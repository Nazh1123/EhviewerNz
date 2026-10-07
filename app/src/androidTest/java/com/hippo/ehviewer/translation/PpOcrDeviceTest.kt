package com.hippo.ehviewer.translation

import android.graphics.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PpOcrDeviceTest {
    @Test fun officialModelsRecognizeEnglishChineseAndKoreanOnCpu() = runBlocking<Unit>(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testPpOcr") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = args.getString("ppOcrDirectory") ?: "/data/local/tmp/ehnz-ppocr"
        for ((language, text) in listOf("en" to "HELLO WORLD", "zh" to "你好世界", "ko" to "안녕하세요")) {
            val path = File(directory, "$language-inference.onnx")
            assertTrue("Missing test model $path", path.isFile)
            val dictionary = JSONArray(context.assets.open("ppocr-$language.json").bufferedReader().use { it.readText() })
            val alphabet = (0 until dictionary.length()).map(dictionary::getString)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
            val width = kotlin.math.ceil(paint.measureText(text)).toInt()
            val page = Bitmap.createBitmap(width + 48, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            Canvas(page).drawText(text, 24f, 80f, paint)
            val line = TextLine(listOf(Pt(24f, 80f + paint.fontMetrics.ascent), Pt((width + 24).toFloat(), 80f + paint.fontMetrics.ascent),
                Pt((width + 24).toFloat(), 80f + paint.fontMetrics.descent), Pt(24f, 80f + paint.fontMetrics.descent)), 1f)
            val start = System.nanoTime()
            try {
                PpOcr(path.path, alphabet).use { ocr ->
                    val loaded = System.nanoTime()
                    repeat(2) { pass ->
                        val before = System.nanoTime(); ocr.recognize(page, listOf(line))
                        Log.i("PpOcrDeviceTest", "$language pass=$pass loadMs=${(loaded-start)/1_000_000} ocrMs=${(System.nanoTime()-before)/1_000_000} text=${line.text}")
                        assertEquals(text, line.text.trim())
                    }
                }
            } finally { page.recycle() }
        }
    }
}
