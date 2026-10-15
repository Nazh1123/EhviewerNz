package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.isActive

/** waifu2x upconv_7 anime RGB, 2x without denoising; matches nihui's CPU preprocessing. */
class SuperResolution @JvmOverloads constructor(files: NcnnModelFiles,
    backend: SuperResolutionBackend = SuperResolutionBackend.AUTO,
    pipeline: SuperResolutionPipeline = SuperResolutionPipeline.GPU_FP32) : AutoCloseable {
    private val runtime = SuperResolutionRuntime(backend, { ImageInference.vulkanDeviceName() }) { gpu ->
        if (gpu && pipeline != SuperResolutionPipeline.LEGACY_FP32)
            SuperResolutionVulkan(files, halfStorage = pipeline == SuperResolutionPipeline.GPU_FP16)
        else ImageModel(files, threads = if (gpu) 1 else 2, halfPrecision = false, useVulkan = gpu)
    }
    val usingVulkan: Boolean get() = runtime.usingVulkan
    val fellBack: Boolean get() = runtime.fellBack
    val deviceName: String? get() = runtime.deviceName

    suspend fun upscale(source: Bitmap, maxOutputBytes: Long = DEFAULT_OUTPUT_BYTES,
                        progress: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
        require(source.config != Bitmap.Config.HARDWARE && !source.isRecycled)
        require(accepts(source.width, source.height, maxOutputBytes)) { "Super-resolution output exceeds memory budget" }
        currentCoroutineContext().ensureActive()
        val result = Bitmap.createBitmap(source.width * 2, source.height * 2, Bitmap.Config.ARGB_8888)
        try {
            val request = currentCoroutineContext()
            var lastCompleted = 0
            if (runtime.render { model ->
                model.render(source, result) { done, total ->
                    if (request.isActive && done > lastCompleted) {
                        lastCompleted = done
                        progress(done, total)
                    }
                    request.isActive
                }
            }) return result
            val total = ((source.width + TILE - 1) / TILE) * ((source.height + TILE - 1) / TILE)
            var completed = 0
            for (y in 0 until source.height step TILE) for (x in 0 until source.width step TILE) {
                currentCoroutineContext().ensureActive()
                val w = minOf(TILE, source.width - x)
                val h = minOf(TILE, source.height - y)
                val iw = (w + 1) / 2 * 2 + 14
                val ih = (h + 1) / 2 * 2 + 14
                // Extract only this tile's surrounding context, never a full-page float tensor.
                val left = maxOf(0, x - 7)
                val top = maxOf(0, y - 7)
                val right = minOf(source.width, x - 7 + iw)
                val bottom = minOf(source.height, y - 7 + ih)
                val rw = right - left
                val pixels = IntArray(rw * (bottom - top))
                source.getPixels(pixels, 0, rw, left, top, rw, bottom - top)
                val input = FloatArray(iw * ih * 3)
                for (py in 0 until ih) for (px in 0 until iw) {
                    val sx = (x + px - 7).coerceIn(left, right - 1) - left
                    val sy = (y + py - 7).coerceIn(top, bottom - 1) - top
                    val color = pixels[sy * rw + sx]
                    val index = py * iw + px
                    // Reader pages are opaque; composite transparent source pixels on white.
                    val alpha = (color ushr 24) / 255f
                    for (c in 0..2) {
                        val channel = (color ushr (16 - c * 8)) and 255
                        input[c * iw * ih + index] = (channel / 255f) * alpha + 1f - alpha
                    }
                }
                val ow = (iw - 14) * 2
                val oh = (ih - 14) * 2
                val output = FloatArray(ow * oh * 3)
                runtime.upscale(input, iw, ih, output)
                currentCoroutineContext().ensureActive()
                val colors = IntArray(w * h * 4)
                for (py in 0 until h * 2) for (px in 0 until w * 2) {
                    var color = -0x1000000
                    for (c in 0..2) {
                        val value = output[c * ow * oh + py * ow + px]
                        check(value.isFinite()) { "Invalid super-resolution output" }
                        color = color or ((value * 255f + 0.5f).toInt().coerceIn(0, 255) shl (16 - c * 8))
                    }
                    colors[py * w * 2 + px] = color
                }
                result.setPixels(colors, 0, w * 2, x * 2, y * 2, w * 2, h * 2)
                progress(++completed, total)
                yield() // Cancel obsolete pages between native tiles.
            }
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    override fun close() = runtime.close()

    companion object {
        const val TILE = 128
        const val DEFAULT_OUTPUT_BYTES = 128L * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 256L * 1024 * 1024
        fun vulkanDeviceName(): String? = ImageInference.vulkanDeviceName()
        @JvmOverloads
        fun accepts(width: Int, height: Int, maxOutputBytes: Long = DEFAULT_OUTPUT_BYTES) =
            width > 0 && height > 0 && width <= 32768 && height <= 32768 &&
                width.toLong() * height <= minOf(maxOutputBytes, MAX_OUTPUT_BYTES).coerceAtLeast(0) / 16
    }
}
