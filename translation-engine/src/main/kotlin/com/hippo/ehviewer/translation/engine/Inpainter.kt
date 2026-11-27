package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.*

class Inpainter @JvmOverloads constructor(modelPath: String, private val cfg: InpainterConfig = InpainterConfig(),
                private val files: NcnnModelFiles = NcnnModelFiles.local(modelPath)) : AutoCloseable {
    private var model: ImageModel? = null
    @Volatile private var closed = false
    init { require(cfg.method in setOf("boxfill", "aot")) }
    @Synchronized private fun model(): ImageModel {
        check(!closed) { "Text removal is closed" }
        return model ?: ImageModel(files, 4, true).also { model = it }
    }

    suspend fun inpaint(page: Bitmap, regions: List<TextRegion>, textMask: Bitmap,
                        checkRelevant: () -> Unit = {}): Bitmap {
        check(!closed)
        val width = page.width; val height = page.height
        require(textMask.width == width && textMask.height == height)
        val pixels = IntArray(width * height)
        page.getPixels(pixels, 0, width, 0, 0, width, height)
        val row = IntArray(width)
        val bounds = regions.map { bounds(it, width, height) }
        val mask = removalMask(textMask, bounds, row)
        checkRelevant()
        if (mask == null) return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val backgrounds = bounds.map { RemovalBackground.sample(pixels, mask, width, it) }
        val complex = regions.indices.filter { cfg.method == "aot" && !backgrounds[it].flat }
        // Complex regions win at overlaps; a neighbouring flat bubble cannot erase artwork.
        val aiMask = if (complex.isEmpty()) null else ByteArray(mask.size)
        var hasAiPixels = false
        if (aiMask != null) for (index in complex) {
            val box = bounds[index]
            for (y in max(0, box[1] - cfg.maskRadius) until min(height, box[3] + cfg.maskRadius))
                for (x in max(0, box[0] - cfg.maskRadius) until min(width, box[2] + cfg.maskRadius)) {
                    val i = y * width + x
                    if (mask[i].toInt() != 0) {
                        aiMask[i] = 1
                        hasAiPixels = true
                    }
                }
        }
        regions.forEachIndexed { index, region -> region.onArt = index in complex }
        run {
            for ((index, region) in regions.withIndex()) {
                if (index in complex) continue
                val box = bounds[index]
                val color = backgrounds[index].color
                region.onArt = false
                val left = max(0, box[0] - cfg.maskRadius); val right = min(width, box[2] + cfg.maskRadius)
                val top = max(0, box[1] - cfg.maskRadius); val bottom = min(height, box[3] + cfg.maskRadius)
                for (y in top until bottom) for (x in left until right)
                    if (mask[y * width + x].toInt() != 0 && (aiMask == null || aiMask[y * width + x].toInt() == 0))
                        pixels[y * width + x] = color
            }
        }
        if (aiMask != null && hasAiPixels) {
            checkRelevant()
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
                if (aiMask[sy * width + sx].toInt() != 0) holes[i] = 1f
                else for (channel in 0..2) input[channel * small.size + i] =
                    ((small[i] ushr (16 - channel * 8)) and 255) / 127.5f - 1f
            }
            checkRelevant()
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
                    for (x in row.indices) if (aiMask[y * width + x].toInt() != 0) pixels[y * width + x] = row[x]
                }
            } finally { if (restoredPage !== resultSmall) restoredPage.recycle(); resultSmall.recycle() }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun removalMask(textMask: Bitmap, bounds: List<IntArray>, row: IntArray): ByteArray? {
        val area = Rect()
        for (box in bounds) area.union(box[0], box[1], box[2], box[3])
        if (area.isEmpty) return null
        // The seed is zero outside the boxes. Include the entire dilation halo in the crop.
        area.inset(-cfg.maskRadius, -cfg.maskRadius)
        check(area.intersect(0, 0, textMask.width, textMask.height))
        val width = area.width()
        val height = area.height()
        val seed = ByteArray(width * height)
        var hasText = false
        for (box in bounds) {
            val span = box[2] - box[0]
            if (span <= 0) continue
            for (y in box[1] until box[3]) {
                textMask.getPixels(row, 0, span, box[0], y, span, 1)
                val start = (y - area.top) * width + box[0] - area.left
                for (x in 0 until span) if ((row[x] and 255) > 127) {
                    seed[start + x] = 1
                    hasText = true
                }
            }
        }
        if (!hasText) return null
        val dilated = RemovalMask.dilate(seed, width, height, cfg.maskRadius)
        if (width == textMask.width && height == textMask.height) return dilated
        return ByteArray(textMask.width * textMask.height).also { mask ->
            for (y in 0 until height) {
                val start = y * width
                dilated.copyInto(mask, (area.top + y) * textMask.width + area.left, start, start + width)
            }
        }
    }

    private fun bounds(region: TextRegion, width: Int, height: Int) = intArrayOf(
        floor(region.x0 - cfg.regionPad).toInt().coerceIn(0, width),
        floor(region.y0 - cfg.regionPad).toInt().coerceIn(0, height),
        ceil(region.x1 + cfg.regionPad).toInt().coerceIn(0, width),
        ceil(region.y1 + cfg.regionPad).toInt().coerceIn(0, height))

    // First complex region loads AOT; flat-only pages never allocate its weights/workspace.
    fun warmUp() = Unit
    @Synchronized override fun close() { closed = true; model?.close(); model = null }
}
