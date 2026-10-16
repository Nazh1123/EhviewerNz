package com.hippo.ehviewer.superresolution

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class SuperResolutionPageMetrics(
    var computedTiles: Int = 0, var reusedTiles: Int = 0, var inferenceMs: Long = 0,
    var encodeMs: Long = 0, var displayMs: Long = -1, var totalMs: Long = 0,
    var maxOutputBitmapBytes: Int = 0,
)

/** A one-element encoding queue overlaps bounded PNG work with inference, never full-page allocation. */
internal class SuperResolutionPageProcessor(private val context: Context) {
    internal class Animation : RuntimeException()
    internal class MemoryBudget : RuntimeException()
    private class Encoding(val index: Int, val pixels: EnhancedTilePage.Pixels) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) pixels.close() }
    }

    suspend fun process(source: File, store: SuperResolutionStore, key: String, budget: Long,
                        checkPriority: () -> Unit,
                        render: suspend (Bitmap, Rect, (Int, Int) -> Unit) -> Bitmap,
                        progress: (Int) -> Unit,
                        ready: suspend (EnhancedTilePage) -> Boolean,
                        grouped: Boolean = false): Pair<EnhancedTilePage, SuperResolutionPageMetrics> {
        val start = SystemClock.elapsedRealtime()
        val metrics = SuperResolutionPageMetrics()
        store.findTiles(key)?.takeIf { page -> (0 until page.count).all(page::complete) }?.let {
            val page = store.retainTiles(it)
            ready(page)
            metrics.reusedTiles = page.count; metrics.displayMs = SystemClock.elapsedRealtime() - start
            metrics.totalMs = metrics.displayMs
            return page to metrics
        }
        checkPriority()
        val legacy = store.find(key)
        val decoder = legacy?.let { BitmapRegionDecoder.newInstance(it.path, false) }
        var original: Bitmap? = null
        var page: EnhancedTilePage? = null
        var displayed = false
        var band: Bitmap? = null
        var bandRegion = Rect()
        try {
            if (decoder == null) original = decode(source, budget)
            val width = decoder?.width?.div(2) ?: checkNotNull(original).width
            val height = decoder?.height?.div(2) ?: checkNotNull(original).height
            page = store.checkpoint(key, width, height)
            val tiles = page
            val missing = (0 until tiles.count).filterNot(tiles::complete)
            metrics.reusedTiles = tiles.count - missing.size
            progress(metrics.reusedTiles * 100 / tiles.count)
            coroutineScope {
                val encoding = Channel<Encoding>(1, onUndeliveredElement = { it.close() })
                val writer = launch(Dispatchers.IO) {
                    for (task in encoding) try {
                        val encodeStart = SystemClock.elapsedRealtime()
                        // Finish the single small checkpoint already being encoded before yielding to translation.
                        withContext(NonCancellable) { store.saveTile(tiles, task.index, task.pixels.bitmap) }
                        metrics.encodeMs += SystemClock.elapsedRealtime() - encodeStart
                    } finally { task.close() }
                }
                try {
                    for ((position, index) in missing.withIndex()) {
                        currentCoroutineContext().ensureActive(); checkPriority()
                        val region = tiles.region(index)
                        val inferenceStart = SystemClock.elapsedRealtime()
                        val bitmap = if (decoder != null) checkNotNull(decoder.decodeRegion(region,
                            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }))
                        else {
                            val input = checkNotNull(original)
                            if (band == null || !bandRegion.contains(region)) {
                                band?.recycle(); band = null
                                bandRegion = Rect(region)
                                if (grouped) {
                                    // Share strip uploads/JNI dispatch across neighbouring tiles.
                                    // The temporary band stays <=8 MiB, independent of page height.
                                    val maxWidth = (BAND_BYTES / region.height() / 4).toInt() / 2 * 2
                                    bandRegion.right = minOf(tiles.width, region.left + maxWidth)
                                }
                                band = render(input, Rect(bandRegion.left / 2, bandRegion.top / 2,
                                    bandRegion.right / 2, bandRegion.bottom / 2)) { done, total ->
                                    progress(((metrics.reusedTiles + position + done.toDouble() / total.coerceAtLeast(1)) * 100 / tiles.count).toInt())
                                }
                                metrics.maxOutputBitmapBytes = maxOf(metrics.maxOutputBitmapBytes, checkNotNull(band).allocationByteCount)
                            }
                            Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888).also { tile ->
                                Canvas(tile).drawBitmap(checkNotNull(band), Rect(region.left - bandRegion.left, region.top - bandRegion.top,
                                    region.right - bandRegion.left, region.bottom - bandRegion.top), Rect(0, 0, tile.width, tile.height), null)
                            }
                        }
                        metrics.inferenceMs += SystemClock.elapsedRealtime() - inferenceStart
                        metrics.computedTiles++
                        metrics.maxOutputBitmapBytes = maxOf(metrics.maxOutputBitmapBytes, bitmap.allocationByteCount)
                        val task = Encoding(index, tiles.remember(index, bitmap))
                        try {
                            if (position == missing.lastIndex) {
                                currentCoroutineContext().ensureActive(); checkPriority()
                                // The last pixels are still in the shared cache: do not wait for PNG or read it back.
                                displayed = ready(tiles)
                                metrics.displayMs = SystemClock.elapsedRealtime() - start
                            }
                            encoding.send(task)
                            progress((metrics.reusedTiles + position + 1) * 100 / tiles.count)
                        } catch (error: Throwable) { task.close(); throw error }
                    }
                    if (missing.isEmpty()) { displayed = ready(tiles); metrics.displayMs = SystemClock.elapsedRealtime() - start }
                    encoding.close(); writer.join()
                } finally { encoding.cancel(); writer.cancelAndJoin() }
            }
            currentCoroutineContext().ensureActive()
            store.finish(tiles)
            metrics.totalMs = SystemClock.elapsedRealtime() - start
            return tiles to metrics
        } finally {
            band?.recycle(); original?.recycle(); decoder?.recycle()
            if (!displayed) page?.trim()
        }
    }
    companion object { const val BAND_BYTES = 8L * 1024 * 1024 }

    private fun decode(file: File, budget: Long): Bitmap {
        val header = ByteArray(21)
        val count = file.inputStream().use { it.read(header) }
        if ((count >= 3 && String(header, 0, 3, Charsets.US_ASCII) == "GIF") ||
            (count >= 21 && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && header[20].toInt() and 2 != 0)) throw Animation()
        fun checkSize(w: Int, h: Int) {
            if (w !in 1..32768 || h !in 1..32768 || w.toLong() * h * 4 > budget) throw MemoryBudget()
        }
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            if (info.isAnimated) throw Animation()
            checkSize(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.isMutableRequired = true
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds); checkSize(bounds.outWidth, bounds.outHeight)
        return checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    }
}
