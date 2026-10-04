package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

class Inpainter(private val modelPath: String, private val cfg: InpainterConfig = InpainterConfig()) : AutoCloseable {
    private var model: ImageModel? = null
    @Volatile private var closed = false
    init { require(cfg.method in setOf("boxfill", "aot")) }
    @Synchronized private fun model(): ImageModel {
        check(!closed) { "Text removal is closed" }
        return model ?: ImageModel(modelPath, 4, true).also { model = it }
    }

    suspend fun inpaint(page: Bitmap, regions: List<TextRegion>, textMask: Bitmap): Bitmap {
        check(!closed)
        val width = page.width; val height = page.height
        require(textMask.width == width && textMask.height == height)
        val pixels = IntArray(width * height)
        page.getPixels(pixels, 0, width, 0, 0, width, height)
        val seed = ByteArray(pixels.size)
        val row = IntArray(width)
        val bounds = regions.map { bounds(it, width, height) }
        for (y in 0 until height) {
            textMask.getPixels(row, 0, width, 0, y, width, 1)
            for (box in bounds) if (y in box[1] until box[3]) {
                for (x in box[0] until box[2]) if ((row[x] and 255) > 127) seed[y * width + x] = 1
            }
        }
        val mask = RemovalMask.dilate(seed, width, height, cfg.maskRadius)
        if (mask.none { it.toInt() != 0 }) return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (cfg.method == "boxfill") {
            for ((index, region) in regions.withIndex()) {
                val box = bounds[index]
                val color = backgroundColor(pixels, seed, width, box)
                region.onArt = false
                val left = max(0, box[0] - cfg.maskRadius); val right = min(width, box[2] + cfg.maskRadius)
                val top = max(0, box[1] - cfg.maskRadius); val bottom = min(height, box[3] + cfg.maskRadius)
                for (y in top until bottom) for (x in left until right)
                    if (mask[y * width + x].toInt() != 0) pixels[y * width + x] = color
            }
        } else {
            val side = cfg.tileSize
            val resized = Bitmap.createScaledBitmap(page, side, side, true)
            val small = IntArray(side * side)
            try { resized.getPixels(small, 0, side, 0, 0, side, side) }
            finally { if (resized !== page) resized.recycle() }
            val holes = FloatArray(small.size)
            val input = FloatArray(small.size * 3)
            for (y in 0 until side) for (x in 0 until side) {
                val i = y * side + x
                val sx = (x.toLong() * width / side).toInt(); val sy = (y.toLong() * height / side).toInt()
                if (mask[sy * width + sx].toInt() != 0) holes[i] = 1f
                else for (channel in 0..2) input[channel * small.size + i] =
                    ((small[i] ushr (16 - channel * 8)) and 255) / 127.5f - 1f
            }
            val restored = FloatArray(input.size)
            model().inpaint(input, holes, side, restored)
            for (i in small.indices) {
                small[i] = Color.rgb(((restored[i] + 1) * 127.5f).toInt().coerceIn(0, 255),
                    ((restored[small.size + i] + 1) * 127.5f).toInt().coerceIn(0, 255),
                    ((restored[2 * small.size + i] + 1) * 127.5f).toInt().coerceIn(0, 255))
            }
            val resultSmall = Bitmap.createBitmap(small, side, side, Bitmap.Config.ARGB_8888)
            val restoredPage = Bitmap.createScaledBitmap(resultSmall, width, height, true)
            try {
                for (y in 0 until height) {
                    restoredPage.getPixels(row, 0, width, 0, y, width, 1)
                    for (x in row.indices) if (mask[y * width + x].toInt() != 0) pixels[y * width + x] = row[x]
                }
            } finally { if (restoredPage !== resultSmall) restoredPage.recycle(); resultSmall.recycle() }
            regions.forEach { it.onArt = true }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun bounds(region: TextRegion, width: Int, height: Int) = intArrayOf(
        floor(region.x0 - cfg.regionPad).toInt().coerceIn(0, width),
        floor(region.y0 - cfg.regionPad).toInt().coerceIn(0, height),
        ceil(region.x1 + cfg.regionPad).toInt().coerceIn(0, width),
        ceil(region.y1 + cfg.regionPad).toInt().coerceIn(0, height))

    private fun backgroundColor(pixels: IntArray, letters: ByteArray, width: Int, box: IntArray): Int {
        // Quantized mode rejects a few foreground strokes without requiring a second image mask.
        val histogram = IntArray(4096)
        for (y in box[1] until box[3] step 2) for (x in box[0] until box[2] step 2) {
            val index = y * width + x
            if (letters[index].toInt() != 0) continue
            val color = pixels[index]
            val bucket = ((color ushr 12) and 0xF00) or ((color ushr 8) and 0xF0) or ((color ushr 4) and 0xF)
            histogram[bucket]++
        }
        val bucket = histogram.indices.maxByOrNull { histogram[it] } ?: 4095
        if (histogram[bucket] == 0) return Color.WHITE
        return Color.rgb(((bucket ushr 8) and 15) * 17, ((bucket ushr 4) and 15) * 17, (bucket and 15) * 17)
    }

    fun warmUp() {
        if (cfg.method == "aot") model().inpaint(FloatArray(64 * 64 * 3), FloatArray(64 * 64), 64, FloatArray(64 * 64 * 3))
    }
    @Synchronized override fun close() { closed = true; model?.close(); model = null }
}
