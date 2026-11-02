package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.preference.ListPreference
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.translation.engine.SuperResolution
import com.hippo.ehviewer.translation.engine.SuperResolutionBackend
import com.hippo.ehviewer.translation.engine.SuperResolutionPipeline
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.fragment.SuperResolutionFragment
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Real NCNN CPU/Vulkan execution; full output assembly is used only for small QA fixtures. */
@RunWith(AndroidJUnit4::class)
class SuperResolutionScaleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val folder get() = File(context.getExternalFilesDir(null), "sr-scale-validation").apply { mkdirs() }
    private fun save(name: String, bitmap: Bitmap) = File(folder, "$name.png").also { file ->
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun write(name: String, report: JSONObject) { File(folder, "$name.json").writeText(report.toString(2)) }
    private fun foreground(): SettingsActivity {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        return (instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity).also {
            instrumentation.runOnMainSync { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    private fun fixture(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = .8f; textSize = 17f }
        canvas.drawText("TEXT 123 漫画", 3f, 22f, paint)
        for (y in 31 until height step 13) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y + 19f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(width * .6f, height * .5f, minOf(width, height) * .27f, paint)
        paint.style = Paint.Style.FILL
        for (x in 0 until width) {
            val gray = x * 255 / width
            paint.color = Color.rgb(gray, gray, gray)
            canvas.drawRect(x.toFloat(), height - 17f, x + 1f, height.toFloat(), paint)
        }
    }
    private suspend fun process(source: File, mode: SuperResolutionScale, engine: SuperResolution,
                                label: String, grouped: Boolean = true): Pair<EnhancedTilePage, SuperResolutionPageMetrics> {
        val store = SuperResolutionStore(context, "scale-device-$label-${System.nanoTime()}",
            SuperResolutionOptions(scale = mode, persist = false))
        return SuperResolutionPageProcessor(context).process(source, store, store.key(source),
            SuperResolutionMemoryBudget.current(context), {},
            { bitmap, rect, callback -> engine.upscaleRegion(bitmap, rect.left, rect.top, rect.width(), rect.height(),
                SuperResolutionPageProcessor.BAND_BYTES, callback) }, {}, { false }, grouped)
    }

    @Test fun automaticSelectionAndNativeQualitySeams() = runBlocking<Unit> {
        val activity = foreground()
        val settings = SuperResolutionSettings(context)
        val oldScale = settings.preferences.getString(SuperResolutionSettings.SCALE, null)
        val cases = JSONArray()
        val violations = mutableListOf<String>()
        val report = JSONObject().put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("gpu", SuperResolution.vulkanDeviceName()).put("cases", cases)
        try {
            instrumentation.runOnMainSync {
                activity.supportFragmentManager.executePendingTransactions()
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SuperResolutionFragment
                val preference = checkNotNull(fragment.findPreference<ListPreference>(SuperResolutionSettings.SCALE))
                assertEquals(listOf("auto", "2", "4", "8"), preference.entryValues.map(CharSequence::toString))
                for (mode in SuperResolutionScale.entries) {
                    preference.value = mode.value
                    assertEquals(mode, settings.read().scale)
                }
                preference.value = "auto"
            }
            instrumentation.waitForIdleSync()
            delay(300) // Let the preference's next rendered frame reach the screenshot.
            instrumentation.uiAutomation.takeScreenshot()?.let { try { save("settings-auto", it) } finally { it.recycle() } }
            val thresholds = JSONArray()
            for ((shortSide, expected) in listOf(1 to 8, 149 to 8, 150 to 8, 299 to 8, 300 to 4, 599 to 4, 600 to 2, 1200 to 2)) {
                assertEquals(expected, settings.read().scale.resolve(shortSide, 1600))
                assertEquals(expected, settings.read().scale.resolve(1600, shortSide))
                thresholds.put(JSONObject().put("shortSide", shortSide).put("scale", expected))
            }
            report.put("thresholds", thresholds)
            val files = withContext(Dispatchers.IO) { SuperResolutionModels(context).files() }
            for (backend in listOf("gpu_fast", "gpu_quality", "cpu")) {
                val engine = withContext(Dispatchers.IO) { SuperResolution(files,
                    if (backend == "cpu") SuperResolutionBackend.CPU else SuperResolutionBackend.VULKAN,
                    if (backend == "gpu_fast") SuperResolutionPipeline.GPU_FP16 else SuperResolutionPipeline.GPU_FP32) }
                try {
                    if (backend == "gpu_fast") {
                        for (shortSide in listOf(600, 599, 300, 299, 150, 149)) {
                            val input = fixture(shortSide, shortSide + 1)
                            val source = try { save("auto-$shortSide-source", input) } finally { input.recycle() }
                            val (page, stats) = withContext(Dispatchers.IO) { process(source, settings.read().scale, engine, "auto-$shortSide") }
                            val expected = SuperResolutionScale.AUTO.resolve(shortSide, shortSide + 1)
                            cases.put(JSONObject().put("name", "auto-$shortSide").put("scale", page.scale)
                                .put("width", page.width).put("height", page.height).put("totalMs", stats.totalMs))
                            write("quality", report)
                            assertEquals(expected, page.scale); assertEquals(shortSide * expected, page.width)
                            assertEquals((shortSide + 1) * expected, page.height)
                            assertTrue((0 until page.count).all(page::complete)); page.trim()
                        }
                    }
                    val input = if (backend == "cpu") fixture(129, 127) else fixture(257, 193)
                    try {
                        val source = save("$backend-source", input)
                        for (scale in if (backend == "gpu_fast") listOf(2, 4, 8) else listOf(4, 8)) {
                            var reference = input
                            try {
                                repeat(Integer.numberOfTrailingZeros(scale)) {
                                    val next = withContext(Dispatchers.IO) { engine.upscale(reference) }
                                    if (reference !== input) reference.recycle()
                                    reference = next
                                }
                                save("$backend-${scale}x-reference", reference)
                                for (grouped in if (backend == "gpu_fast" && scale == 8) listOf(false, true) else listOf(true)) {
                                    val label = "$backend-${scale}x-${if (grouped) "bands" else "tiles"}"
                                    val (page, stats) = withContext(Dispatchers.IO) {
                                        process(source, SuperResolutionScale.fromValue(scale.toString()), engine, label, grouped)
                                    }
                                    val diff = compareAndSave(page, reference, label)
                                        .put("name", label).put("scale", scale).put("totalMs", stats.totalMs)
                                        .put("maxOutputBitmapBytes", stats.maxOutputBitmapBytes)
                                    cases.put(diff); write("quality", report)
                                    // Shifted inference windows can change FP16 rounding. Repeated
                                    // enhancement amplifies it; FP32/CPU retain a tighter tolerance.
                                    val fast = backend == "gpu_fast"
                                    val maxError = if (!fast) 3 else if (scale == 8) 32 else 8
                                    val meanError = if (!fast) .05 else if (scale == 8) .30 else .15
                                    val seamError = if (!fast) .05 else if (scale == 8) .30 else .25
                                    diff.put("maxTolerance", maxError).put("maeTolerance", meanError)
                                        .put("seamTolerance", seamError)
                                    // Also reject errors disproportionately concentrated at tile edges.
                                    val concentratedAtSeams = diff.getDouble("seamMae") > diff.getDouble("mae") * 1.5 + .02
                                    if (diff.getInt("max") > maxError || diff.getDouble("mae") >= meanError ||
                                        diff.getDouble("seamMae") >= seamError || concentratedAtSeams) violations += "$label: $diff"
                                    assertTrue(stats.maxOutputBitmapBytes <= SuperResolutionPageProcessor.BAND_BYTES)
                                    page.trim()
                                }
                            } finally { if (reference !== input) reference.recycle() }
                        }
                    } finally { input.recycle() }
                    if (backend == "gpu_fast") {
                        val publicSource = File(context.getExternalFilesDir(null), "sr-optimization/public.jpg")
                        if (publicSource.isFile) for (scale in listOf(2, 4, 8)) {
                            val (page, _) = withContext(Dispatchers.IO) {
                                process(publicSource, SuperResolutionScale.fromValue(scale.toString()), engine, "public-$scale")
                            }
                            val assembled = assemble(page)
                            try { save("public-${scale}x", assembled) } finally { assembled.recycle(); page.trim() }
                        }
                    }
                } finally { withContext(Dispatchers.IO) { engine.close() } }
            }
            report.put("violations", JSONArray(violations))
            assertTrue(violations.joinToString("\n"), violations.isEmpty())
            report.put("result", "PASS")
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("error", error.toString()); throw error
        } finally {
            write("quality", report)
            settings.preferences.edit().apply {
                if (oldScale == null) remove(SuperResolutionSettings.SCALE) else putString(SuperResolutionSettings.SCALE, oldScale)
            }.commit()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun assemble(page: EnhancedTilePage): Bitmap {
        val result = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        for (index in 0 until page.count) {
            val core = page.core(index); val region = page.region(index)
            val pixels = checkNotNull(page.acquire(index, 1))
            try { canvas.drawBitmap(pixels.bitmap, Rect(core.left - region.left, core.top - region.top,
                core.right - region.left, core.bottom - region.top), core, null) } finally { pixels.close() }
        }
        return result
    }

    private fun compareAndSave(page: EnhancedTilePage, reference: Bitmap, label: String): JSONObject {
        val assembled = assemble(page)
        try {
            assertEquals(reference.width, assembled.width); assertEquals(reference.height, assembled.height)
            save(label, assembled)
            val rowA = IntArray(page.width); val rowB = IntArray(page.width)
            var maximum = 0; var total = 0L; var seamTotal = 0L; var seamCount = 0L
            for (y in 0 until page.height) {
                reference.getPixels(rowA, 0, page.width, 0, y, page.width, 1)
                assembled.getPixels(rowB, 0, page.width, 0, y, page.width, 1)
                for (x in 0 until page.width) {
                    assertEquals(255, Color.alpha(rowB[x]))
                    val seam = (x > 1 && x < page.width - 2 && (x + 2) % page.outputEdge < 4) ||
                        (y > 1 && y < page.height - 2 && (y + 2) % page.outputEdge < 4)
                    for (shift in listOf(0, 8, 16)) {
                        val delta = abs(((rowA[x] ushr shift) and 255) - ((rowB[x] ushr shift) and 255))
                        maximum = maxOf(maximum, delta); total += delta
                        if (seam) { seamTotal += delta; seamCount++ }
                    }
                }
            }
            return JSONObject().put("max", maximum).put("mae", total.toDouble() / page.width / page.height / 3)
                .put("seamMae", if (seamCount == 0L) 0.0 else seamTotal.toDouble() / seamCount)
                .put("width", page.width).put("height", page.height).put("tiles", page.count)
        } finally { assembled.recycle() }
    }

    @Test fun boundedMemoryAcrossRepeatedFourAndEightTimesPages() = runBlocking<Unit> {
        val activity = foreground()
        val report = JSONObject().put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("samplingMs", 200).put("sourceWidth", 768).put("sourceHeight", 1024)
        val cases = JSONArray(); report.put("cases", cases)
        try {
            val input = fixture(768, 1024)
            val source = try { save("memory-source", input) } finally { input.recycle() }
            val engine = withContext(Dispatchers.IO) { SuperResolution(SuperResolutionModels(context).files(),
                SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP16) }
            try {
                val warm = fixture(64, 64)
                try { withContext(Dispatchers.IO) { engine.upscale(warm).recycle() } } finally { warm.recycle() }
                for (scale in listOf(2, 4, 8, 4, 8)) {
                    System.gc(); delay(300)
                    val samples = JSONArray()
                    fun sample(): JSONObject {
                        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
                        return JSONObject().put("elapsedMs", SystemClock.elapsedRealtime()).put("pssKiB", info.totalPss)
                            .put("nativeBytes", Debug.getNativeHeapAllocatedSize())
                            .put("javaBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    }
                    val before = sample()
                    val sampler = launch(Dispatchers.IO) { while (isActive) { samples.put(sample()); delay(200) } }
                    Log.i("SRScaleDevice", "MEMORY_START scale=$scale round=${cases.length()}")
                    val result = try { withContext(Dispatchers.IO) {
                        process(source, SuperResolutionScale.fromValue(scale.toString()), engine, "memory-$scale")
                    } } finally { sampler.cancelAndJoin() }
                    val (page, stats) = result
                    val after = sample(); page.trim()
                    System.gc(); delay(500)
                    val released = sample()
                    val peakPss = maxOf(before.getInt("pssKiB"), after.getInt("pssKiB"),
                        (0 until samples.length()).maxOfOrNull { samples.getJSONObject(it).getInt("pssKiB") } ?: 0)
                    cases.put(JSONObject().put("scale", scale).put("totalMs", stats.totalMs)
                        .put("width", page.width).put("height", page.height)
                        .put("logicalOutputBytes", page.width.toLong() * page.height * 4)
                        .put("maxOutputBitmapBytes", stats.maxOutputBitmapBytes).put("tiles", page.count)
                        .put("before", before).put("after", after).put("released", released)
                        .put("peakPssKiB", peakPss).put("samples", samples))
                    write("memory", report)
                    assertTrue(stats.maxOutputBitmapBytes <= SuperResolutionPageProcessor.BAND_BYTES)
                    assertEquals(768 * scale, page.width); assertEquals(1024 * scale, page.height)
                    assertEquals(0L, page.cachedBytes())
                    assertTrue((0 until page.count).all(page::complete))
                    Log.i("SRScaleDevice", "MEMORY_DONE scale=$scale peakPssKiB=$peakPss ms=${stats.totalMs}")
                }
            } finally { withContext(Dispatchers.IO) { engine.close() } }
            System.gc(); delay(500)
            report.put("nativeAfterEngineClose", Debug.getNativeHeapAllocatedSize()).put("result", "PASS")
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("error", error.toString()); throw error
        } finally { write("memory", report); instrumentation.runOnMainSync { activity.finish() } }
    }
}
