package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.*
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.engine.*
import com.hippo.ehviewer.ui.SettingsActivity
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuperResolutionRegionDeviceTest {
    @Test fun regionQualityCheckpointMemoryAndGpuTuning() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val folder = File(context.getExternalFilesDir(null), "sr-tiles-validation").apply { mkdirs() }
        val report = JSONObject().put("scope", "foreground, same weights, warm reused models, 1280x1920; timings exclude encoding unless specified")
        fun flush() { File(folder, "report.json").writeText(report.toString(2)) }
        fun save(name: String, bitmap: Bitmap) { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val engines = linkedMapOf<String, SuperResolution>()
        val all = mutableListOf<Bitmap>()
        try {
            val files = withContext(Dispatchers.IO) { SuperResolutionModels(context).files() }
            report.put("gpu", SuperResolution.vulkanDeviceName()); flush()
            for (arithmetic in listOf(false, true)) for (batch in listOf(1, 2, 4)) {
                val name = "a${if (arithmetic) 1 else 0}b$batch"
                report.put("opening", name); flush()
                engines[name] = withContext(Dispatchers.IO) {
                    SuperResolution(files, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP16, arithmetic, batch)
                }
            }
            fun fixture(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 25f; strokeWidth = 1f }
                val canvas = Canvas(this)
                for (y in 40 until h step 64) canvas.drawLine(7f, y.toFloat(), w - 7f, y + 13f, paint)
                canvas.drawText("TEXT 123 漫画", 15f, 36f, paint)
                for (x in 0 until minOf(w, 256)) { paint.color = Color.rgb(x, 255 - x, x / 2); canvas.drawRect(x.toFloat(), (h - 40).toFloat(), x + 1f, h.toFloat(), paint) }
            }
            fun difference(a: Bitmap, b: Bitmap): JSONObject {
                assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                val rowA = IntArray(a.width); val rowB = IntArray(a.width)
                var maximum = 0; var total = 0L
                for (y in 0 until a.height) {
                    a.getPixels(rowA, 0, a.width, 0, y, a.width, 1)
                    b.getPixels(rowB, 0, b.width, 0, y, b.width, 1)
                    for (x in rowA.indices) for (shift in listOf(0, 8, 16)) {
                        val delta = abs(((rowA[x] ushr shift) and 255) - ((rowB[x] ushr shift) and 255))
                        maximum = maxOf(maximum, delta); total += delta
                    }
                }
                return JSONObject().put("max", maximum).put("mae", total.toDouble() / a.width / a.height / 3)
            }
            val small = fixture(513, 385).also { all += it }
            val baseline = withContext(Dispatchers.IO) { engines.getValue("a0b1").upscale(small) }.also { all += it }
            save("fixed-source", small); save("fixed-storage16", baseline)
            val quality = JSONObject()
            for ((name, engine) in engines) {
                val image = withContext(Dispatchers.IO) { engine.upscale(small) }
                try { quality.put(name, difference(baseline, image)); if (name == "a1b4") save("fixed-arithmetic16", image) } finally { image.recycle() }
            }
            report.put("precisionVsStorage16", quality); flush()

            // Reassemble only in this small QA fixture; production never allocates the full output.
            for (backend in listOf(SuperResolutionBackend.CPU, SuperResolutionBackend.VULKAN)) {
                val engine = if (backend == SuperResolutionBackend.CPU) SuperResolution(files, backend) else engines.getValue("a0b1")
                val whole = withContext(Dispatchers.IO) { engine.upscale(small) }
                val assembled = Bitmap.createBitmap(whole.width, whole.height, Bitmap.Config.ARGB_8888)
                val geometry = EnhancedTilePage(folder, small.width, small.height)
                try {
                    for (index in 0 until geometry.count) {
                        val region = geometry.region(index); val core = geometry.core(index)
                        val tile = withContext(Dispatchers.IO) { engine.upscaleRegion(small, region.left / 2, region.top / 2, region.width() / 2, region.height() / 2) }
                        try { Canvas(assembled).drawBitmap(tile, Rect(core.left - region.left, core.top - region.top, core.right - region.left, core.bottom - region.top), core, null) }
                        finally { tile.recycle() }
                    }
                    val diff = difference(whole, assembled)
                    save("fixed-region-$backend", assembled)
                    report.put("region_$backend", diff); flush()
                    assertTrue("Region boundary error: $diff", diff.getInt("max") <= if (backend == SuperResolutionBackend.CPU) 1 else 3)
                    assertTrue(diff.getDouble("mae") < .1)
                } finally { whole.recycle(); assembled.recycle(); if (backend == SuperResolutionBackend.CPU) engine.close() }
            }
            val public = File(context.getExternalFilesDir(null), "sr-optimization/public.jpg")
            if (public.isFile) {
                val input = checkNotNull(BitmapFactory.decodeFile(public.path))
                try {
                    val reference = withContext(Dispatchers.IO) { engines.getValue("a0b1").upscale(input) }
                    try {
                        save("public-storage16", reference)
                        val comparisons = JSONObject()
                        for ((name, engine) in engines) {
                            val image = withContext(Dispatchers.IO) { engine.upscale(input) }
                            try { comparisons.put(name, difference(reference, image)); if (name == "a1b4") save("public-arithmetic16", image) } finally { image.recycle() }
                        }
                        report.put("publicPrecision", comparisons); flush()
                    } finally { reference.recycle() }
                } finally { input.recycle() }
            }
            val large = fixture(1280, 1920).also { all += it }
            // Small warmup compiles pipelines. Interleave modes to reduce order/thermal bias.
            for (engine in engines.values) withContext(Dispatchers.IO) { engine.upscale(small).recycle() }
            val times = JSONObject(); engines.keys.forEach { times.put(it, JSONArray()) }
            repeat(3) { round ->
                val order = if (round % 2 == 0) engines.entries.toList() else engines.entries.reversed()
                for ((name, engine) in order) {
                    val start = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) { engine.upscale(large).recycle() }
                    times.getJSONArray(name).put(SystemClock.elapsedRealtime() - start)
                    report.put("timingsMs", times); flush()
                }
            }
            val source = File(folder, "source.png")
            source.outputStream().use { large.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val selected = SuperResolutionOptions(persist = false)
            val store = SuperResolutionStore(context, "device-tiles-${System.currentTimeMillis()}", selected)
            val key = store.key(source)
            var calls = 0
            try {
                withContext(Dispatchers.IO) {
                    SuperResolutionPageProcessor(context).process(source, store, key, 12L * 1024 * 1024, {},
                        { bitmap, rect, callback ->
                            if (++calls == 4) throw CancellationException("translation checkpoint")
                            engines.getValue("a0b1").upscaleRegion(bitmap, rect.left, rect.top, rect.width(), rect.height(), progress = callback)
                        }, {}, { fail("Partial page displayed"); false }, grouped = true)
                }
            } catch (_: CancellationException) { }
            assertNull(store.findTiles(key))
            val processorStart = SystemClock.elapsedRealtime()
            val (tiles, stats) = withContext(Dispatchers.IO) {
                SuperResolutionPageProcessor(context).process(source, store, key, 12L * 1024 * 1024, {},
                    { bitmap, rect, callback -> engines.getValue("a0b1").upscaleRegion(bitmap, rect.left, rect.top, rect.width(), rect.height(), progress = callback) }, {}, { true }, grouped = true)
            }
            assertTrue(stats.reusedTiles > 0)
            assertTrue(stats.maxOutputBitmapBytes <= SuperResolutionPageProcessor.BAND_BYTES)
            assertTrue(tiles.width.toLong() * tiles.height * 4 > 12L * 1024 * 1024)
            report.put("checkpoint", JSONObject().put("reused", stats.reusedTiles).put("computed", stats.computedTiles)
                .put("maxOutputBitmapBytes", stats.maxOutputBitmapBytes).put("displayMs", stats.displayMs)
                .put("encodeMs", stats.encodeMs).put("nativeMs", stats.inferenceMs)
                .put("totalMs", SystemClock.elapsedRealtime() - processorStart))
            tiles.trim()
            val freshKey = "fresh-$key"
            val (fresh, freshStats) = withContext(Dispatchers.IO) {
                SuperResolutionPageProcessor(context).process(source, store, freshKey, 12L * 1024 * 1024, {},
                    { bitmap, rect, callback -> engines.getValue("a0b1").upscaleRegion(bitmap, rect.left, rect.top, rect.width(), rect.height(), progress = callback) }, {}, { true }, grouped = true)
            }
            report.put("freshTiledPage", JSONObject().put("computed", freshStats.computedTiles).put("reused", freshStats.reusedTiles)
                .put("maxOutputBitmapBytes", freshStats.maxOutputBitmapBytes).put("displayMs", freshStats.displayMs)
                .put("nativeMs", freshStats.inferenceMs).put("encodeMs", freshStats.encodeMs).put("totalMs", freshStats.totalMs))
            fresh.trim()
            report.put("result", "PASS"); flush()
        } finally {
            withContext(Dispatchers.IO) { engines.values.forEach { it.close() }; all.forEach { it.recycle() } }
            flush(); instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
