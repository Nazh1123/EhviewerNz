package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.hippo.unifile.UniFile
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicIntegerArray

/** Immutable geometry, temporary lossless tiles and one final image; bounded shared pixel cache. */
class EnhancedTilePage internal constructor(val directory: File, val sourceWidth: Int, val sourceHeight: Int,
                                           val scale: Int = 2) {
    init { require(scale in listOf(2, 4, 8)) }
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    // Keep output tiles around 504px at every scale, with source-aligned gutters.
    val sourceEdge = minOf(EDGE * 2 / scale, 512 / scale - 2)
    val outputEdge = sourceEdge * scale
    val columns = (sourceWidth + sourceEdge - 1) / sourceEdge
    val rows = (sourceHeight + sourceEdge - 1) / sourceEdge
    val count = columns * rows
    private val cache = LinkedHashMap<String, Pixels>(16, .75f, true)
    private var bytes = 0L
    private var caching = true
    @Volatile internal var finalImage: UniFile? = null
        private set
    private var decoder: BitmapRegionDecoder? = null
    // Used on the UI thread: remote SAF availability is validated by the IO reader/store.
    internal val available get() = finalImage?.let { it.uri.scheme != "file" || it.isFile } ?: directory.isDirectory

    @Synchronized internal fun useFinalImage(image: UniFile) {
        decoder?.recycle(); decoder = null
        finalImage = image
    }

    /** Switch the backing source before deleting checkpoints, excluding concurrent region reads. */
    @Synchronized internal fun removeTileImages() {
        check(finalImage != null)
        directory.listFiles().orEmpty().filter { it.extension in listOf("png", "webp") &&
            it.nameWithoutExtension.toIntOrNull() != null }.forEach { check(it.delete()) }
    }
    private val versions = AtomicIntegerArray(count)
    private val listeners = CopyOnWriteArraySet<(Int) -> Unit>()
    internal fun version(index: Int) = versions.get(index)
    internal fun subscribe(listener: (Int) -> Unit) { listeners.add(listener) }
    internal fun unsubscribe(listener: (Int) -> Unit) { listeners.remove(listener) }
    internal fun saved(index: Int) { versions.incrementAndGet(index); listeners.forEach { it(index) } }

    /** Retained separately by encoding, CPU cache and a GL texture. */
    class Pixels(val bitmap: Bitmap) : AutoCloseable {
        private var references = 1
        @Synchronized fun retain(): Pixels { check(references > 0); references++; return this }
        @Synchronized override fun close() { check(references > 0); if (--references == 0) bitmap.recycle() }
    }
    fun core(index: Int): Rect {
        require(index in 0 until count)
        val x = index % columns * outputEdge
        val y = index / columns * outputEdge
        return Rect(x, y, minOf(width, x + outputEdge), minOf(height, y + outputEdge))
    }
    /** One source-pixel gutter keeps crops aligned at every scale and covers bilinear filtering. */
    fun region(index: Int): Rect = core(index).apply {
        left = maxOf(0, left - scale); top = maxOf(0, top - scale)
        right = minOf(width, right + scale); bottom = minOf(height, bottom + scale)
    }
    fun file(index: Int): File = File(directory, "$index.webp").takeIf { it.isFile }
        ?: File(directory, "$index.png").takeIf { it.isFile } ?: File(directory, "$index.webp")
    fun complete(index: Int): Boolean {
        if (finalImage != null) return true // The store validated the complete image once when opening it.
        val file = file(index)
        if (!SuperResolutionStore.completeImage(file)) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val region = region(index)
        return bounds.outWidth == region.width() && bounds.outHeight == region.height()
    }
    @Synchronized private fun cache(key: String, pixels: Pixels) {
        if (!caching) return
        cache.put(key, pixels.retain())?.let { bytes -= it.bitmap.allocationByteCount; it.close() }
        bytes += pixels.bitmap.allocationByteCount
        val entries = cache.entries.iterator()
        while (bytes > CPU_CACHE_BYTES && entries.hasNext()) {
            val entry = entries.next(); bytes -= entry.value.bitmap.allocationByteCount
            entry.value.close(); entries.remove()
        }
    }
    fun remember(index: Int, bitmap: Bitmap): Pixels = Pixels(bitmap).also { cache("$index:1", it) }
    /** Warm just the first screen-scale region, without allocating a whole enhanced bitmap. */
    internal fun warmFirstRegion(viewWidth: Int, viewHeight: Int) {
        var sample = 1
        val ratio = minOf(width.toFloat() / viewWidth.coerceAtLeast(1), height.toFloat() / viewHeight.coerceAtLeast(1))
        while (sample * 2 <= ratio) sample *= 2
        acquire(0, sample)?.close()
    }
    /** IO only. The caller owns one reference, including when the cache evicts the tile. */
    fun acquire(index: Int, sample: Int): Pixels? {
        synchronized(this) { cache["$index:$sample"]?.let { return it.retain() } }
        val bitmap = synchronized(this) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample
            }
            val final = finalImage
            if (final == null) BitmapFactory.decodeFile(file(index).path, options)
            else {
                val source = decoder ?: final.openInputStream().use {
                    BitmapRegionDecoder.newInstance(it, false)
                }?.also { decoder = it } ?: return null
                source.decodeRegion(region(index), options)
            }
        } ?: return null
        return Pixels(bitmap).also { cache("$index:$sample", it) }
    }
    @Synchronized fun activate() { caching = true }
    @Synchronized fun trim() {
        caching = false; cache.values.forEach { it.close() }; cache.clear(); bytes = 0
        decoder?.recycle(); decoder = null
    }
    @Synchronized fun cachedBytes() = bytes
    companion object {
        // Guttered regions fit into two 128-pixel inference blocks and a 512-pixel GL texture.
        const val EDGE = 252
        const val CPU_CACHE_BYTES = 8L * 1024 * 1024
    }
}
