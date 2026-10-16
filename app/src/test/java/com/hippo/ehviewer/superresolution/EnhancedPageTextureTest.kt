package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.hippo.lib.glview.glrenderer.GLCanvas
import com.hippo.lib.glview.image.EnhancedPageTexture
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnhancedPageTextureTest {
    @Test fun crossTileCropFillsViewportAndPanEvictsPreviousTextures() = runBlocking {
        for (scale in listOf(2, 4, 8)) verifyCropAndPan(scale)
    }

    private suspend fun verifyCropAndPan(scale: Int) {
        val context = RuntimeEnvironment.getApplication()
        val page = EnhancedTilePage(File(context.cacheDir, "texture-${System.nanoTime()}").apply { mkdirs() }, 1200 / scale, 1200 / scale, scale)
        val store = SuperResolutionStore(context, "texture-test", SuperResolutionOptions(persist = false))
        for (index in 0 until page.count) {
            val rect = page.region(index)
            val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try { store.saveTile(page, index, bitmap) } finally { bitmap.recycle() }
        }
        val destinations = mutableListOf<RectF>()
        val canvas = Proxy.newProxyInstance(GLCanvas::class.java.classLoader, arrayOf(GLCanvas::class.java)) { _, method, args ->
            if (method.name == "drawTexture" && args?.getOrNull(1) is RectF) {
                destinations.add(RectF(args[2] as RectF))
            }
            null
        } as GLCanvas
        val texture = EnhancedPageTexture(page, null)
        fun drawUntil(count: Int, source: RectF) {
            val deadline = System.nanoTime() + 3_000_000_000L
            do {
                destinations.clear()
                texture.draw(canvas, source, RectF(0f, 0f, 100f, 100f))
                if (destinations.size == count) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            fail("Visible SR tiles did not load: $destinations")
        }
        try {
            drawUntil(4, RectF(480f, 480f, 530f, 530f))
            assertEquals(10_000.0, destinations.sumOf { (it.width() * it.height()).toDouble() }, .01)
            assertTrue(destinations.all { it.left >= 0 && it.top >= 0 && it.right <= 100 && it.bottom <= 100 })
            val previous = texture.residentBytes()
            drawUntil(1, RectF(1040f, 1040f, 1100f, 1100f))
            assertTrue(texture.residentBytes() < previous)
            assertTrue(texture.residentBytes() <= EnhancedPageTexture.GL_CACHE_BYTES)
        } finally { texture.recycle(); page.trim() }
        assertEquals(0L, texture.residentBytes())
    }
}
