package com.hippo.ehviewer.translation.engine

/** Binary morphology with bounded work per pixel, independent of the brush radius. */
internal object RemovalMask {
    fun dilate(source: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        require(width > 0 && height > 0 && source.size == width * height && radius >= 0)
        if (radius == 0) return source.copyOf()
        val horizontal = ByteArray(source.size)
        val result = ByteArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0..minOf(radius, width - 1)) count += source[row + x].toInt()
            for (x in 0 until width) {
                horizontal[row + x] = if (count > 0) 1 else 0
                if (x - radius >= 0) count -= source[row + x - radius].toInt()
                if (x + radius + 1 < width) count += source[row + x + radius + 1].toInt()
            }
        }
        for (x in 0 until width) {
            var count = 0
            for (y in 0..minOf(radius, height - 1)) count += horizontal[y * width + x].toInt()
            for (y in 0 until height) {
                result[y * width + x] = if (count > 0) 1 else 0
                if (y - radius >= 0) count -= horizontal[(y - radius) * width + x].toInt()
                if (y + radius + 1 < height) count += horizontal[(y + radius + 1) * width + x].toInt()
            }
        }
        return result
    }
}
