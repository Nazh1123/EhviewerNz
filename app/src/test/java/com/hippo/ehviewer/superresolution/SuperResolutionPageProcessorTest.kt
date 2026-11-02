package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.hippo.ehviewer.translation.engine.SuperResolution
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuperResolutionPageProcessorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { SuperResolutionStore.clear(context); SuperResolutionStore.clear(context, true) }
    private fun source(width: Int = 513, height: Int = 385): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE); bitmap.setPixel(width / 2, height / 2, Color.BLACK)
        return File(context.cacheDir, "page.png").also {
            try { it.outputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) } }
            finally { bitmap.recycle() }
        }
    }
    private fun render(bitmap: Bitmap, rect: Rect, progress: (Int, Int) -> Unit): Bitmap {
        val region = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        return try { Bitmap.createScaledBitmap(region, region.width * 2, region.height * 2, false).also { progress(1, 1) } }
        finally { if (region !== bitmap) region.recycle() }
    }

    @Test fun directPixelsAreAvailableBeforeFinalEncodingAndPublishedPageReopensWithoutInference() = runBlocking {
        val source = source()
        val store = SuperResolutionStore(context, "one", SuperResolutionOptions())
        val key = store.key(source)
        var displayed = false
        val (page, metrics) = SuperResolutionPageProcessor(context).process(source, store, key, 8L * 1024 * 1024,
            {}, ::render, {}, { page ->
                assertNull(store.findTiles(key))
                assertFalse(page.file(page.count - 1).exists())
                checkNotNull(page.acquire(page.count - 1, 1)).close()
                displayed = true; true
            })
        assertTrue(displayed)
        assertTrue(metrics.displayMs <= metrics.totalMs)
        assertTrue(metrics.maxOutputBitmapBytes <= 512 * 512 * 4)
        assertEquals(1026, page.width); assertEquals(770, page.height)
        assertTrue((0 until page.count).all(page::complete))
        assertTrue(page.cachedBytes() <= EnhancedTilePage.CPU_CACHE_BYTES)
        page.trim()
        val (_, cached) = SuperResolutionPageProcessor(context).process(source, store, key, 8L * 1024 * 1024,
            {}, { _, _, _ -> error("Cache hit must not load a model") }, {}, { false })
        assertEquals(0, cached.computedTiles); assertEquals(page.count, cached.reusedTiles)
    }

    @Test fun interruptionKeepsCompletedTilesAndResumeDoesNotRecomputeThem() = runBlocking {
        val source = source()
        val store = SuperResolutionStore(context, "resume", SuperResolutionOptions())
        val key = store.key(source)
        var calls = 0
        try {
            SuperResolutionPageProcessor(context).process(source, store, key, 8L * 1024 * 1024, {},
                { bitmap, rect, progress -> if (++calls == 3) throw CancellationException("translation"); render(bitmap, rect, progress) },
                {}, { fail("Incomplete result must not publish"); false })
            fail("Expected interruption")
        } catch (_: CancellationException) { }
        assertNull(store.findTiles(key))
        val checkpoint = store.checkpoint(key, 513, 385)
        val retained = (0 until checkpoint.count).count(checkpoint::complete)
        assertTrue("At least one completed checkpoint must survive", retained > 0)
        calls = 0
        val (page, metrics) = SuperResolutionPageProcessor(context).process(source, store, key, 8L * 1024 * 1024, {},
            { bitmap, rect, progress -> calls++; render(bitmap, rect, progress) }, {}, { false })
        assertEquals(page.count - retained, calls)
        assertEquals(retained, metrics.reusedTiles)
        assertNotNull(store.findTiles(key))
    }

    @Test fun outputCanExceedFullBitmapBudgetAndRetiringCacheKeepsBorrowedPixelsAlive() = runBlocking {
        val source = source(1024, 1024)
        val store = SuperResolutionStore(context, "large", SuperResolutionOptions())
        val (page, metrics) = SuperResolutionPageProcessor(context).process(source, store, store.key(source),
            6L * 1024 * 1024, {}, ::render, {}, { false })
        assertTrue(page.width.toLong() * page.height * 4 > 6L * 1024 * 1024)
        assertTrue(metrics.maxOutputBitmapBytes <= 512 * 512 * 4)
        val borrowed = checkNotNull(page.acquire(0, 1))
        page.trim()
        assertFalse(borrowed.bitmap.isRecycled)
        assertEquals(0L, page.cachedBytes())
        val late = checkNotNull(page.acquire(1, 1)); late.close()
        assertEquals("Retired pages cannot accumulate late IO results", 0L, page.cachedBytes())
        borrowed.close(); assertTrue(borrowed.bitmap.isRecycled)
    }

    @Test fun groupedStripsShareInferenceButSaveIndependentCorrectlyCroppedTiles() = runBlocking {
        val source = source(1024, 1024)
        val store = SuperResolutionStore(context, "bands", SuperResolutionOptions())
        var calls = 0
        val updates = mutableListOf<Int>()
        val (page, metrics) = SuperResolutionPageProcessor(context).process(source, store, store.key(source), 6L * 1024 * 1024,
            {}, { bitmap, rect, callback ->
                calls++
                repeat(1000) { callback(it + 1, 1000) }
                render(bitmap, rect, callback)
            }, { updates += it }, { false }, grouped = true)
        assertEquals(0, updates.first())
        assertEquals(100, updates.last())
        assertEquals("Only changed percentages should reach the UI", updates.distinct(), updates)
        assertEquals(updates.sorted(), updates)
        assertEquals(page.rows, calls)
        assertEquals(page.count, metrics.computedTiles)
        assertTrue(metrics.maxOutputBitmapBytes <= SuperResolutionPageProcessor.BAND_BYTES)
        assertTrue(metrics.maxOutputBitmapBytes < page.width * page.height * 4)
        val index = (512 / EnhancedTilePage.EDGE) * page.columns + 512 / EnhancedTilePage.EDGE
        val pixels = checkNotNull(page.acquire(index, 1))
        try {
            val region = page.region(index)
            assertEquals(Color.BLACK, pixels.bitmap.getPixel(1024 - region.left, 1024 - region.top))
            assertEquals(Color.WHITE, pixels.bitmap.getPixel(0, 0))
        } finally { pixels.close(); page.trim() }
    }

    @Test fun groupedResumeOnlyRendersMissingTileRuns() = runBlocking {
        val source = source(1024, 128)
        val store = SuperResolutionStore(context, "sparse-resume", SuperResolutionOptions())
        val key = store.key(source)
        val checkpoint = store.checkpoint(key, 1024, 128)
        val missing = listOf(0, 2)
        for (index in 0 until checkpoint.count) if (index !in missing) {
            val region = checkpoint.region(index)
            val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
            try { store.saveTile(checkpoint, index, bitmap) } finally { bitmap.recycle() }
        }
        val rendered = mutableListOf<Rect>()
        val (page, metrics) = SuperResolutionPageProcessor(context).process(source, store, key, 1024L * 1024,
            {}, { bitmap, rect, callback ->
                rendered += Rect(rect)
                render(bitmap, rect, callback)
            }, {}, { false }, grouped = true)
        val expected = missing.map { index -> checkpoint.region(index).let {
            Rect(it.left / 2, it.top / 2, it.right / 2, it.bottom / 2)
        } }
        assertEquals("Saved tiles must not extend the inference bands", expected, rendered)
        assertEquals(missing.size, metrics.computedTiles)
        assertEquals(page.count - missing.size, metrics.reusedTiles)
        assertTrue((0 until page.count).all(page::complete))
    }

    @Test fun singleTileTransfersRenderedPixelsWithoutCopyingAndReleasesThemAfterEncoding() = runBlocking {
        val source = source(81, 53)
        for (grouped in listOf(false, true)) {
            val store = SuperResolutionStore(context, "single-$grouped", SuperResolutionOptions())
            var rendered: Bitmap? = null
            val (page, _) = SuperResolutionPageProcessor(context).process(source, store, store.key(source), 1024L * 1024,
                {}, { bitmap, rect, callback -> render(bitmap, rect, callback).also { rendered = it } }, {}, { tiles ->
                    val pixels = checkNotNull(tiles.acquire(0, 1))
                    try {
                        assertSame("Encoding and display should share the inference output", rendered, pixels.bitmap)
                        assertFalse(pixels.bitmap.isRecycled)
                    } finally { pixels.close() }
                    false
                }, grouped = grouped)
            assertTrue(page.complete(0))
            assertTrue(checkNotNull(rendered).isRecycled)
            assertEquals(0L, page.cachedBytes())
        }
    }

    @Test fun failedPublicationReleasesTransferredPixelsButKeepsReadersBorrowAlive() = runBlocking {
        val source = source(81, 53)
        val store = SuperResolutionStore(context, "failed-publication", SuperResolutionOptions())
        var rendered: Bitmap? = null
        var borrowed: EnhancedTilePage.Pixels? = null
        try {
            SuperResolutionPageProcessor(context).process(source, store, store.key(source), 1024L * 1024,
                {}, { bitmap, rect, callback -> render(bitmap, rect, callback).also { rendered = it } }, {}, { tiles ->
                    borrowed = checkNotNull(tiles.acquire(0, 1))
                    throw CancellationException("Reader left")
                }, grouped = true)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertSame(rendered, checkNotNull(borrowed).bitmap)
            assertFalse(checkNotNull(rendered).isRecycled)
        } finally { borrowed?.close() }
        assertTrue(checkNotNull(rendered).isRecycled)
    }

    @Test fun higherAndAutomaticScalesKeepTilesBoundedAndReopenWithoutInference() = runBlocking {
        val source = source(255, 151)
        for (mode in listOf(SuperResolutionScale.X4, SuperResolutionScale.X8, SuperResolutionScale.AUTO)) {
            val options = SuperResolutionOptions(scale = mode, persist = false)
            val store = SuperResolutionStore(context, "scale-${mode.value}", options)
            val key = store.key(source)
            val (page, metrics) = SuperResolutionPageProcessor(context).process(source, store, key, 1024L * 1024,
                {}, ::render, {}, { false }, grouped = true)
            val scale = mode.resolve(255, 151)
            assertEquals(255 * scale, page.width); assertEquals(151 * scale, page.height)
            assertEquals(scale, page.scale)
            assertTrue(metrics.maxOutputBitmapBytes <= SuperResolutionPageProcessor.BAND_BYTES)
            for (index in 0 until page.count) {
                assertTrue(page.complete(index))
                assertTrue(page.region(index).width() <= 512 && page.region(index).height() <= 512)
            }
            val region = page.region(0)
            val pixels = checkNotNull(page.acquire(0, 1))
            try { assertEquals(Color.WHITE, pixels.bitmap.getPixel(0, 0)) }
            finally { pixels.close(); page.trim() }
            val persistent = SuperResolutionStore(context, "scale-${mode.value}", options.copy(persist = true))
            val (retained, reused) = SuperResolutionPageProcessor(context).process(source, persistent, key, 1024L * 1024,
                {}, { _, _, _ -> error("Cached page must not load model") }, {}, { false })
            assertEquals(0, reused.computedTiles)
            assertEquals(page.count, reused.reusedTiles)
            assertEquals(region, retained.region(0))
            assertEquals(scale, retained.scale)
            SuperResolutionStore.clear(context)
            assertNotNull(persistent.findTiles(key))
        }
    }

    @Test fun interruptedEightTimesPageResumesSavedTiles() = runBlocking {
        val source = source(127, 129)
        val store = SuperResolutionStore(context, "resume-eight", SuperResolutionOptions(scale = SuperResolutionScale.X8))
        val key = store.key(source)
        var calls = 0
        try {
            SuperResolutionPageProcessor(context).process(source, store, key, 1024L * 1024, {},
                { bitmap, rect, callback ->
                    if (++calls == 7) throw CancellationException("translation")
                    render(bitmap, rect, callback)
                }, {}, { fail("Incomplete page must not publish"); false })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertNull(store.findTiles(key))
        val partial = store.checkpoint(key, 127, 129)
        val saved = (0 until partial.count).count(partial::complete)
        assertTrue(saved > 0)
        val (page, stats) = SuperResolutionPageProcessor(context).process(source, store, key, 1024L * 1024,
            {}, ::render, {}, { false })
        assertEquals(saved, stats.reusedTiles)
        assertEquals(page.count - saved, stats.computedTiles)
        assertEquals(8, page.scale)
    }

    @Test fun veryWideEightTimesBandsRespectNativeInputDimensions() = runBlocking {
        val source = source(16385, 1)
        val store = SuperResolutionStore(context, "wide-eight", SuperResolutionOptions(scale = SuperResolutionScale.X8))
        val (page, _) = SuperResolutionPageProcessor(context).process(source, store, store.key(source), 1024L * 1024, {},
            { bitmap, rect, callback ->
                assertTrue("Every pass must fit the native dimension and byte limits",
                    SuperResolution.accepts(rect.width(), rect.height(), SuperResolutionPageProcessor.BAND_BYTES))
                render(bitmap, rect, callback)
            }, {}, { false }, grouped = true)
        assertEquals(131080, page.width)
        assertEquals(8, page.height)
    }
}
