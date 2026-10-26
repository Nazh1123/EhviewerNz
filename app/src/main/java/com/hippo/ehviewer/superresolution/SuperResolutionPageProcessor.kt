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

/** A one-element encoding queue overlaps lossless checkpoint writes with inference. */
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
        var lastProgress = -1
        fun reportProgress(value: Int) {
            // Native tiles can generate many callbacks for the same visible percentage.
            if (value == lastProgress) return
            lastProgress = value
            progress(value)
        }
        store.findTiles(key)?.takeIf { page -> (0 until page.count).all(page::complete) }?.let {
            val page = store.retainTiles(it)
            ready(page)
            metrics.reusedTiles = page.count; metrics.displayMs = SystemClock.elapsedRealtime() - start
            metrics.totalMs = metrics.displayMs
            return page to metrics
        }
        checkPriority()
        val legacy = if (store.acceptsLegacy) store.find(key) else null
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
            val scale = tiles.scale
            val upscaler = SuperResolutionUpscaler(render)
            val missing = (0 until tiles.count).filterNot(tiles::complete)
            metrics.reusedTiles = tiles.count - missing.size
            reportProgress(metrics.reusedTiles * 100 / tiles.count)
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
                                    // A 2x pass also limits its input region to 32768px per side.
                                    val maxWidth = minOf(65536L, BAND_BYTES / region.height() / 4).toInt() / scale * scale
                                    // Stop at saved tiles and whole-tile boundaries. Filling the entire
                                    // byte budget would infer saved pixels and an unused strip tail.
                                    var previous = index
                                    for (nextPosition in position + 1 until missing.size) {
                                        val next = missing[nextPosition]
                                        if (next != previous + 1 || next / tiles.columns != index / tiles.columns) break
                                        val nextRegion = tiles.region(next)
                                        if (nextRegion.right - region.left > maxWidth) break
                                        bandRegion.right = nextRegion.right
                                        previous = next
                                    }
                                }
                                band = upscaler.upscale(input, Rect(bandRegion.left / scale, bandRegion.top / scale,
                                    bandRegion.right / scale, bandRegion.bottom / scale), scale) { done, total ->
                                    reportProgress(((metrics.reusedTiles + position + done.toDouble() / total.coerceAtLeast(1)) * 100 / tiles.count).toInt())
                                }
                                metrics.maxOutputBitmapBytes = maxOf(metrics.maxOutputBitmapBytes, checkNotNull(band).allocationByteCount)
                            }
                            if (bandRegion == region) {
                                // Transfer ownership to the encoder/cache instead of copying a single tile.
                                checkNotNull(band).also { band = null }
                            } else Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888).also { tile ->
                                val canvas = Canvas(tile)
                                try {
                                    canvas.drawBitmap(checkNotNull(band), Rect(region.left - bandRegion.left, region.top - bandRegion.top,
                                        region.right - bandRegion.left, region.bottom - bandRegion.top), Rect(0, 0, tile.width, tile.height), null)
                                } finally {
                                    // Native Canvas retains the pixel buffer even after Bitmap.recycle().
                                    // Detach it now instead of retaining an entire page's tiles until GC.
                                    canvas.setBitmap(null)
                                }
                            }
                        }
                        metrics.inferenceMs += SystemClock.elapsedRealtime() - inferenceStart
                        metrics.computedTiles++
                        metrics.maxOutputBitmapBytes = maxOf(metrics.maxOutputBitmapBytes, bitmap.allocationByteCount)
                        val task = Encoding(index, tiles.remember(index, bitmap))
                        try {
                            if (position == missing.lastIndex) {
                                currentCoroutineContext().ensureActive(); checkPriority()
                                // Publish shared pixels before the last checkpoint or final-image encoding.
                                displayed = ready(tiles)
                                metrics.displayMs = SystemClock.elapsedRealtime() - start
                            }
                            encoding.send(task)
                            reportProgress((metrics.reusedTiles + position + 1) * 100 / tiles.count)
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
