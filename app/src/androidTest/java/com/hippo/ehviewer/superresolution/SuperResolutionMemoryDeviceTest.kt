package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.engine.SuperResolution
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.image.Image
import com.hippo.ehviewer.ui.SettingsActivity
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic ordinary manga dimensions; runs the production Kotlin/NCNN path and provider cache. */
@RunWith(AndroidJUnit4::class)
class SuperResolutionMemoryDeviceTest {
    @Test fun previouslyRejectedPageCompletesAndPreviousResultsAreRecycled() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity"
        ).use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        instrumentation.runOnMainSync {
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(com.hippo.ehviewer.R.id.settings)
                as com.hippo.ehviewer.ui.fragment.SuperResolutionFragment
            val acceleration = checkNotNull(fragment.findPreference<androidx.preference.ListPreference>(SuperResolutionModels.BACKEND))
            assertEquals(listOf("auto", "auto_fast", "cpu"), acceleration.entryValues.map { it.toString() })
        }
        val budget = SuperResolutionMemoryBudget.current(context)
        val report = StringBuilder("fixture=synthetic 1280x1920\nbudgetMiB=${budget / 1048576}\n")
        val width = 1280
        val height = 1920
        assertTrue("Ordinary manga page should fit this device: $budget", SuperResolution.accepts(width, height, budget))
        val provider = object : GalleryProvider() {
            override fun size() = 2
            override fun onRequest(index: Int) = Unit
            override fun onForceRequest(index: Int) = Unit
            override fun onCancelRequest(index: Int) = Unit
            override fun getError(): String? = null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        val canvas = Canvas(bitmap)
        for (y in 64 until height step 96) canvas.drawLine(32f, y.toFloat(), width - 32f, y.toFloat(), paint)
        provider.notifyPageSucceed(0, checkNotNull(Image.create(bitmap)))
        val source = checkNotNull(provider.getSuperResolutionInput(0, budget))
        var result: Bitmap? = null
        try {
            val baselineNative = Debug.getNativeHeapAllocatedSize()
            var peakNative = baselineNative
            var canceledTiles = 0
            val files = SuperResolutionModels(context).files()
            val canceled = launch(Dispatchers.Default) {
                SuperResolution(files).use { engine ->
                    val request = currentCoroutineContext()
                    engine.upscale(source, budget) { done, _ ->
                        canceledTiles = done
                        request.cancel()
                    }.also { it.recycle(); fail("Canceled result escaped") }
                }
            }
            canceled.join()
            assertTrue(canceled.isCancelled)
            assertEquals(1, canceledTiles)
            assertFalse(source.isRecycled)
            report.append("nativeBeforeCancellation=$baselineNative\n")
            report.append("nativeAfterCancellation=${Debug.getNativeHeapAllocatedSize()}\n")
            SuperResolution(files).use { engine ->
                val start = SystemClock.elapsedRealtime()
                var completed = 0
                Log.i(TAG, "START budgetMiB=${budget / 1048576}")
                result = withContext(Dispatchers.Default) {
                    engine.upscale(source, budget) { done, total ->
                        completed = done
                        peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                        if (done % 10 == 0) Log.i(TAG, "tiles=$done/$total")
                    }
                }
                assertEquals(150, completed)
                report.append("elapsedMs=${SystemClock.elapsedRealtime() - start}\n")
            }
            val enhanced = checkNotNull(result)
            assertEquals(width * 2, enhanced.width)
            assertEquals(height * 2, enhanced.height)
            assertEquals(39321600, enhanced.allocationByteCount)
            assertEquals(255, Color.alpha(enhanced.getPixel(100, 100)))
            report.append("outputBytes=${enhanced.allocationByteCount}\npeakSampledNative=$peakNative\n")
            provider.setSuperResolutionCacheLimit(budget)
            provider.setEnhancedPage(0, checkNotNull(Image.create(enhanced)))
            result = null // Provider now owns the result.
            assertTrue(provider.hasEnhancedPage(0))
            assertFalse(enhanced.isRecycled)
            provider.retainEnhancedPage(1)
            assertTrue(enhanced.isRecycled)
            assertFalse(provider.hasEnhancedPage(0))
            assertFalse(bitmap.isRecycled)
            report.append("nativeAfterResultRecycle=${Debug.getNativeHeapAllocatedSize()}\nPASS\n")
            File(context.cacheDir, "super-resolution-memory-test.txt").writeText(report.toString())
            Log.i(TAG, report.toString())
        } finally {
            result?.recycle()
            source.recycle()
            instrumentation.runOnMainSync { provider.stop(); activity.finish() }
        }
    }

    companion object { private const val TAG = "SuperResolutionMemoryTest" }
}
