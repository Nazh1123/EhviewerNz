package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cascades the native 2x model over bounded regions, retaining context between passes. */
internal class SuperResolutionUpscaler(
    private val render: suspend (Bitmap, Rect, (Int, Int) -> Unit) -> Bitmap,
) {
    suspend fun upscale(source: Bitmap, region: Rect, scale: Int, progress: (Int, Int) -> Unit): Bitmap {
        require(scale in listOf(2, 4, 8))
        val passes = Integer.numberOfTrailingZeros(scale)
        var completed = 0
        val callback: (Int, Int) -> Unit = { done, total ->
            progress(completed * 100 + done * 100 / total.coerceAtLeast(1), passes * 100)
        }
        suspend fun pass(input: Bitmap, crop: Rect): Bitmap {
            currentCoroutineContext().ensureActive()
            return render(input, crop, callback).also { completed++ }
        }
        suspend fun cascade(crop: Rect, factor: Int): Bitmap {
            if (factor == 2) return pass(source, crop)
            val half = factor / 2
            // The 2x network reads up to eight input pixels beyond its requested region.
            // Include that context from the preceding pass to avoid seams at tile edges.
            val padding = (8 + half - 1) / half
            val context = Rect(maxOf(0, crop.left - padding), maxOf(0, crop.top - padding),
                minOf(source.width, crop.right + padding), minOf(source.height, crop.bottom + padding))
            val intermediate = cascade(context, half)
            return try {
                pass(intermediate, Rect((crop.left - context.left) * half, (crop.top - context.top) * half,
                    (crop.right - context.left) * half, (crop.bottom - context.top) * half))
            } finally { intermediate.recycle() }
        }
        return cascade(region, scale)
    }
}
