package com.hippo.ehviewer.superresolution

import android.app.ActivityManager
import android.content.Context
import com.hippo.ehviewer.image.WebpEncoder
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Account for native image planes, encoder workspace, bitstream and bounded input bands. */
internal object SuperResolutionWebp {
    const val MAX_WORKSPACE = 256L * 1024 * 1024
    // VP8L also keeps ARGB transforms, hash chains and backward references.
    fun estimatedBytes(width: Int, height: Int, lossless: Boolean = false) =
        width.toLong() * height * (if (lossless) 48 else 12) + 32L * 1024 * 1024

    fun budget(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0
        manager.getMemoryInfo(info)
        if (info.lowMemory) return 0
        val runtime = Runtime.getRuntime()
        val headroom = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()).coerceAtLeast(0)
        // Only the fixed input band lives on the Java heap. Native planes must be admitted
        // against system headroom, otherwise common 2x pages needlessly fall back on small heaps.
        if (headroom < 24L * 1024 * 1024) return 0
        return minOf(MAX_WORKSPACE, (info.availMem - info.threshold).coerceAtLeast(0) / 3)
    }

    fun accepts(width: Int, height: Int, budget: Long, lossless: Boolean = false): Boolean =
        width in 1..WebpEncoder.MAX_DIMENSION && height in 1..WebpEncoder.MAX_DIMENSION &&
            estimatedBytes(width, height, lossless) <= budget.coerceAtMost(MAX_WORKSPACE)

    suspend fun write(page: EnhancedTilePage, output: OutputStream, quality: Int? = 95) {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val handle = if (quality == null) WebpEncoder.createLossless(page.width, page.height)
            else WebpEncoder.create(page.width, page.height)
        check(handle != 0L) { "Cannot allocate WebP workspace" }
        try {
            SuperResolutionBands.read(page) { pixels, rows ->
                check(WebpEncoder.append(handle, pixels, rows)) { "Cannot prepare WebP band" }
            }
            // In lossless mode 75 controls encoding effort, with no near-lossless preprocessing.
            check(WebpEncoder.finish(handle, output, Runnable { coroutine.ensureActive() }, quality ?: 75)) { "Cannot encode SR WebP" }
        } finally { WebpEncoder.release(handle) }
    }
}
