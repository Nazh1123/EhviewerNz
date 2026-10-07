package com.hippo.ehviewer.translation.engine

import android.graphics.*
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlinx.coroutines.*
import kotlin.math.*

/** Official PP-OCRv5 recognition graphs: BGR NCHW [-1,1], zero right padding, greedy CTC. */
class PpOcr(path: String, private val alphabet: List<String>, private val cfg: OcrConfig = OcrConfig()) : PageOcr {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use {
        it.setIntraOpNumThreads(2); it.setInterOpNumThreads(1)
        it.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        environment.createSession(path, it)
    }
    private val inputName = session.inputNames.single()
    private var closed = false

    override suspend fun recognize(page: Bitmap, lines: List<TextLine>) = withContext(Dispatchers.Default) {
        for (line in lines) {
            ensureActive()
            recognizeLine(page, line)
        }
    }

    @Synchronized private fun recognizeLine(page: Bitmap, line: TextLine) {
        check(!closed) { "PP-OCR is closed" }
        val box = Geometry.bounds(line.quad)?.let(Geometry::canonical) ?: return
        val vertical = box.height > box.width
        line.direction = if (vertical) "v" else "h"
        val length = if (vertical) box.height else box.width
        val thickness = if (vertical) box.width else box.height
        val contentWidth = ceil(48 * (length + 2 * cfg.stripPad) / (thickness + 2 * cfg.stripPad)).toInt().coerceIn(2, 2048)
        val width = max(320, (contentWidth + 31) / 32 * 32)
        val corners = box.corners(cfg.stripPad.toFloat())
        val ordered = if (vertical) listOf(corners[1], corners[2], corners[3], corners[0]) else corners
        val transform = Matrix()
        if (!transform.setPolyToPoly(ordered.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0,
                floatArrayOf(0f, 0f, contentWidth.toFloat(), 0f, contentWidth.toFloat(), 48f, 0f, 48f), 0, 4)) return
        val crop = Bitmap.createBitmap(contentWidth, 48, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(contentWidth * 48)
        try {
            crop.eraseColor(Color.WHITE)
            Canvas(crop).drawBitmap(page, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            crop.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, 48)
        } finally { crop.recycle() }
        val input = FloatArray(width * 48 * 3)
        for (y in 0 until 48) for (x in 0 until contentWidth) {
            val pixel = pixels[y * contentWidth + x]
            for (channel in 0..2) input[channel * width * 48 + y * width + x] =
                ((pixel ushr (channel * 8)) and 255) / 127.5f - 1f
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 48, width.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                check(shape.size == 3 && shape[0] == 1L && shape[2] == alphabet.size.toLong()) { "PP-OCR output/dictionary mismatch" }
                val decoded = decode(output.floatBuffer, shape[1].toInt(), alphabet)
                line.text = decoded.first.takeIf { decoded.second >= cfg.minProb }.orEmpty()
            }
        }
    }

    @Synchronized override fun close() { if (!closed) { closed = true; session.close() } }

    companion object {
        fun decode(probabilities: FloatBuffer, steps: Int, alphabet: List<String>): Pair<String, Float> {
            require(steps >= 0 && alphabet.isNotEmpty() && probabilities.remaining().toLong() == steps.toLong() * alphabet.size)
            val base = probabilities.position()
            var previous = -1; var count = 0; var sum = 0f
            val text = buildString {
                for (time in 0 until steps) {
                    var id = 0; var score = Float.NEGATIVE_INFINITY
                    for (character in alphabet.indices) {
                        val value = probabilities[base + time * alphabet.size + character]
                        if (value > score) { id = character; score = value }
                    }
                    if (id != 0 && id != previous && score.isFinite()) { append(alphabet[id]); sum += score; count++ }
                    previous = id
                }
            }
            return text to if (count == 0) 0f else sum / count
        }
    }
}
