package com.hippo.lib.glview.image

import android.graphics.RectF
import android.os.Process
import com.hippo.ehviewer.superresolution.EnhancedTilePage
import com.hippo.lib.glview.glrenderer.BitmapTexture
import com.hippo.lib.glview.glrenderer.GLCanvas
import com.hippo.lib.glview.glrenderer.Texture
import com.hippo.lib.glview.view.GLRoot
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Region PNG decoding stays off GL; only visible tiles at the required mip level are uploaded. */
class EnhancedPageTexture(private val page: EnhancedTilePage, private val root: GLRoot?) : Texture {
    private class Tile(val pixels: EnhancedTilePage.Pixels) {
        val texture = BitmapTexture(pixels.bitmap)
        val bytes = pixels.bitmap.allocationByteCount.toLong()
        fun close() { texture.recycle(); pixels.close() }
    }
    private val textures = LinkedHashMap<String, Tile>(16, .75f, true)
    private val ready = LinkedHashMap<String, EnhancedTilePage.Pixels>()
    private val pending = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private var desired = emptySet<String>()
    @Volatile private var closed = false
    private var bytes = 0L
    var callback: Runnable? = null
    private val saved: (Int) -> Unit = { index ->
        val redraw = synchronized(this) {
            failed.removeAll { it.substringBefore(':').toInt() == index }
            !closed && desired.any { it.substringBefore(':').toInt() == index }
        }
        if (redraw) invalidate()
    }
    init { page.subscribe(saved) }
    private fun invalidate() {
        root?.lockRenderThread()
        try { if (!closed) callback?.run() } finally { root?.unlockRenderThread() }
        if (!closed) root?.requestRenderForced()
    }
    override fun getWidth() = page.width
    override fun getHeight() = page.height
    override fun isOpaque() = false // Unloaded tiles reveal the original, never a black rectangle.
    override fun draw(canvas: GLCanvas, x: Int, y: Int) = draw(canvas, x, y, width, height)
    override fun draw(canvas: GLCanvas, x: Int, y: Int, w: Int, h: Int) =
        draw(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()), RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()))

    @Synchronized override fun draw(canvas: GLCanvas, source: RectF, target: RectF) {
        if (closed || source.isEmpty || target.isEmpty) return
        var sample = 1
        val ratio = minOf(source.width() / target.width(), source.height() / target.height())
        while (sample * 2 <= ratio) sample *= 2
        val edge = page.outputEdge
        val x0 = (source.left / edge).toInt().coerceIn(0, page.columns - 1)
        val x1 = ((source.right - .01f) / edge).toInt().coerceIn(0, page.columns - 1)
        val y0 = (source.top / edge).toInt().coerceIn(0, page.rows - 1)
        val y1 = ((source.bottom - .01f) / edge).toInt().coerceIn(0, page.rows - 1)
        val count = (x1 - x0 + 1L) * (y1 - y0 + 1)
        while (count * (edge + page.scale * 2) * (edge + page.scale * 2) * 4 / sample / sample > GL_CACHE_BYTES) sample *= 2
        desired = (y0..y1).flatMap { y -> (x0..x1).map { x -> "${y * page.columns + x}:$sample" } }.toSet()
        val unused = textures.entries.iterator()
        while (unused.hasNext()) {
            val entry = unused.next()
            if (entry.key !in desired) { bytes -= entry.value.bytes; entry.value.close(); unused.remove() }
        }
        val stale = ready.entries.iterator()
        while (stale.hasNext()) { val entry = stale.next(); if (entry.key !in desired) { entry.value.close(); stale.remove() } }
        var uploads = 0
        for (y in y0..y1) for (x in x0..x1) {
            val index = y * page.columns + x
            val key = "$index:$sample"
            var tile = textures[key]
            if (tile == null && uploads < 4) ready.remove(key)?.let {
                tile = Tile(it); textures[key] = tile!!; bytes += tile!!.bytes; uploads++
            }
            if (tile == null) { request(index, sample, key); continue }
            val core = RectF(page.core(index))
            if (!core.intersect(source)) continue
            val region = page.region(index)
            val bitmap = tile!!.pixels.bitmap
            val sx = bitmap.width.toFloat() / region.width()
            val sy = bitmap.height.toFloat() / region.height()
            val cropped = RectF((core.left - region.left) * sx, (core.top - region.top) * sy,
                (core.right - region.left) * sx, (core.bottom - region.top) * sy)
            val destination = RectF(target.left + (core.left - source.left) / source.width() * target.width(),
                target.top + (core.top - source.top) / source.height() * target.height(),
                target.left + (core.right - source.left) / source.width() * target.width(),
                target.top + (core.bottom - source.top) / source.height() * target.height())
            tile!!.texture.draw(canvas, cropped, destination)
        }
        if (ready.isNotEmpty()) root?.requestRenderForced()
    }

    private fun request(index: Int, sample: Int, key: String) {
        if (ready.containsKey(key) || key in pending || key in failed || pending.size >= 8) return
        pending.add(key)
        val version = page.version(index)
        try {
            loader.execute {
                val pixels = try { page.acquire(index, sample) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                synchronized(this) {
                    pending.remove(key)
                    if (!closed && key in desired && pixels != null) ready.put(key, pixels)?.close()
                    else pixels?.close()
                    // A failed file stays on the original image until a new atomic
                    // save arrives. Avoid an unbounded decode/render retry loop.
                    if (pixels == null && page.version(index) == version) failed.add(key)
                }
                if (pixels != null || page.version(index) != version) invalidate()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { pending.remove(key) }
    }
    @Synchronized fun recycle() {
        closed = true; callback = null
        page.unsubscribe(saved); failed.clear()
        textures.values.forEach { it.close() }; textures.clear(); bytes = 0
        ready.values.forEach { it.close() }; ready.clear(); desired = emptySet()
    }
    @Synchronized fun residentBytes() = bytes + ready.values.sumOf { it.bitmap.allocationByteCount.toLong() }
    companion object {
        const val GL_CACHE_BYTES = 24L * 1024 * 1024
        private val loader = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(32), { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "SR-visible-tiles").apply { isDaemon = true }
        })
    }
}
