package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** All three modes share the same pinned model, crop, padding and 128-pixel tiles. */
enum class SuperResolutionPipeline { LEGACY_FP32, GPU_FP32, GPU_FP16 }

internal fun interface SuperResolutionProgress {
    fun onProgress(completed: Int, total: Int): Boolean
}

internal interface SuperResolutionBitmapModel : SuperResolutionTileModel {
    fun render(source: Bitmap, destination: Bitmap, progress: SuperResolutionProgress)
    fun renderRegion(source: Bitmap, destination: Bitmap, left: Int, top: Int, progress: SuperResolutionProgress) {
        check(left == 0 && top == 0 && destination.width == source.width * 2 && destination.height == source.height * 2)
        render(source, destination, progress)
    }
}

/** Model weights and shader pipelines can survive a page change; bitmaps never do. */
internal class SuperResolutionVulkan(files: NcnnModelFiles, halfStorage: Boolean,
                                     halfArithmetic: Boolean = false, batchTiles: Int = 1) : SuperResolutionBitmapModel {
    private val lifecycle = ReentrantReadWriteLock()
    private var handle = files.param.read { param, graph ->
        files.weights.read { weights, binary ->
            if (graph == null && binary == null)
                SuperResolutionInference.open(checkNotNull(param), checkNotNull(weights), halfStorage, halfArithmetic, batchTiles)
            else {
                require(graph != null && binary != null) { "Image model sources must use the same storage mode" }
                SuperResolutionInference.openDescriptors(graph.fd, binary.fd, halfStorage, halfArithmetic, batchTiles)
            }
        }
    }.also { check(it != 0L) { "Vulkan super-resolution pipeline could not be loaded" } }

    override fun render(source: Bitmap, destination: Bitmap, progress: SuperResolutionProgress) = lifecycle.read {
        check(handle != 0L) { "Super-resolution model is closed" }
        check(SuperResolutionInference.render(handle, source, destination, progress, ImageInference.parallelLock)) {
            "Vulkan super-resolution pipeline failed"
        }
    }
    override fun upscale(input: FloatArray, width: Int, height: Int, output: FloatArray): Unit =
        error("Bitmap pipeline does not accept CPU float tiles")
    override fun renderRegion(source: Bitmap, destination: Bitmap, left: Int, top: Int, progress: SuperResolutionProgress) = lifecycle.read {
        check(handle != 0L) { "Super-resolution model is closed" }
        check(SuperResolutionInference.renderRegion(handle, source, destination, left, top, progress, ImageInference.parallelLock)) {
            "Vulkan super-resolution region failed"
        }
    }
    override fun close() = lifecycle.write {
        if (handle != 0L) { SuperResolutionInference.close(handle); handle = 0L }
    }
}

internal object SuperResolutionInference {
    init { System.loadLibrary("ehnz_image") }
    external fun open(param: String, weights: String, halfStorage: Boolean, halfArithmetic: Boolean, batchTiles: Int): Long
    external fun openDescriptors(paramFd: Int, weightsFd: Int, halfStorage: Boolean, halfArithmetic: Boolean, batchTiles: Int): Long
    external fun render(handle: Long, source: Bitmap, destination: Bitmap,
                        progress: SuperResolutionProgress, parallelLock: Any): Boolean
    external fun close(handle: Long)
    external fun renderRegion(handle: Long, source: Bitmap, destination: Bitmap, left: Int, top: Int,
                              progress: SuperResolutionProgress, parallelLock: Any): Boolean
}
