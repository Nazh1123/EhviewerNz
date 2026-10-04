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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Reflection lets one fixture measure the installed baseline and its replacement. */
@RunWith(AndroidJUnit4::class)
class ImageEngineDeviceTest {
    @Test fun modelInferencePreservesSourceAndRecognizesBothReadingDirections() = runBlocking<Unit>(Dispatchers.Default) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testImageEngine") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent(context, com.hippo.ehviewer.ui.splash.SplashActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefix = args.getString("enginePackage") ?: "com.hippo.ehviewer.translation.engine"
        val models = File(context.noBackupFilesDir, "translation-models")
        fun type(name: String) = context.classLoader.loadClass("$prefix.$name")
        fun model(name: String, file: String, vararg extra: Any): Any {
            val config = type("${name}Config").getConstructor().newInstance()
            val constructor = type(name).constructors.single { it.parameterTypes.size == extra.size + 2 }
            return constructor.newInstance(File(models, file).path, *extra, config)
        }
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val detector = model("Detector", "dbnet_detect.ncnn.param")
        val ocr = model("Ocr", "ocr_48px_ctc.ncnn.param", alphabet)
        val inpainter = model("Inpainter", "mit_aot_fixed512.ncnn.param")
        val page = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
        Canvas(page).apply {
            drawText("こんにちは。", 120f, 260f, paint)
            drawText("明日は学校へ行きます。", 120f, 480f, paint)
            "ありがとう".forEachIndexed { index, character -> drawText(character.toString(), 850f, 300f + index * 54f, paint) }
        }
        val original = page.copy(Bitmap.Config.ARGB_8888, false)
        val samples = JSONArray()
        val label = args.getString("label") ?: "reader"
        val directory = File(context.getExternalFilesDir(null), "translation-smoke").apply { mkdirs() }
        val watchdog = Thread {
            try {
                Thread.sleep(15000)
                File(directory, "image-$label-stacks.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") {
                    "${it.key.name}: ${it.key.state}\n${it.value.joinToString("\n") }"
                })
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        try {
            ocr.javaClass.getMethod("warmUp").invoke(ocr)
            repeat(3) { iteration ->
                val start = System.nanoTime()
                val detected = detector.javaClass.getMethod("detect", Bitmap::class.java).invoke(detector, page)
                val detectedAt = System.nanoTime()
                Log.i("ImageEngineTest", "$label $iteration detect ${(detectedAt - start) / 1_000_000}ms")
                @Suppress("UNCHECKED_CAST")
                val lines = detected.javaClass.getMethod("getLines").invoke(detected) as List<Any>
                val mask = detected.javaClass.getMethod("getTextMask").invoke(detected) as Bitmap
                try {
                    suspendCall(ocr, "recognize", page, lines)
                    val recognizedAt = System.nanoTime()
                    Log.i("ImageEngineTest", "$label $iteration OCR ${(recognizedAt - detectedAt) / 1_000_000}ms")
                    val texts = lines.map { it.javaClass.getMethod("getText").invoke(it) as String }
                    assertTrue("No text detected: $texts", lines.isNotEmpty())
                    assertTrue("Japanese greeting missing: $texts", texts.any { "こんにちは" in it })
                    assertTrue("School caption missing: $texts", texts.any { "学校" in it })
                    assertTrue("Vertical caption missing: $texts", texts.any { "ありがとう" in it })
                    val grouping = type("Grouping")
                    val method = grouping.methods.filter { it.name == "group" }.maxBy { it.parameterTypes.size }
                    val regions = if (method.parameterTypes.size == 2)
                        method.invoke(grouping.getField("INSTANCE").get(null), lines, "")
                    else method.invoke(grouping.getField("INSTANCE").get(null), lines)
                    val cleaned = suspendCall(inpainter, "inpaint", page, regions!!, mask) as Bitmap
                    val removedAt = System.nanoTime()
                    try {
                        assertTrue("Source image was mutated", original.sameAs(page))
                        assertFalse("Text removal returned original", page.sameAs(cleaned))
                        assertEquals(Color.WHITE, cleaned.getPixel(0, 0))
                        if (iteration == 0) {
                            File(directory, "image-$label-source.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            File(directory, "image-$label-cleaned.png").outputStream().use { cleaned.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    } finally { cleaned.recycle() }
                    val sample = JSONObject().put("iteration", iteration).put("texts", JSONArray(texts))
                        .put("detectMs", (detectedAt - start) / 1_000_000)
                        .put("ocrMs", (recognizedAt - detectedAt) / 1_000_000)
                        .put("inpaintMs", (removedAt - recognizedAt) / 1_000_000)
                    samples.put(sample)
                    Log.i("ImageEngineTest", sample.toString())
                } finally { mask.recycle() }
            }
        } finally {
            watchdog.interrupt()
            File(directory, "image-$label.json").writeText(JSONObject().put("engine", prefix).put("samples", samples).toString(2))
            for (loaded in listOf(inpainter, ocr, detector)) loaded.javaClass.getMethod("close").invoke(loaded)
            original.recycle(); page.recycle()
        }
    }

    private suspend fun suspendCall(receiver: Any, name: String, vararg args: Any): Any? = suspendCoroutine { continuation ->
        val method = receiver.javaClass.methods.single { it.name == name }
        val parameters = if (method.parameterTypes.size == args.size + 2) args.toList() + true else args.toList()
        val result = method.invoke(receiver, *parameters.toTypedArray(), continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
    }
}
