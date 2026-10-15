package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.TranslationModels
import com.hippo.ehviewer.translation.engine.*
import com.hippo.ehviewer.ui.SettingsActivity
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One foreground run: identical inputs, explicit backends, real external translation models. */
@RunWith(AndroidJUnit4::class)
class SuperResolutionVulkanDeviceTest {
    @Test fun gpuParityPerformanceAndTranslationCoexistence() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = StringBuilder("fixture=synthetic; same process; foreground; CPU then Vulkan\n")
        val reportFile = File(context.cacheDir, "super-resolution-vulkan-test.txt")
        // Bring the exported launcher forward through the shell before launching
        // internal settings; some OEMs block instrumentation's background start.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity"
        ).use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val allocated = mutableListOf<Bitmap>()
        try {
            report.append("device=${withContext(Dispatchers.IO) { SuperResolution.vulkanDeviceName() }}\n")
            assertNotNull("Test device must expose an NCNN-compatible Vulkan GPU", SuperResolution.vulkanDeviceName())
            val files = withContext(Dispatchers.IO) { SuperResolutionModels(context).files() }
            val budget = SuperResolutionMemoryBudget.current(context)
            suspend fun render(source: Bitmap, backend: SuperResolutionBackend, label: String,
                               modelFiles: NcnnModelFiles = files,
                               pipeline: SuperResolutionPipeline = SuperResolutionPipeline.GPU_FP16): Bitmap = withContext(Dispatchers.Default) {
                val start = SystemClock.elapsedRealtime()
                var peakNative = Debug.getNativeHeapAllocatedSize()
                val result = SuperResolution(modelFiles, backend, pipeline).use { engine ->
                    assertEquals(backend == SuperResolutionBackend.VULKAN, engine.usingVulkan)
                    assertFalse(engine.fellBack)
                    engine.upscale(source, budget) { _, _ ->
                        peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                    }
                }
                report.append("${label}Ms=${SystemClock.elapsedRealtime() - start}\n${label}SampledNative=$peakNative\n")
                result.also { allocated += it }
            }
            fun compare(expected: Bitmap, actual: Bitmap, label: String, maxAllowed: Int = 8) {
                assertEquals(expected.width, actual.width)
                assertEquals(expected.height, actual.height)
                val count = expected.width * expected.height
                val a = IntArray(count); val b = IntArray(count)
                expected.getPixels(a, 0, expected.width, 0, 0, expected.width, expected.height)
                actual.getPixels(b, 0, actual.width, 0, 0, actual.width, actual.height)
                var maximum = 0
                var total = 0L
                for (i in a.indices) for (shift in listOf(0, 8, 16)) {
                    val delta = abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
                    maximum = maxOf(maximum, delta); total += delta
                }
                val mean = total.toDouble() / (count * 3)
                report.append("${label}Max8bitError=$maximum\n${label}Mean8bitError=$mean\n")
                assertTrue("$label max error $maximum", maximum <= maxAllowed)
                assertTrue("$label mean error $mean", mean <= 0.75)
            }

            val small = Bitmap.createBitmap(187, 135, Bitmap.Config.ARGB_8888).also { allocated += it }
            val pixels = IntArray(small.width * small.height) { i ->
                val x = i % small.width; val y = i / small.width
                Color.rgb((x * 7 + y * 13) % 256, (x * 7 + y * 13 + 53) % 256, (x * 7 + y * 13 + 106) % 256)
            }
            small.setPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val cpu = render(small, SuperResolutionBackend.CPU, "smallCpu")
            val gpu = render(small, SuperResolutionBackend.VULKAN, "smallGpuFp32", pipeline = SuperResolutionPipeline.GPU_FP32)
            compare(cpu, gpu, "oddTilesCpuGpu")
            fun external(source: ModelFile) = ModelFile.external {
                source.read { path, descriptor ->
                    if (path != null) ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    else ParcelFileDescriptor.dup(checkNotNull(descriptor).fileDescriptor)
                }
            }
            val referenced = NcnnModelFiles(external(files.param), external(files.weights))
            val referencedGpu = render(small, SuperResolutionBackend.VULKAN, "externalGpu", referenced,
                SuperResolutionPipeline.GPU_FP32)
            compare(gpu, referencedGpu, "externalGpu", 1)
            cpu.recycle(); gpu.recycle(); referencedGpu.recycle()
            val fast = render(small, SuperResolutionBackend.VULKAN, "privateGpuFp16")
            val externalFast = render(small, SuperResolutionBackend.VULKAN, "externalGpuFp16", referenced)
            try { compare(fast, externalFast, "externalFp16", 1) }
            finally { fast.recycle(); externalFast.recycle() }

            var canceledTiles = 0
            val cancellableEngine = withContext(Dispatchers.Default) {
                SuperResolution(files, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP16)
            }
            try {
                val canceled = launch(Dispatchers.Default) {
                    val request = currentCoroutineContext()
                    cancellableEngine.upscale(small, budget) { done, _ ->
                        canceledTiles = done
                        request.cancel()
                    }.also { it.recycle(); fail("Canceled GPU result escaped") }
                }
                canceled.join()
                assertTrue(canceled.isCancelled)
                assertEquals(1, canceledTiles)
                assertFalse(small.isRecycled)
                withContext(Dispatchers.Default) { cancellableEngine.upscale(small, budget) }.recycle()
                assertTrue(cancellableEngine.usingVulkan); assertFalse(cancellableEngine.fellBack)
                val failure = withContext(Dispatchers.Default) {
                    runCatching { cancellableEngine.upscale(small, budget) { _, _ ->
                        throw IllegalArgumentException("Progress callback failed")
                    } }.exceptionOrNull()
                }
                assertTrue(failure is IllegalArgumentException)
                assertEquals("Progress callback failed", failure?.message)
                withContext(Dispatchers.Default) { cancellableEngine.upscale(small, budget) }.recycle()
                report.append("gpuCallbackExceptionAndReuse=PASS\n")
                report.append("gpuCancellationAndReuseAfterFirstTile=PASS\n")
            } finally { cancellableEngine.close() }

            val page = Bitmap.createBitmap(1280, 1920, Bitmap.Config.ARGB_8888).also { allocated += it }
            page.eraseColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
            val canvas = Canvas(page)
            for (y in 64 until page.height step 96) canvas.drawLine(32f, y.toFloat(), page.width - 32f, y.toFloat(), paint)
            render(page, SuperResolutionBackend.CPU, "pageCpu").recycle()
            render(page, SuperResolutionBackend.VULKAN, "pageGpu").let {
                assertEquals(2560, it.width); assertEquals(3840, it.height); it.recycle()
            }

            val translation = TranslationModels(context)
            report.append("translationModelsReady=${translation.ready()}\n")
            if (translation.ready()) {
                val set = withContext(Dispatchers.IO) { translation.verified() }
                val start = SystemClock.elapsedRealtime()
                coroutineScope {
                    val ready = CompletableDeferred<Unit>()
                    val cpuTranslation = async(Dispatchers.Default) {
                        Detector(set.detectorNcnn, DetectorConfig(inputSize = 256), set.imageFiles(set.detectorNcnn)).use { detector ->
                            val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
                            Ocr(set.ocr, alphabet, OcrConfig(concurrency = 1), set.imageFiles(set.ocr)).use { ocr ->
                                ready.complete(Unit)
                                val detection = detector.detect(small)
                                assertEquals(small.width, detection.textMask.width)
                                detection.textMask.recycle()
                                val line = TextLine(listOf(Pt(30f, 40f), Pt(150f, 40f), Pt(150f, 80f), Pt(30f, 80f)), 1f)
                                ocr.recognize(small, listOf(line))
                                val mask = Bitmap.createBitmap(small.width, small.height, Bitmap.Config.ARGB_8888)
                                try {
                                    Canvas(mask).drawRect(40f, 45f, 140f, 75f, Paint().apply { color = Color.WHITE })
                                    Inpainter(set.aotInpainterNcnn, files = set.imageFiles(set.aotInpainterNcnn)).use { inpainter ->
                                        inpainter.inpaint(small, listOf(TextRegion(listOf(line), "h")), mask).recycle()
                                    }
                                } finally { mask.recycle() }
                            }
                        }
                    }
                    val gpuWork = async(Dispatchers.Default) {
                        ready.await()
                        SuperResolution(files, SuperResolutionBackend.VULKAN, SuperResolutionPipeline.GPU_FP16).use { engine ->
                            repeat(3) {
                                val begin = SystemClock.elapsedRealtime()
                                engine.upscale(small, budget).recycle()
                                assertTrue(engine.usingVulkan); assertFalse(engine.fellBack)
                                report.append("concurrentReusedGpu${it}Ms=${SystemClock.elapsedRealtime() - begin}\n")
                            }
                        }
                    }
                    awaitAll(cpuTranslation, gpuWork)
                }
                val sourceAfter = IntArray(pixels.size)
                small.getPixels(sourceAfter, 0, small.width, 0, 0, small.width, small.height)
                assertArrayEquals("CPU translation and GPU super-resolution must preserve the original", pixels, sourceAfter)
                report.append("cpuDetectorOcrInpaintAndVulkan=PASS\ncoexistMs=${SystemClock.elapsedRealtime() - start}\n")
            }
            report.append("PASS\n")
        } finally {
            reportFile.writeText(report.toString())
            allocated.forEach { if (!it.isRecycled) it.recycle() }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
