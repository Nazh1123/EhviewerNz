package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

class Ocr(modelPath: String, private val alphabet: List<String>, private val cfg: OcrConfig = OcrConfig()) : PageOcr {
    private val base = modelPath.replace("_mixed.ncnn.param", ".ncnn.param")
    private val mixed = base.removeSuffix(".ncnn.param") + "_mixed.ncnn.param"
    private val useMixed = File(mixed).isFile && ImageInference.supportsFp16()
    private val net = ImageModel(if (useMixed) mixed else base, 1, useMixed, base.removeSuffix(".param") + ".bin")
    // The same permits apply across pages; page concurrency cannot multiply OCR CPU usage.
    private val permits = Semaphore(if (cfg.concurrent) cfg.concurrency.coerceIn(1, 4) else 1)

    override suspend fun recognize(page: Bitmap, lines: List<TextLine>) = coroutineScope {
        val next = AtomicInteger()
        repeat(min(lines.size, if (cfg.concurrent) cfg.concurrency.coerceIn(1, 4) else 1)) {
            launch(Dispatchers.Default) {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= lines.size) break
                    permits.withPermit { recognizeLine(page, lines[index]) }
                }
            }
        }
    }

    private fun recognizeLine(page: Bitmap, line: TextLine) {
        val box = Geometry.bounds(line.quad)?.let(Geometry::canonical) ?: return
        val vertical = box.height > box.width
        line.direction = if (vertical) "v" else "h"
        val quad = box.corners(cfg.stripPad.toFloat())
        val length = if (vertical) box.height else box.width
        val thickness = if (vertical) box.width else box.height
        val stripWidth = (48 * (length + 2 * cfg.stripPad) / (thickness + 2 * cfg.stripPad)).roundToInt().coerceAtLeast(2)
        val inputWidth = stripWidth + 16
        if (inputWidth / 4 - 1 !in 1..2048) return
        val ordered = if (vertical) listOf(quad[1], quad[2], quad[3], quad[0]) else quad
        val transform = Matrix()
        if (!transform.setPolyToPoly(floatArrayOf(0f, 0f, stripWidth - 1f, 0f, stripWidth - 1f, 47f, 0f, 47f), 0,
                ordered.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0, 4)) return
        val values = FloatArray(9); transform.getValues(values)
        val cropLeft = floor(quad.minOf(Pt::x) - 2).toInt().coerceIn(0, page.width - 1)
        val cropTop = floor(quad.minOf(Pt::y) - 2).toInt().coerceIn(0, page.height - 1)
        val cropRight = ceil(quad.maxOf(Pt::x) + 3).toInt().coerceIn(cropLeft + 1, page.width)
        val cropBottom = ceil(quad.maxOf(Pt::y) + 3).toInt().coerceIn(cropTop + 1, page.height)
        val cropW = cropRight - cropLeft; val cropH = cropBottom - cropTop
        val pixels = IntArray(cropW * cropH)
        page.getPixels(pixels, 0, cropW, cropLeft, cropTop, cropW, cropH)
        val samples = Array(3) { FloatArray(stripWidth * 48) }
        val wx = FloatArray(4); val wy = FloatArray(4)
        for (y in 0 until 48) for (x in 0 until stripWidth) {
            val denominator = values[6] * x + values[7] * y + values[8]
            val sx = (values[0] * x + values[1] * y + values[2]) / denominator - cropLeft
            val sy = (values[3] * x + values[4] * y + values[5]) / denominator - cropTop
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt()
            for (tap in 0..3) { wx[tap] = cubic(sx - ix - tap + 1); wy[tap] = cubic(sy - iy - tap + 1) }
            for (channel in 0..2) {
                var value = 0f
                for (dy in 0..3) {
                    var row = 0f
                    for (dx in 0..3) {
                        val pixel = pixels[(iy + dy - 1).coerceIn(0, cropH - 1) * cropW + (ix + dx - 1).coerceIn(0, cropW - 1)]
                        row += wx[dx] * ((pixel ushr (16 - channel * 8)) and 255)
                    }
                    value += wy[dy] * row
                }
                samples[channel][y * stripWidth + x] = value.roundToInt().coerceIn(0, 255).toFloat()
            }
        }
        val input = FloatArray(inputWidth * 48 * 3) { 1f }
        val blurred = FloatArray(stripWidth * 48)
        val kernel = FloatArray(5) { tap -> exp(-((tap - 2) * (tap - 2)) / (2f * 1.2f * 1.2f)) }
        val weight = kernel.sum()
        for (tap in kernel.indices) kernel[tap] /= weight
        for (channel in 0..2) {
            val source = samples[channel]
            for (y in 0 until 48) for (x in 0 until stripWidth) {
                var blur = 0f
                for (tap in -2..2) blur += kernel[tap + 2] * source[y * stripWidth + (x + tap).coerceIn(0, stripWidth - 1)]
                blurred[y * stripWidth + x] = blur
            }
            for (y in 0 until 48) for (x in 0 until stripWidth) {
                var blur = 0f
                for (tap in -2..2) blur += kernel[tap + 2] * blurred[(y + tap).coerceIn(0, 47) * stripWidth + x]
                val original = source[y * stripWidth + x]
                input[channel * inputWidth * 48 + y * inputWidth + x] =
                    (original + 1.6f * (original - blur)).coerceIn(0f, 255f).toInt() / 127.5f - 1f
            }
        }
        val steps = inputWidth / 4 - 1
        val ids = IntArray(steps); val logProb = FloatArray(steps)
        net.recognize(input, inputWidth, ids, logProb)
        val decoded = decode(ids, logProb, alphabet)
        line.text = if (decoded.second >= cfg.minProb) decoded.first else ""
    }

    override fun warmUp() = net.warmOcr()
    override fun close() = net.close()

    companion object {
        private fun cubic(distance: Float): Float {
            val x = abs(distance)
            return when {
                x <= 1 -> ((1.25f * x - 2.25f) * x) * x + 1
                x < 2 -> ((-0.75f * x + 3.75f) * x - 6) * x + 3
                else -> 0f
            }
        }
        internal fun decode(ids: IntArray, logProb: FloatArray, alphabet: List<String>): Pair<String, Float> {
            var previous = -1; var count = 0; var confidence = 0.0
            val text = buildString {
                for (i in ids.indices) {
                    val id = ids[i]
                    if (id != 0 && id != previous && id in alphabet.indices) {
                        append(if (alphabet[id] == "<SP>") " " else alphabet[id])
                        confidence += logProb[i]; count++
                    }
                    previous = id
                }
            }
            return text to if (count == 0) 0f else exp(confidence / count).toFloat()
        }
    }
}
