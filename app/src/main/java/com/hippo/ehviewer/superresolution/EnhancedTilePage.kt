package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicIntegerArray

/** Immutable geometry, atomically written PNG tiles, bounded shared CPU cache. No full output Bitmap. */
class EnhancedTilePage internal constructor(val directory: File, val sourceWidth: Int, val sourceHeight: Int) {
    val width = sourceWidth * 2
    val height = sourceHeight * 2
    val columns = (sourceWidth + EDGE - 1) / EDGE
    val rows = (sourceHeight + EDGE - 1) / EDGE
    val count = columns * rows
    private val cache = LinkedHashMap<String, Pixels>(16, .75f, true)
    private var bytes = 0L
    private var caching = true
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
        val x = index % columns * EDGE * 2
        val y = index / columns * EDGE * 2
        return Rect(x, y, minOf(width, x + EDGE * 2), minOf(height, y + EDGE * 2))
    }
    /** Two output-pixel gutters allow bilinear filtering across tile boundaries. */
    fun region(index: Int): Rect = core(index).apply {
        left = maxOf(0, left - 2); top = maxOf(0, top - 2)
        right = minOf(width, right + 2); bottom = minOf(height, bottom + 2)
    }
    fun file(index: Int) = File(directory, "$index.png")
    fun complete(index: Int): Boolean {
        val file = file(index)
        if (!SuperResolutionStore.completePng(file)) return false
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
    /** IO only. The caller owns one reference, including when the cache evicts the tile. */
    fun acquire(index: Int, sample: Int): Pixels? {
        synchronized(this) { cache["$index:$sample"]?.let { return it.retain() } }
        val bitmap = BitmapFactory.decodeFile(file(index).path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample
        }) ?: return null
        return Pixels(bitmap).also { cache("$index:$sample", it) }
    }
    @Synchronized fun activate() { caching = true }
    @Synchronized fun trim() { caching = false; cache.values.forEach { it.close() }; cache.clear(); bytes = 0 }
    @Synchronized fun cachedBytes() = bytes
    companion object {
        // Guttered regions fit into two 128-pixel inference blocks and a 512-pixel GL texture.
        const val EDGE = 252
        const val CPU_CACHE_BYTES = 8L * 1024 * 1024
    }
}
