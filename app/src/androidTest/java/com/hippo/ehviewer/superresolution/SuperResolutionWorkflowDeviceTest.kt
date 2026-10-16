package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.widget.ImageButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.translation.ImageProcessingPriority
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.fragment.SettingsHeaders
import com.hippo.ehviewer.ui.fragment.SuperResolutionFragment
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real reader + source provider + GPU + service + disk restore; no external gallery required. */
@RunWith(AndroidJUnit4::class)
class SuperResolutionWorkflowDeviceTest {
    @Test fun settingsEntryAndControlsAreIndependentOfReadingSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        try {
            instrumentation.runOnMainSync {
                activity.supportFragmentManager.executePendingTransactions()
                val headers = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsHeaders
                val screen = headers.preferenceScreen
                val entry = (0 until screen.preferenceCount).map(screen::getPreference)
                    .single { it.fragment == SuperResolutionFragment::class.java.name }
                assertEquals(activity.getString(R.string.sr_title), entry.title)
                activity.supportFragmentManager.beginTransaction().replace(R.id.settings, SuperResolutionFragment()).commitNow()
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SuperResolutionFragment
                assertNotNull(fragment.findPreference<androidx.preference.ListPreference>(SuperResolutionSettings.AHEAD))
                assertNotNull(fragment.findPreference<androidx.preference.SwitchPreferenceCompat>(SuperResolutionSettings.PERSIST))
                assertNotNull(fragment.findPreference<androidx.preference.ListPreference>(SuperResolutionSettings.CACHE))
                assertEquals(listOf("auto", "auto_fast", "cpu"), fragment.findPreference<androidx.preference.ListPreference>(SuperResolutionModels.BACKEND)!!.entryValues.map { it.toString() })
                val reading = com.hippo.ehviewer.ui.fragment.ReadFragment()
                activity.supportFragmentManager.beginTransaction().replace(R.id.settings, reading).commitNow()
                assertNull(reading.findPreference<androidx.preference.Preference>("super_resolution_settings"))
                activity.supportFragmentManager.beginTransaction().replace(R.id.settings, SuperResolutionFragment()).commitNow()
            }
            instrumentation.waitForIdleSync()
            lateinit var screenshot: Bitmap
            instrumentation.runOnMainSync {
                val view = activity.window.decorView
                screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(screenshot))
            }
            File(context.cacheDir, "super-resolution-settings.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun prefetchTranslationPriorityFullGalleryAndPersistentRestore() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SuperResolutionSettings(context)
        val keys = listOf(SuperResolutionModels.ENABLED, SuperResolutionModels.BACKEND, SuperResolutionSettings.AHEAD, SuperResolutionSettings.PERSIST)
        val previous = settings.preferences.all.filterKeys { it in keys }
        settings.preferences.edit().putBoolean(SuperResolutionModels.ENABLED, false)
            .putString(SuperResolutionModels.BACKEND, "auto_fast")
            .putString(SuperResolutionSettings.AHEAD, "1").putBoolean(SuperResolutionSettings.PERSIST, true).commit()
        val directory = File(context.cacheDir, "sr-workflow-${System.currentTimeMillis()}").apply { mkdirs() }
        val sources = (0..4).map { page ->
            val bitmap = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 36f; strokeWidth = 2f }
            val canvas = Canvas(bitmap)
            canvas.drawText("ORIGINAL PAGE $page", 40f, 80f, paint)
            for (y in 128 until 1000 step 64) canvas.drawLine(40f, y.toFloat(), 720f, y.toFloat(), paint)
            File(directory, "$page.png").also { file ->
                try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
        }
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        val hashes = sources.map(::hash)
        val report = StringBuilder()
        var activity: GalleryActivity? = null
        var session: GallerySuperResolutionSession? = null
        var translation: Job? = null
        val releaseTranslation = CompletableDeferred<Unit>()
        suspend fun await(label: String, predicate: () -> Boolean) {
            withTimeout(60_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(20) }
            report.append("$label=PASS\n")
        }
        fun launchReader(): GalleryActivity {
            val reader = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java)
                .setAction(GalleryActivity.ACTION_DIR).putExtra(GalleryActivity.KEY_FILENAME, directory.path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GalleryActivity
            instrumentation.runOnMainSync { reader.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            return reader
        }
        fun controller(reader: GalleryActivity): ReaderSuperResolutionController {
            val field = GalleryActivity::class.java.getDeclaredField("mSuperResolutionController").apply { isAccessible = true }
            return field.get(reader) as ReaderSuperResolutionController
        }
        try {
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
                .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            activity = launchReader()
            val first = activity
            await("readerReady") { controller(first).session.current >= 0 }
            session = controller(first).session
            val queue = session
            withContext(Dispatchers.Main) { first.findViewById<ImageButton>(R.id.quick_super_resolution).performClick() }
            await("nativeProgress") { queue.activePage == 0 && queue.progress in 1..90 }
            val admitted = CompletableDeferred<Unit>()
            translation = launch(Dispatchers.IO) {
                ImageProcessingPriority.work.run(translation = true) { admitted.complete(Unit); releaseTranslation.await() }
            }
            withTimeout(10_000) { admitted.await() }
            withContext(Dispatchers.Main) {
                assertFalse("An interrupted partial SR image must not publish", queue.states.containsKey(0))
                assertTrue(queue.working)
            }
            report.append("translationPreemptsAtTileBoundary=PASS\n")
            releaseTranslation.complete(Unit); translation.join()
            await("prefetchWindow") { !queue.working && queue.states.keys.containsAll(listOf(0, 1)) }
            withContext(Dispatchers.Main) {
                assertEquals(setOf(0, 1), queue.states.keys)
                assertEquals(2, queue.total)
                assertEquals(2, queue.completed)
                assertTrue(queue.states.values.all { it == R.string.sr_done })
                assertNotNull(first.findViewById<android.widget.ProgressBar>(R.id.super_resolution_queue_progress_bar))
                queue.full()
            }
            withContext(Dispatchers.Main) { first.finish() }
            instrumentation.waitForIdleSync()
            await("backgroundFullGallery") { !queue.working && queue.states.size == sources.size }
            assertEquals(hashes, sources.map(::hash))
            report.append("originalBytesUnchanged=PASS\n")
            val saved = withContext(Dispatchers.IO) {
                val store = SuperResolutionStore(context, queue.identity, settings.read())
                sources.map { checkNotNull(store.findTiles(store.key(it))).directory }
            }
            assertEquals(5, saved.size)
            withContext(Dispatchers.Main) { SuperResolutionTasks.release(queue) }
            withContext(Dispatchers.IO) { SuperResolutionModels.lock.lock(); try { SuperResolutionStore.clear(context) } finally { SuperResolutionModels.lock.unlock() } }
            val reopenStart = SystemClock.elapsedRealtime()
            activity = launchReader()
            val reopened = activity
            await("reopenCachedWindow") { controller(reopened).session.states.size >= 2 && !controller(reopened).session.working }
            session = controller(reopened).session
            val engines = GallerySuperResolutionSession::class.java.getDeclaredField("engines").apply { isAccessible = true }.get(session)
            val loaded = SuperResolutionSessionCache::class.java.getDeclaredField("value").apply { isAccessible = true }.get(engines)
            assertNull("Restoring saved images must not load NCNN", loaded)
            report.append("reopenWithoutInferenceMs=${SystemClock.elapsedRealtime() - reopenStart}\n")
            assertTrue(saved.all { it.isDirectory })
            report.append("PASS\n")
        } finally {
            releaseTranslation.complete(Unit); translation?.cancelAndJoin()
            withContext(Dispatchers.Main) { session?.let(SuperResolutionTasks::release); activity?.finish() }
            val edit = settings.preferences.edit()
            keys.forEach { key ->
                edit.remove(key)
                when (val value = previous[key]) { is Boolean -> edit.putBoolean(key, value); is String -> edit.putString(key, value) }
            }
            edit.commit()
            File(context.cacheDir, "super-resolution-workflow-test.txt").writeText(report.toString())
        }
    }
}
