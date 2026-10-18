package com.hippo.ehviewer.superresolution

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Assemble only tile cores. Even band boundaries preserve WebP's 4:2:0 chroma alignment. */
internal object SuperResolutionBands {
    const val MAX_BYTES = 8 * 1024 * 1024

    suspend fun read(page: EnhancedTilePage, consume: (IntArray, Int) -> Unit) {
        val bandHeight = minOf(page.outputEdge, (MAX_BYTES / 4 / page.width / 2 * 2).coerceAtLeast(2))
        val pixels = IntArray(page.width * bandHeight)
        for (tileRow in 0 until page.rows) {
            val top = tileRow * page.outputEdge
            val bottom = minOf(page.height, top + page.outputEdge)
            var y = top
            while (y < bottom) {
                currentCoroutineContext().ensureActive()
                val height = minOf(bandHeight, bottom - y)
                for (column in 0 until page.columns) {
                    currentCoroutineContext().ensureActive()
                    val index = tileRow * page.columns + column
                    val core = page.core(index)
                    val region = page.region(index)
                    val tile = checkNotNull(page.acquire(index, 1)) { "Missing SR tile" }
                    try {
                        check(tile.bitmap.width == region.width() && tile.bitmap.height == region.height())
                        tile.bitmap.getPixels(pixels, core.left, page.width, core.left - region.left,
                            y - region.top, core.width(), height)
                    } finally { tile.close() }
                }
                consume(pixels, height)
                y += height
            }
        }
    }
}
