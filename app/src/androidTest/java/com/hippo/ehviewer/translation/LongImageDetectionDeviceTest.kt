package com.hippo.ehviewer.translation

import android.graphics.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongImageDetectionDeviceTest {
    @Test fun realDetectorAndOcrKeepDialogueAtWindowSeams() = runBlocking<Unit>(Dispatchers.Default) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testLongDetection") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = TranslationModels(context).verified()
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val page = Bitmap.createBitmap(800, 7200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
        val positions = listOf(1200f, 3000f, 5100f, 6800f)
        Canvas(page).apply { positions.forEach { drawText("こんにちは。", 140f, it, paint) } }
        try {
            Detector(models.detectorNcnn).use { detector ->
                val start = System.nanoTime(); val detected = detector.detect(page)
                try {
                    Ocr(models.ocr, alphabet).use { ocr -> ocr.recognize(page, detected.lines) }
                    val greetings = detected.lines.filter { "こんにちは" in it.text }
                    Log.i("LongImageDetection", "lines=${detected.lines.size} greetings=${greetings.size} ms=${(System.nanoTime()-start)/1_000_000}")
                    assertEquals("Missing or duplicated seam text: ${detected.lines.map { it.text }}", positions.size, greetings.size)
                    for (y in positions) assertEquals(1, greetings.count { line -> kotlin.math.abs(line.quad.map(Pt::y).average() - y) < 100 })
                    assertEquals(page.width, detected.textMask.width); assertEquals(page.height, detected.textMask.height)
                } finally { detected.textMask.recycle() }
            }
        } finally { page.recycle() }
    }
}
