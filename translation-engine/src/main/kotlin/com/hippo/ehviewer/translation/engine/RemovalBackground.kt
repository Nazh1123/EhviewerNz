package com.hippo.ehviewer.translation.engine

import kotlin.math.*

/** Ignore the dilated glyph mask, but reject texture, gradients and insufficient evidence. */
internal object RemovalBackground {
    data class Sample(val color: Int, val flat: Boolean)

    fun sample(pixels: IntArray, mask: ByteArray, width: Int, box: IntArray,
               include: (Int, Int) -> Boolean = { _, _ -> true }): Sample {
        val histogram = IntArray(4096)
        val step = max(1, sqrt(((box[2] - box[0]) * (box[3] - box[1])).toDouble() / 4096).toInt())
        fun visit(block: (Int) -> Unit) {
            for (y in box[1] until box[3] step step) for (x in box[0] until box[2] step step) {
                val index = y * width + x
                if (mask[index].toInt() == 0 && include(x, y)) block(pixels[index])
            }
        }
        fun bucket(color: Int) = ((color ushr 12) and 0xF00) or ((color ushr 8) and 0xF0) or ((color ushr 4) and 0xF)
        visit { histogram[bucket(it)]++ }
        val dominant = histogram.indices.maxBy { histogram[it] }
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        visit { color ->
            if (bucket(color) == dominant) {
                r += (color ushr 16) and 255; g += (color ushr 8) and 255; b += color and 255; count++
            }
        }
        if (count == 0) return Sample(-1, false)
        val red = (r / count).toInt(); val green = (g / count).toInt(); val blue = (b / count).toInt()
        val color = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        var total = 0; var matching = 0
        visit {
            total++
            if (abs(((it ushr 16) and 255) - red) <= 8 &&
                abs(((it ushr 8) and 255) - green) <= 8 && abs((it and 255) - blue) <= 8) matching++
        }
        return Sample(color, total >= 32 && matching.toDouble() / total >= .995)
    }
}
