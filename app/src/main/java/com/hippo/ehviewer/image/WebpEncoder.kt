package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.os.Build
import java.io.OutputStream

/** One encoder policy for checkpoints and overlays, including Android 8/9. */
internal object WebpEncoder {
    const val MAX_DIMENSION = 16383
    val nativeAvailable: Boolean by lazy {
        try { System.loadLibrary("image"); true } catch (_: LinkageError) { false }
    }

    fun losslessExtension(bitmap: Bitmap): String =
        if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION &&
            (Build.VERSION.SDK_INT >= 29 || nativeAvailable)) "webp" else "png"

    @Suppress("DEPRECATION")
    fun writeLossless(bitmap: Bitmap, output: OutputStream) {
        val success = when {
            losslessExtension(bitmap) == "png" -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            nativeAvailable -> nativeWriteLossless(bitmap, output)
            Build.VERSION.SDK_INT >= 30 -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 75, output)
            Build.VERSION.SDK_INT >= 29 -> bitmap.compress(Bitmap.CompressFormat.WEBP, 100, output)
            else -> nativeWriteLossless(bitmap, output)
        }
        check(success) { "Cannot encode lossless image" }
    }

    // Native YUV (lossy) or ARGB (lossless) planes avoid a whole-page Java Bitmap.
    // Only lossy frames require even-height input bands.
    external fun create(width: Int, height: Int): Long
    external fun createLossless(width: Int, height: Int): Long
    external fun append(handle: Long, pixels: IntArray, rows: Int): Boolean
    external fun finish(handle: Long, output: OutputStream, checkActive: Runnable, quality: Int = 95): Boolean
    external fun release(handle: Long)
    private external fun nativeWriteLossless(bitmap: Bitmap, output: OutputStream): Boolean
}
