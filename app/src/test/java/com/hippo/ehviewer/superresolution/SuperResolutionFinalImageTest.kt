package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuperResolutionFinalImageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { SuperResolutionStore.clear(context); SuperResolutionStore.clear(context, true) }

    private suspend fun page(store: SuperResolutionStore): EnhancedTilePage {
        val page = store.checkpoint("page", 259, 257)
        for (index in 0 until page.count) {
            val region = page.region(index)
            val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                if (region.contains(504, 504)) bitmap.setPixel(504 - region.left, 504 - region.top, Color.BLACK)
                store.saveTile(page, index, bitmap)
            } finally { bitmap.recycle() }
        }
        store.finish(page)
        return page
    }

    @Test fun compactionKeepsOneImageAndRegionReadsSurviveCheckpointDeletionAndReopening() = runBlocking {
        val store = SuperResolutionStore(context, "single", SuperResolutionOptions())
        val page = page(store)
        val held = checkNotNull(page.acquire(0, 1))
        val final = store.compact("page", page, null, "001")
        assertTrue(final.isFile)
        val decoded = checkNotNull(final.openInputStream().use { BitmapFactory.decodeStream(it) })
        try { assertEquals(Color.BLACK, decoded.getPixel(504, 504)); assertEquals(Color.WHITE, decoded.getPixel(100, 100)) }
        finally { decoded.recycle() }
        assertEquals(1, page.directory.listFiles()!!.count { it.extension in listOf("png", "webp") })
        assertTrue(page.directory.listFiles()!!.none { it.nameWithoutExtension.toIntOrNull() != null })
        assertFalse(held.bitmap.isRecycled)
        held.close(); page.trim()
        val reopened = checkNotNull(store.findTiles("page"))
        val index = reopened.columns + 1
        val pixels = checkNotNull(reopened.acquire(index, 1))
        try {
            // Robolectric's region decoder models geometry; native pixel values are checked on device.
            assertEquals(reopened.region(index).width(), pixels.bitmap.width)
            assertEquals(reopened.region(index).height(), pixels.bitmap.height)
        } finally { pixels.close(); reopened.trim() }
    }

    @Test fun galleryExportIsTheOnlyImageAndMovingTheLocalFinalDoesNotEncodeAgain() = runBlocking {
        val store = SuperResolutionStore(context, "export", SuperResolutionOptions())
        val page = page(store)
        val local = store.compact("page", page, null, "001")
        val bytes = local.openInputStream().use { it.readBytes() }
        val root = File(context.cacheDir, "webp-test-gallery").apply { mkdirs() }
        val gallery = checkNotNull(UniFile.fromFile(root))
        val exported = store.compact("page", page, gallery, "Chapter/001")
        assertArrayEquals(bytes, exported.openInputStream().use { it.readBytes() })
        assertFalse(local.exists())
        assertEquals(listOf("page.json"), page.directory.list()!!.toList())
        page.trim()
        assertEquals(exported.uri, checkNotNull(store.findTiles("page")).finalImage!!.uri)
        assertEquals(exported.uri, store.compact("page", page, gallery, "Chapter/001").uri)
    }

    @Test fun promotingACompactedCacheResultSurvivesCacheCleanupWithoutDuplicatingTheImage() = runBlocking {
        val cached = SuperResolutionStore(context, "promote", SuperResolutionOptions(persist = false))
        val page = page(cached)
        val original = cached.compact("page", page, null, "001")
        val persistent = SuperResolutionStore(context, "promote", SuperResolutionOptions())
        val retained = persistent.retainTiles(page)
        assertFalse(original.exists())
        assertEquals(retained.finalImage!!.uri, page.finalImage!!.uri)
        page.trim(); retained.trim()
        SuperResolutionStore.clear(context)
        val reopened = checkNotNull(persistent.findTiles("page"))
        assertTrue(reopened.finalImage!!.isFile)
        assertEquals(1, reopened.directory.listFiles()!!.count { it.extension in listOf("png", "webp") })
        reopened.trim()
    }

    @Test fun webpAdmissionChecksDimensionsAndWorkspaceWithLongArithmetic() {
        val budget = SuperResolutionWebp.MAX_WORKSPACE
        assertTrue(SuperResolutionWebp.accepts(2400, 3600, budget))
        assertTrue(SuperResolutionWebp.accepts(1200, 1600, budget, lossless = true))
        assertFalse(SuperResolutionWebp.accepts(2400, 3600, budget, lossless = true))
        assertFalse(SuperResolutionWebp.accepts(16384, 1, budget, lossless = true))
        assertFalse(SuperResolutionWebp.accepts(16384, 1, budget))
        assertFalse(SuperResolutionWebp.accepts(2400, 3600, 8L * 1024 * 1024))
        assertFalse(SuperResolutionWebp.accepts(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(SuperResolutionWebp.accepts(16000, 16000, Long.MAX_VALUE))
    }
}
