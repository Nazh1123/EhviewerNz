package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.widget.ImageButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.lib.glgallery.GalleryView
import com.hippo.lib.glgallery.GalleryPageView
import com.hippo.lib.glview.image.EnhancedPageTexture
import com.hippo.lib.glview.view.GLRootView
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies actual queue selection, reader textures, page changes and settings reload. */
@RunWith(AndroidJUnit4::class)
class SuperResolutionScaleReaderDeviceTest {
    @Test fun mixedResolutionReaderAndScaleChanges() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SuperResolutionSettings(context)
        val keys = listOf(SuperResolutionSettings.SCALE, SuperResolutionSettings.AHEAD, SuperResolutionSettings.PERSIST,
            SuperResolutionModels.BACKEND, SuperResolutionSettings.AUTO_ENABLE)
        val previous = settings.preferences.all.filterKeys { it in keys }
        val output = File(context.getExternalFilesDir(null), "sr-scale-validation").apply { mkdirs() }
        val directory = File(context.cacheDir, "sr-scale-reader-${System.nanoTime()}").apply { mkdirs() }
        val sizes = listOf(151 to 211, 301 to 421, 601 to 841)
        for ((index, size) in sizes.withIndex()) {
            val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 15f; strokeWidth = 1f }
            val canvas = Canvas(bitmap)
            canvas.drawText("AUTO PAGE $index", 5f, 21f, paint)
            for (y in 30 until bitmap.height step 21) canvas.drawLine(3f, y.toFloat(), bitmap.width - 3f, y + 7f, paint)
            try { File(directory, "$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        settings.preferences.edit().putString(SuperResolutionSettings.SCALE, "auto")
            .putString(SuperResolutionSettings.AHEAD, "0").putBoolean(SuperResolutionSettings.PERSIST, false)
            .putString(SuperResolutionModels.BACKEND, "auto_fast").putBoolean(SuperResolutionSettings.AUTO_ENABLE, false).commit()
        var activity: GalleryActivity? = null
        var controller: ReaderSuperResolutionController? = null
        val report = JSONObject(); val pages = JSONArray(); report.put("pages", pages)
        suspend fun await(predicate: () -> Boolean) = withTimeout(120_000) {
            while (!withContext(Dispatchers.Main) { predicate() }) delay(40)
        }
        fun screenshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                try { File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { image.recycle() }
            }
        }
        try {
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.hippo.ehviewer.ui.splash.SplashActivity")
                .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            val reader = instrumentation.startActivitySync(Intent(context, GalleryActivity::class.java)
                .setAction(GalleryActivity.ACTION_DIR).putExtra(GalleryActivity.KEY_FILENAME, directory.path)
                .putExtra(GalleryActivity.KEY_PAGE, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GalleryActivity
            activity = reader
            instrumentation.runOnMainSync { reader.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            fun field(name: String) = GalleryActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(reader)
            await { field("mSuperResolutionController") != null }
            val controls = field("mSuperResolutionController") as ReaderSuperResolutionController
            controller = controls
            val provider = field("mGalleryProvider") as GalleryProvider2
            val gallery = field("mGalleryView") as GalleryView
            val root = reader.findViewById<GLRootView>(R.id.gl_root_view)
            fun textureState(index: Int): JSONObject {
                root.lockRenderThread()
                return try {
                    val view = gallery.findPageByIndex(index) ?: return JSONObject().put("ready", false)
                    val image = GalleryPageView::class.java.getDeclaredField("mImage").apply { isAccessible = true }.get(view)
                    val texture = image.javaClass.getDeclaredField("mEnhancedTiles").apply { isAccessible = true }.get(image)
                        as? EnhancedPageTexture ?: return JSONObject().put("ready", false)
                    synchronized(texture) {
                        fun member(name: String) = EnhancedPageTexture::class.java.getDeclaredField(name).apply { isAccessible = true }.get(texture)
                        val desired = member("desired") as Set<*>
                        val loaded = member("textures") as Map<*, *>
                        val failed = member("failed") as Set<*>
                        val tilePage = member("page") as EnhancedTilePage
                        JSONObject().put("ready", tilePage === provider.getEnhancedTiles(index) &&
                            desired.isNotEmpty() && desired.all(loaded::containsKey))
                            .put("scale", tilePage.scale)
                            .put("visible", desired.size).put("loaded", loaded.size).put("failed", failed.size)
                            .put("residentBytes", texture.residentBytes())
                    }
                } finally { root.unlockRenderThread() }
            }
            suspend fun awaitTextures(index: Int): JSONObject {
                var state = JSONObject()
                withTimeout(30_000) {
                    while (true) {
                        state = withContext(Dispatchers.Main) { textureState(index) }
                        if (state.optBoolean("ready")) break
                        delay(100)
                    }
                }
                assertEquals(0, state.getInt("failed"))
                assertTrue(state.getLong("residentBytes") <= EnhancedPageTexture.GL_CACHE_BYTES)
                delay(200)
                return state
            }
            await { controls.session.current == 0 }
            instrumentation.runOnMainSync { reader.findViewById<ImageButton>(R.id.quick_super_resolution).performClick() }
            for (index in sizes.indices) {
                if (index > 0) instrumentation.runOnMainSync { gallery.setCurrentPage(index) }
                await { controls.session.current == index && !controls.session.working && provider.getEnhancedTiles(index) != null }
                withContext(Dispatchers.Main) {
                    assertEquals(R.string.sr_done, controls.session.states[index])
                    val tiles = checkNotNull(provider.getEnhancedTiles(index))
                    val expected = listOf(8, 4, 2)[index]
                    assertEquals(expected, tiles.scale)
                    pages.put(JSONObject().put("page", index).put("scale", tiles.scale)
                        .put("width", tiles.width).put("height", tiles.height))
                }
                val textures = awaitTextures(index)
                pages.getJSONObject(pages.length() - 1).put("textures", textures)
                screenshot("reader-auto-$index")
            }
            for (scale in listOf(4, 8)) {
                settings.preferences.edit().putString(SuperResolutionSettings.SCALE, scale.toString()).commit()
                withContext(Dispatchers.Main) { controls.resume() }
                await { !controls.session.working && provider.getEnhancedTiles(2)?.scale == scale }
                withContext(Dispatchers.Main) { assertEquals(R.string.sr_done, controls.session.states[2]) }
                if (scale == 8) { delay(600); screenshot("reader-fixed-8x-loading") }
                val textures = awaitTextures(2)
                screenshot("reader-fixed-${scale}x")
                pages.put(JSONObject().put("page", 2).put("setting", "fixed").put("scale", scale).put("textures", textures))
            }
            report.put("result", "PASS")
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("error", error.toString()); throw error
        } finally {
            withContext(Dispatchers.Main) { controller?.session?.let(SuperResolutionTasks::release); activity?.finish() }
            settings.preferences.edit().apply {
                for (key in keys) {
                    remove(key)
                    when (val value = previous[key]) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
            File(output, "reader.json").writeText(report.toString(2))
        }
    }
}
