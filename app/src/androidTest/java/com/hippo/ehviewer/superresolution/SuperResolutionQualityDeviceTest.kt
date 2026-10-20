package com.hippo.ehviewer.superresolution

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.translation.engine.SuperResolution
import com.hippo.ehviewer.translation.engine.SuperResolutionBackend
import com.hippo.ehviewer.translation.engine.SuperResolutionPipeline
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.fragment.SuperResolutionFragment
import com.hippo.preference.ListPreference
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the installed debug app. Only the companion test APK needs installation. */
@RunWith(AndroidJUnit4::class)
class SuperResolutionQualityDeviceTest {
    @Test fun compareAllQualitySettingsOnIdenticalEnhancedPixels() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SuperResolutionSettings(context)
        val previousQuality = settings.preferences.getString(SuperResolutionSettings.QUALITY, null)
        val runName = "run-${System.currentTimeMillis()}"
        val output = File(context.getExternalFilesDir(null), "sr-quality-validation/$runName").apply { check(mkdirs()) }
        val workspace = File(context.cacheDir, "sr-quality-qa-$runName").apply { check(mkdirs()) }
        // Isolate pruning and metadata from all existing gallery results.
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir() = File(workspace, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(workspace, "persistent").apply { mkdirs() }
        }
        val cases = JSONArray()
        val report = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE).put("run", runName).put("scale", 2)
            .put("comparison", "Each source is enhanced once; all six settings encode identical lossless tiles.")
            .put("cases", cases)
        var activity: SettingsActivity? = null
        try {
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
                .use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
            val screen = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            activity = screen
            lateinit var preference: ListPreference
            instrumentation.runOnMainSync {
                screen.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                screen.supportFragmentManager.executePendingTransactions()
                val fragment = screen.supportFragmentManager.findFragmentById(R.id.settings) as SuperResolutionFragment
                preference = checkNotNull(fragment.findPreference(SuperResolutionSettings.QUALITY))
                assertEquals(listOf("80", "85", "90", "95", "100", "lossless"), preference.entryValues.map { it.toString() })
            }
            report.put("initialQuality", settings.read().quality.value)
            instrumentation.waitForIdleSync()
            delay(250)
            fun screenshot(name: String) {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            screenshot("settings")
            instrumentation.runOnMainSync { preference.performClick() }
            instrumentation.waitForIdleSync()
            delay(250)
            screenshot("quality-options")
            instrumentation.runOnMainSync {
                screen.supportFragmentManager.fragments.filterIsInstance<androidx.fragment.app.DialogFragment>()
                    .forEach { it.dismiss() }
            }
            instrumentation.waitForIdleSync()
            val inputs = linkedMapOf<String, File>()
            for (colored in listOf(false, true)) {
                val name = if (colored) "color-detail" else "line-art"
                val bitmap = fixture(colored)
                inputs[name] = File(output, "$name-source.png").also { file ->
                    try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { bitmap.recycle() }
                }
            }
            val publicSource = File(context.getExternalFilesDir(null), "sr-optimization/public.jpg")
            check(publicSource.isFile) { "Missing existing public QA fixture" }
            inputs["illustration"] = publicSource.copyTo(File(output, "illustration-source.jpg"))

            SuperResolutionModels.lock.withLock {
                val engine = SuperResolution(SuperResolutionModels(context).files(), SuperResolutionBackend.AUTO,
                    SuperResolutionPipeline.GPU_FP16)
                try {
                    for ((name, source) in inputs) {
                        val baseStore = SuperResolutionStore(isolated, "base-$name",
                            SuperResolutionOptions(persist = false, scale = SuperResolutionScale.X2))
                        val (base, metrics) = SuperResolutionPageProcessor(context).process(source, baseStore, baseStore.key(source),
                            SuperResolutionMemoryBudget.current(context), {},
                            { bitmap, region, callback -> engine.upscaleRegion(bitmap, region.left, region.top,
                                region.width(), region.height(), SuperResolutionPageProcessor.BAND_BYTES, callback) },
                            {}, { false }, grouped = true)
                        try {
                            val folder = File(output, name).apply { check(mkdirs()) }
                            File(folder, "reference.png").outputStream().use { SuperResolutionPng.write(base, it) }
                            val sample = JSONObject().put("sample", name).put("sourceWidth", base.sourceWidth)
                                .put("sourceHeight", base.sourceHeight).put("width", base.width).put("height", base.height)
                                .put("inferenceMs", metrics.inferenceMs).put("gpu", engine.usingVulkan)
                            val results = JSONArray()
                            sample.put("results", results); cases.put(sample)
                            for (quality in SuperResolutionQuality.entries) {
                                instrumentation.runOnMainSync { preference.value = quality.value }
                                assertEquals(quality, settings.read().quality)
                                val selected = settings.read().copy(persist = false, scale = SuperResolutionScale.X2)
                                val store = SuperResolutionStore(isolated, "$name-${quality.value}", selected)
                                val key = store.key(source)
                                val page = store.checkpoint(key, base.sourceWidth, base.sourceHeight)
                                for (index in 0 until base.count) base.file(index).copyTo(File(page.directory, base.file(index).name))
                                store.finish(page)
                                try {
                                    val start = SystemClock.elapsedRealtime()
                                    val result = store.compact(key, page, checkNotNull(UniFile.fromFile(folder)), quality.value)
                                    val elapsed = SystemClock.elapsedRealtime() - start
                                    val extension = "webp"
                                    assertEquals("${quality.value}_sr.$extension", result.name)
                                    results.put(JSONObject().put("quality", quality.value).put("file", result.uri.path)
                                        .put("bytes", result.length()).put("saveMs", elapsed).put("format", extension))
                                } finally { page.trim() }
                            }
                        } finally { base.trim() }
                    }
                } finally { engine.close() }
            }
            report.put("result", "PASS")
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("error", error.stackTraceToString())
            throw error
        } finally {
            settings.preferences.edit().apply {
                if (previousQuality == null) remove(SuperResolutionSettings.QUALITY)
                else putString(SuperResolutionSettings.QUALITY, previousQuality)
            }.commit()
            File(output, "report.json").writeText(report.toString(2))
            android.util.Log.i("SRQualityQA", "Report: ${output.path}/report.json")
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    private fun fixture(colored: Boolean): Bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 25f }
        canvas.drawText(if (colored) "COLOR / GRADIENT / FINE DETAIL" else "LINE ART / TEXT / SCREENTONE", 18f, 40f, paint)
        for ((index, size) in listOf(9f, 12f, 16f, 22f).withIndex()) {
            paint.textSize = size
            paint.color = if (colored) Color.rgb(210, 25 + index * 30, 45 + index * 40) else Color.BLACK
            canvas.drawText("Small text 0123456789 漫画测试 AaBbCc", 20f, 80f + index * 35, paint)
        }
        for (index in 0..70) {
            paint.color = if (colored) Color.HSVToColor(floatArrayOf(index * 5f, .9f, .85f)) else Color.BLACK
            paint.strokeWidth = .5f + index % 5 * .25f
            canvas.drawLine(20f + index * 7f, 225f, 35f + index * 7f, 465f, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        for (radius in 8..100 step 7) {
            paint.color = if (colored) Color.HSVToColor(floatArrayOf(radius * 3f, .8f, .9f)) else Color.BLACK
            canvas.drawCircle(300f, 580f, radius.toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
        for (y in 490..680 step 4) for (x in 20..170 step 4) {
            paint.color = if (colored) Color.rgb((x * 17 + y * 3) % 256, (x * 5 + y * 7) % 256, (x + y * 11) % 256)
                else Color.rgb((x + y) % 150, (x + y) % 150, (x + y) % 150)
            canvas.drawCircle(x.toFloat(), y.toFloat(), .7f, paint)
        }
        for (x in 20 until 580) {
            paint.color = if (colored) Color.HSVToColor(floatArrayOf((x - 20) * 360f / 560, .75f, .95f))
                else Color.rgb((x - 20) * 255 / 560, (x - 20) * 255 / 560, (x - 20) * 255 / 560)
            canvas.drawRect(x.toFloat(), 715f, x + 1f, 780f, paint)
        }
        canvas.setBitmap(null)
    }
}
