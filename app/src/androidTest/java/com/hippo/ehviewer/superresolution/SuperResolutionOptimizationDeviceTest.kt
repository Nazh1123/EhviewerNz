package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.engine.*
import com.hippo.ehviewer.ui.SettingsActivity
import java.io.File
import java.security.MessageDigest
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Fixed model/image/128-pixel tiles, interleaved variants, per-page load and render measured separately. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SuperResolutionOptimizationDeviceTest {
    @Test fun compareRetainedUpstreamAndOptimizedModels() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.getExternalFilesDir(null), "sr-optimization")
        val reportFile = File(folder, "report.json")
        val report = JSONObject(reportFile.readText())
        assertEquals("Run the full comparison first", "PASS", report.getString("result"))
        val timings = report.getJSONArray("timings")
        report.put("warmReferenceScope", "separate foreground process; both models retained; alternating order; same 1280x1920 fixture")
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity"
        ).use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val page = Bitmap.createBitmap(1280, 1920, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val canvas = Canvas(this)
            val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
            for (y in 64 until height step 96) canvas.drawLine(32f, y.toFloat(), width - 32f, y.toFloat(), paint)
        }
        try {
            withContext(Dispatchers.Default) {
                val files = SuperResolutionModels(context).files()
                val budget = SuperResolutionMemoryBudget.current(context)
                SuperResolution(files, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP16).use { optimized ->
                    SuperResolutionReference.Model(files).use { upstream ->
                        repeat(3) { round ->
                            val order = if (round % 2 == 0) listOf("upstreamReuse16", "reuse16Direct")
                                else listOf("reuse16Direct", "upstreamReuse16")
                            for (variant in order) {
                                val begin = SystemClock.elapsedRealtime()
                                val result = if (variant == "upstreamReuse16") upstream.upscale(page)
                                    else optimized.upscale(page, budget)
                                val elapsed = SystemClock.elapsedRealtime() - begin
                                result.recycle()
                                assertTrue(optimized.usingVulkan); assertFalse(optimized.fellBack)
                                timings.put(JSONObject().put("name", "page_${round}_$variant").put("loadMs", 0)
                                    .put("renderAndCloseMs", elapsed).put("totalMs", elapsed))
                                reportFile.writeText(report.toString(2))
                            }
                        }
                    }
                }
            }
            report.put("warmReferenceResult", "PASS"); reportFile.writeText(report.toString(2))
        } finally {
            page.recycle()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun comparePipelinesPrecisionAndReuse() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.getExternalFilesDir(null), "sr-optimization").apply { mkdirs() }
        val timings = JSONArray(); val differences = JSONArray()
        val report = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("gpu", SuperResolution.vulkanDeviceName())
            .put("tiles", 128).put("fixture", "synthetic 1280x1920 black lines; matches previous benchmark")
            .put("timingScope", "model load + render + close; excludes decoding, encoding, GL, translation")
            .put("timings", timings).put("differences", differences)
        fun flush() { File(folder, "report.json").writeText(report.toString(2)) }
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity"
        ).use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            val files = withContext(Dispatchers.IO) { SuperResolutionModels(context).files() }
            val budget = SuperResolutionMemoryBudget.current(context)
            fun save(bitmap: Bitmap, name: String) = File(folder, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            fun compare(reference: Bitmap, actual: Bitmap, label: String, fp32: Boolean, opaqueSource: Boolean = true) {
                assertEquals(reference.width, actual.width); assertEquals(reference.height, actual.height)
                val a = pixels(reference); val b = pixels(actual)
                val histogram = LongArray(256)
                var sum = 0L; var square = 0L; var seamSum = 0L; var seamCount = 0L
                for (i in a.indices) {
                    assertEquals("Opaque output", 255, b[i] ushr 24)
                    val x = i % reference.width; val y = i / reference.width
                    val seam = x % 256 in listOf(0, 1, 254, 255) || y % 256 in listOf(0, 1, 254, 255)
                    for (shift in intArrayOf(0, 8, 16)) {
                        val delta = abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
                        histogram[delta]++; sum += delta; square += delta.toLong() * delta
                        if (seam) { seamSum += delta; seamCount++ }
                    }
                }
                val channels = a.size.toLong() * 3
                val maximum = histogram.indexOfLast { it != 0L }
                val mean = sum.toDouble() / channels
                val mse = square.toDouble() / channels
                val psnr = if (mse == 0.0) 100.0 else 10 * log10(255.0 * 255 / mse)
                val item = JSONObject().put("name", label).put("maximum", maximum).put("mean", mean)
                    .put("psnrDb", psnr).put("changedFraction", 1 - histogram[0].toDouble() / channels)
                    .put("seamMean", seamSum.toDouble() / maxOf(1L, seamCount))
                differences.put(item); flush()
                // FP16 gets measured, not silently judged equivalent to FP32.
                if (fp32) {
                    assertTrue("$label max=$maximum", maximum <= 2)
                    // Android's rounded unpremultiply adds a separate quantization step
                    // for translucent pixels. Keep the same per-channel bound, and
                    // report its measured mean separately from opaque reader pages.
                    if (opaqueSource) assertTrue("$label mean=$mean", mean < 0.02)
                } else {
                    assertTrue("$label FP16 max=$maximum", maximum <= 8)
                    assertTrue("$label FP16 mean=$mean", mean <= 1.5)
                    assertTrue("$label FP16 PSNR=$psnr", psnr >= 43)
                }
            }
            suspend fun render(source: Bitmap, backend: SuperResolutionBackend, pipeline: SuperResolutionPipeline,
                               name: String): Bitmap = withContext(Dispatchers.Default) {
                val started = SystemClock.elapsedRealtime()
                val engine = SuperResolution(files, backend, pipeline)
                val loaded = SystemClock.elapsedRealtime()
                try {
                    val result = engine.upscale(source, budget)
                    assertEquals(backend == SuperResolutionBackend.VULKAN, engine.usingVulkan)
                    assertFalse(engine.fellBack)
                    engine.close()
                    val ended = SystemClock.elapsedRealtime()
                    timings.put(JSONObject().put("name", name).put("loadMs", loaded - started)
                        .put("renderAndCloseMs", ended - loaded).put("totalMs", ended - started))
                    flush()
                    result
                } finally { engine.close() }
            }
            suspend fun reference(source: Bitmap, name: String): Bitmap = withContext(Dispatchers.Default) {
                val begin = SystemClock.elapsedRealtime()
                SuperResolutionReference.Model(files).use { model ->
                    val loaded = SystemClock.elapsedRealtime()
                    val result = model.upscale(source)
                    model.close()
                    timings.put(JSONObject().put("name", name).put("loadMs", loaded - begin)
                        .put("renderAndCloseMs", SystemClock.elapsedRealtime() - loaded)
                        .put("totalMs", SystemClock.elapsedRealtime() - begin))
                    flush(); result
                }
            }

            val small = qualityFixture()
            try {
                save(small, "quality-input")
                val original = pixels(small)
                val cpu = render(small, SuperResolutionBackend.CPU, SuperResolutionPipeline.LEGACY_FP32, "quality_cpu")
                try {
                    save(cpu, "quality-cpu")
                    for (pipeline in SuperResolutionPipeline.entries) {
                        render(small, SuperResolutionBackend.VULKAN, pipeline, "quality_$pipeline").let { result ->
                            try { save(result, "quality-$pipeline"); compare(cpu, result, "quality_$pipeline", pipeline != SuperResolutionPipeline.GPU_FP16) }
                            finally { result.recycle() }
                        }
                    }
                    reference(small, "quality_upstream16").let { result ->
                        try { save(result, "quality-upstream16"); compare(cpu, result, "quality_upstream16", false) }
                        finally { result.recycle() }
                    }
                    assertArrayEquals(original, pixels(small))
                } finally { cpu.recycle() }
            } finally { small.recycle() }

            // Semi-transparent Android bitmaps store premultiplied channels; composition must match CPU.
            val alpha = Bitmap.createBitmap(187, 135, Bitmap.Config.ARGB_8888)
            try {
                val values = IntArray(alpha.width * alpha.height) { i -> Color.argb(i % 256, i * 17 % 256, i * 29 % 256, i * 43 % 256) }
                alpha.setPixels(values, 0, alpha.width, 0, 0, alpha.width, alpha.height)
                val cpu = render(alpha, SuperResolutionBackend.CPU, SuperResolutionPipeline.LEGACY_FP32, "alpha_cpu")
                val gpu = render(alpha, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP32, "alpha_gpu32")
                try { compare(cpu, gpu, "alpha_gpu32", true, opaqueSource = false) } finally { cpu.recycle(); gpu.recycle() }
            } finally { alpha.recycle() }

            val publicFile = File(folder, "public.jpg")
            assertTrue("Push the pinned upstream images/0.jpg into $publicFile", publicFile.isFile)
            report.put("publicSha256", MessageDigest.getInstance("SHA-256").digest(publicFile.readBytes()).joinToString("") { "%02x".format(it) })
            val publicImage = checkNotNull(BitmapFactory.decodeFile(publicFile.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }))
            try {
                report.put("publicDimensions", "${publicImage.width}x${publicImage.height}")
                val original = pixels(publicImage)
                val cpu = render(publicImage, SuperResolutionBackend.CPU, SuperResolutionPipeline.LEGACY_FP32, "public_cpu")
                try {
                    save(cpu, "public-cpu")
                    for (pipeline in listOf(SuperResolutionPipeline.GPU_FP32, SuperResolutionPipeline.GPU_FP16)) {
                        val gpu = render(publicImage, SuperResolutionBackend.VULKAN, pipeline, "public_$pipeline")
                        try { save(gpu, "public-$pipeline"); compare(cpu, gpu, "public_$pipeline", pipeline == SuperResolutionPipeline.GPU_FP32) }
                        finally { gpu.recycle() }
                    }
                    reference(publicImage, "public_upstream16").let { result ->
                        try { save(result, "public-upstream16"); compare(cpu, result, "public_upstream16", false) }
                        finally { result.recycle() }
                    }
                    assertArrayEquals(original, pixels(publicImage))
                } finally { cpu.recycle() }
            } finally { publicImage.recycle() }

            val page = Bitmap.createBitmap(1280, 1920, Bitmap.Config.ARGB_8888)
            try {
                page.eraseColor(Color.WHITE)
                val canvas = Canvas(page)
                val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
                for (y in 64 until page.height step 96) canvas.drawLine(32f, y.toFloat(), page.width - 32f, y.toFloat(), paint)
                val started = SystemClock.elapsedRealtime()
                val cache = SuperResolutionSessionCache<SuperResolution>()
                val reused = withContext(Dispatchers.Default) { cache.acquire("gpu16") {
                    SuperResolution(files, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP16)
                } }
                report.put("reusedLoadMs", SystemClock.elapsedRealtime() - started)
                val cache32 = SuperResolutionSessionCache<SuperResolution>()
                val start32 = SystemClock.elapsedRealtime()
                val reused32 = withContext(Dispatchers.Default) { cache32.acquire("gpu32") {
                    SuperResolution(files, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP32)
                } }
                report.put("reused32LoadMs", SystemClock.elapsedRealtime() - start32)
                try {
                    repeat(3) { round ->
                        val variants = listOf("legacy", "gpu32", "reuse32", "gpu16", "reuse16", "upstream16")
                        val order = variants.drop(round) + variants.take(round)
                        for (variant in order) {
                            if (variant == "reuse16" || variant == "reuse32") {
                                val engine = if (variant == "reuse16") reused else reused32
                                val session = if (variant == "reuse16") cache else cache32
                                val key = if (variant == "reuse16") "gpu16" else "gpu32"
                                assertSame(engine, session.acquire(key) { error("Reader session must reuse the model") })
                                val begin = SystemClock.elapsedRealtime()
                                withContext(Dispatchers.Default) { engine.upscale(page, budget) }.recycle()
                                assertTrue(engine.usingVulkan); assertFalse(engine.fellBack)
                                timings.put(JSONObject().put("name", "page_${round}_$variant").put("loadMs", 0)
                                    .put("renderAndCloseMs", SystemClock.elapsedRealtime() - begin)
                                    .put("totalMs", SystemClock.elapsedRealtime() - begin))
                                flush()
                            } else if (variant == "upstream16") {
                                reference(page, "page_${round}_upstream16").recycle()
                            } else {
                                val pipeline = when (variant) {
                                    "legacy" -> SuperResolutionPipeline.LEGACY_FP32
                                    "gpu32" -> SuperResolutionPipeline.GPU_FP32
                                    else -> SuperResolutionPipeline.GPU_FP16
                                }
                                render(page, SuperResolutionBackend.VULKAN, pipeline, "page_${round}_$variant").recycle()
                            }
                        }
                        delay(1000)
                    }
                } finally { cache.close(); cache32.close() }
            } finally { page.recycle() }
            report.put("result", "PASS"); flush()
        } catch (error: Throwable) {
            report.put("failure", error.stackTraceToString()); flush(); throw error
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun qualityFixture(): Bitmap = Bitmap.createBitmap(513, 385, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (i in 0..20) {
            paint.strokeWidth = 0.35f + i * 0.12f
            canvas.drawLine(5f, 8f + i * 4, 505f, 8f + i * 6, paint)
        }
        paint.textSize = 17f
        canvas.drawText("漫画细线 网点 测试 0123456789", 7f, 155f, paint)
        paint.textSize = 9f
        canvas.drawText("Small text: Il1 O0 田由甲申 永", 7f, 174f, paint)
        for (row in 0..19) for (column in 0..100) {
            paint.color = Color.BLACK
            canvas.drawCircle(4f + column * 5, 190f + row * 4, 0.25f + row * 0.06f, paint)
        }
        for (x in 0 until width) {
            paint.color = Color.rgb(x * 255 / width, x * 127 / width, 255 - x * 255 / width)
            canvas.drawLine(x.toFloat(), 280f, x.toFloat(), 334f, paint)
        }
        paint.color = Color.BLACK; paint.strokeWidth = 1f
        for (x in 0 until width step 3) canvas.drawLine(x.toFloat(), 345f, (x + 20).toFloat(), 384f, paint)
    }
}
