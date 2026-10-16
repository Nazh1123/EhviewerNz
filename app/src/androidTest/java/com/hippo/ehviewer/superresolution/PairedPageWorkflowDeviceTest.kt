package com.hippo.ehviewer.superresolution

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.translation.*
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual session workers, priority gates, cached translation overlays and real GPU SR. */
@RunWith(AndroidJUnit4::class)
class PairedPageWorkflowDeviceTest {
    @Test fun independentWindowsAndFullGalleryFinishEachPageInOrder() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val translationSettings = TranslationSettings(context)
        val previous = translationSettings.read()
        val srSettings = SuperResolutionSettings(context)
        val keys = listOf(SuperResolutionModels.ENABLED, SuperResolutionSettings.AHEAD, SuperResolutionModels.BACKEND)
        val saved = srSettings.preferences.all.filterKeys { it in keys }
        val report = File(context.getExternalFilesDir(null), "sr-tiles-validation/paired-workflow.txt").apply { parentFile!!.mkdirs(); writeText("Cached translation overlays + real GPU SR; independent original readers.\n") }
        try {
            for ((translationAhead, srAhead, full) in listOf(Triple(1, 2, false), Triple(2, 0, false), Triple(0, 0, true))) {
                val folder = File(context.cacheDir, "paired-${System.nanoTime()}").apply { mkdirs() }
                val sources = (0..2).map { page ->
                    val bitmap = Bitmap.createBitmap(320, 384, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    val paint = Paint().apply { color = Color.BLACK; textSize = 24f }
                    Canvas(bitmap).drawText("ORIGINAL $page ${folder.name}", 5f, 60f, paint)
                    File(folder, "$page.png").also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                }
                fun hashes() = sources.map { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
                val originals = hashes()
                val options = previous.copy(ahead = translationAhead, source = "ja", persistDownloaded = false,
                    backend = TranslationBackend.LLM_API, apiUrl = "http://127.0.0.1:1/v1/chat/completions",
                    apiModel = "cached-pair-test", apiKey = "")
                translationSettings.save(options)
                srSettings.preferences.edit().putBoolean(SuperResolutionModels.ENABLED, false)
                    .putString(SuperResolutionSettings.AHEAD, srAhead.toString()).putString(SuperResolutionModels.BACKEND, "auto_fast").commit()
                val store = TranslationResultStore(TranslationStorage.cacheDir(context), null, options, null, false)
                for (source in sources) {
                    val overlay = Bitmap.createBitmap(320, 384, Bitmap.Config.ARGB_8888)
                    Canvas(overlay).drawText("TRANSLATED", 8f, 60f, Paint().apply { color = Color.BLUE; textSize = 24f })
                    try { store.writeImage(store.key(source)) { overlay.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { overlay.recycle() }
                }
                val order = mutableListOf<String>()
                val translated = mutableSetOf<Int>()
                val enhanced = mutableSetOf<Int>()
                val shown = mutableSetOf<Int>()
                lateinit var translation: GalleryTranslationSession
                lateinit var sr: GallerySuperResolutionSession
                withContext(Dispatchers.Main) {
                    val identity = "paired:${folder.name}"
                    val provider = Provider(sources, identity)
                    translation = TranslationTasks.acquire(context, provider, null)
                    sr = SuperResolutionTasks.acquire(context, provider, null)
                    translation.attach(object : GalleryTranslationSession.Reader {
                        override fun changed() {
                            translation.states.filterValues { it in GalleryTranslationSession.completedStates }.keys.forEach {
                                if (translated.add(it)) order.add("T$it")
                            }
                        }
                        override fun display(page: Int, image: Image) { shown.add(page); image.recycle() }
                        override fun hasTranslatedPage(page: Int) = page in shown
                    })
                    sr.attach(object : GallerySuperResolutionSession.Reader {
                        override fun changed() { sr.states.keys.forEach { if (enhanced.add(it)) order.add("S$it") } }
                        override fun display(page: Int, image: Image) { image.recycle() }
                        override fun hasPage(page: Int) = false
                        override fun clear() = Unit
                    })
                    translation.onPageChanged(0); sr.pageChanged(0)
                    if (full) { translation.translateFullGallery(); sr.full() }
                    else { translation.enable(); sr.enable() }
                }
                try {
                    withTimeout(90_000) {
                        while (!withContext(Dispatchers.Main) {
                                translated.size == (if (full) 3 else translationAhead + 1) &&
                                    enhanced.size == (if (full) 3 else srAhead + 1) && !translation.working && !sr.working
                            }) delay(25)
                    }
                    val expected = (0..2).flatMap { page -> buildList {
                        if (full || page <= translationAhead) add("T$page")
                        if (full || page <= srAhead) add("S$page")
                    } }
                    withContext(Dispatchers.Main) {
                        assertEquals(expected, order)
                        assertTrue(translation.states.values.all { it == R.string.translation_done })
                        assertTrue(sr.states.values.all { it == R.string.sr_done })
                    }
                    assertEquals(originals, hashes())
                    report.appendText("T ahead=$translationAhead S ahead=$srAhead full=$full: $order PASS; original hashes unchanged\n")
                } finally {
                    withContext(Dispatchers.Main) { TranslationTasks.release(translation); SuperResolutionTasks.release(sr) }
                    withTimeout(10_000) { while (withContext(Dispatchers.Main) { sr.working }) delay(20) }
                }
            }
        } finally {
            translationSettings.save(previous)
            srSettings.preferences.edit().apply {
                keys.forEach(::remove)
                saved.forEach { (key, value) -> when (value) { is Boolean -> putBoolean(key, value); is String -> putString(key, value) } }
            }.commit()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private class Provider(private val files: List<File>, private val identity: String) : GalleryProvider2() {
        override fun getTranslationIdentity() = identity
        override fun createTranslationProvider(context: Context) = Provider(files, identity)
        override fun size() = files.size
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index.png"
        override fun save(index: Int, file: UniFile): Boolean {
            file.openOutputStream().use { output -> files[index].inputStream().use { it.copyTo(output) } }
            return true
        }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
