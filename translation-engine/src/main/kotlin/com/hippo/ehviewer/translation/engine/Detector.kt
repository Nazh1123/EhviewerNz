package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import kotlin.math.*

class Detector(modelPath: String, private val cfg: DetectorConfig = DetectorConfig()) : AutoCloseable {
    private val net = ImageModel(modelPath, 4, true)

    fun detect(page: Bitmap): Detection = detect(page) {}

    fun detect(page: Bitmap, checkRelevant: () -> Unit): Detection = DetectionTiles.detect(page, cfg, ::detectWhole, checkRelevant)

    private fun detectWhole(page: Bitmap): Detection {
        val scale = cfg.inputSize.toFloat() / max(page.width, page.height)
        val width = max(1, (page.width * scale).roundToInt())
        val height = max(1, (page.height * scale).roundToInt())
        val gridW = (width + 255) / 256 * 256
        val gridH = (height + 255) / 256 * 256
        val input = FloatArray(gridW * gridH * 3) { -1f }
        val resized = Bitmap.createScaledBitmap(page, width, height, true)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                resized.getPixels(row, 0, width, 0, y, width, 1)
                for (x in row.indices) for (channel in 0..2) {
                    input[channel * gridW * gridH + y * gridW + x] =
                        ((row[x] ushr (16 - channel * 8)) and 255) / 127.5f - 1f
                }
            }
        } finally { if (resized !== page) resized.recycle() }
        // Only DBNet's first output channel is needed for connected components.
        val logits = FloatArray(gridW * gridH)
        val strokes = FloatArray(logits.size)
        val dimensions = net.detect(input, gridW, gridH, logits, strokes)
        val lines = components(logits, gridW, gridH, scale, page.width, page.height, cfg)
        val maskW = dimensions[0]; val maskH = dimensions[1]
        val mask = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(page.width)
            val ratioX = width.toFloat() / page.width * maskW / gridW
            val ratioY = height.toFloat() / page.height * maskH / gridH
            for (y in 0 until page.height) {
                val sy = ((y + 0.5f) * ratioY - 0.5f).coerceIn(0f, maskH - 1f)
                val y0 = sy.toInt(); val y1 = min(maskH - 1, y0 + 1); val fy = sy - y0
                for (x in row.indices) {
                    val sx = ((x + 0.5f) * ratioX - 0.5f).coerceIn(0f, maskW - 1f)
                    val x0 = sx.toInt(); val x1 = min(maskW - 1, x0 + 1); val fx = sx - x0
                    val upper = strokes[y0 * maskW + x0] * (1 - fx) + strokes[y0 * maskW + x1] * fx
                    val lower = strokes[y1 * maskW + x0] * (1 - fx) + strokes[y1 * maskW + x1] * fx
                    row[x] = if (upper * (1 - fy) + lower * fy > cfg.strokeThreshold) -1 else -16777216
                }
                mask.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            return Detection(lines, mask)
        } catch (error: Throwable) { mask.recycle(); throw error }
    }

    fun warmUp() = net.warmDetect()
    override fun close() = net.close()

    companion object {
        internal fun components(logits: FloatArray, width: Int, height: Int, scale: Float,
                                pageW: Int, pageH: Int, cfg: DetectorConfig = DetectorConfig()): List<TextLine> {
            require(width > 0 && height > 0 && logits.size == width * height && scale > 0)
            val cutoff = ln(cfg.textThreshold / (1 - cfg.textThreshold))
            val state = ByteArray(logits.size) { if (logits[it] > cutoff) 1 else 0 }
            val pending = IntArray(logits.size)
            val result = ArrayList<TextLine>()
            for (seed in state.indices) {
                if (state[seed].toInt() != 1) continue
                var head = 0; var tail = 1; pending[0] = seed; state[seed] = 2
                var probability = 0.0
                val outline = ArrayList<Pt>()
                while (head < tail) {
                    val index = pending[head++]; val x = index % width; val y = index / width
                    probability += 1.0 / (1.0 + exp(-logits[index].toDouble()))
                    var boundary = false
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) { boundary = true; continue }
                        val neighbor = ny * width + nx
                        when (state[neighbor].toInt()) {
                            0 -> if (dx == 0 || dy == 0) boundary = true
                            1 -> { state[neighbor] = 2; pending[tail++] = neighbor }
                        }
                    }
                    if (boundary) outline.add(Pt(x.toFloat(), y.toFloat()))
                }
                val score = (probability / tail).toFloat()
                if (score < cfg.boxThreshold) continue
                val box = Geometry.bounds(outline)?.let(Geometry::canonical) ?: continue
                if (min(box.width, box.height) < 3) continue
                val padding = box.width * box.height * cfg.expansion / (2 * (box.width + box.height))
                val quad = box.corners(padding).map { Pt((it.x / scale).coerceIn(0f, pageW - 1f),
                    (it.y / scale).coerceIn(0f, pageH - 1f)) }
                result.add(TextLine(quad, score).apply { direction = if (box.height > box.width) "v" else "h" })
            }
            return result
        }

    }
}
