package com.hippo.ehviewer.translation.engine

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** A read lease keeps model disposal behind every admitted native inference. */
internal class ImageModel(files: NcnnModelFiles, threads: Int, halfPrecision: Boolean,
                          useVulkan: Boolean = false) : SuperResolutionTileModel {
    private val lifecycle = ReentrantReadWriteLock()
    private var handle: Long
    @Volatile private var ocrReady = false
    init {
        handle = files.param.read { param, graph ->
            files.weights.read { bin, weights ->
                if (graph == null && weights == null) {
                    require(checkNotNull(param).endsWith(".param") && File(param).isFile && File(checkNotNull(bin)).isFile) {
                        "Image model files are missing"
                    }
                    if (useVulkan) ImageInference.openVulkan(param, bin)
                    else ImageInference.open(param, bin, threads, halfPrecision)
                } else {
                    require(graph != null && weights != null) { "Image model sources must use the same storage mode" }
                    if (useVulkan) ImageInference.openVulkanDescriptors(graph.fd, weights.fd)
                    else ImageInference.openDescriptors(graph.fd, weights.fd, threads, halfPrecision)
                }
            }
        }
        check(handle != 0L) { "Image model could not be loaded" }
    }
    private fun <T> use(block: (Long) -> T): T = lifecycle.read {
        check(handle != 0L) { "Image model is closed" }
        block(handle)
    }
    fun detect(input: FloatArray, w: Int, h: Int, logits: FloatArray, strokes: FloatArray): IntArray = use {
        synchronized(ImageInference.parallelLock) {
            ImageInference.detect(it, input, w, h, logits, strokes).also { size ->
                check(size != null && size.size == 2 && size[0] > 0 && size[1] > 0) { "Detection inference failed" }
            }!!
        }
    }
    fun recognize(input: FloatArray, w: Int, indices: IntArray, probabilities: FloatArray) = use { handle ->
        if (!ocrReady) synchronized(this) {
            if (!ocrReady) {
                check(ImageInference.recognize(handle, input, w, indices, probabilities)) { "OCR inference failed" }
                ocrReady = true
                return@use
            }
        }
        check(ImageInference.recognize(handle, input, w, indices, probabilities)) { "OCR inference failed" }
    }
    fun inpaint(input: FloatArray, mask: FloatArray, size: Int, output: FloatArray) = use {
        synchronized(ImageInference.parallelLock) {
            check(ImageInference.inpaint(it, input, mask, size, output)) { "Text removal inference failed" }
        }
    }
    fun warmDetect() = detect(FloatArray(256 * 256 * 3), 256, 256, FloatArray(256 * 256), FloatArray(256 * 256))
    override fun upscale(input: FloatArray, width: Int, height: Int, output: FloatArray) = use {
        synchronized(ImageInference.parallelLock) {
            check(ImageInference.upscale(it, input, width, height, output)) { "Super-resolution inference failed" }
        }
    }
    fun warmOcr() = recognize(FloatArray(48 * 64 * 3), 64, IntArray(15), FloatArray(15))
    override fun close() = lifecycle.write {
        if (handle != 0L) { ImageInference.close(handle); handle = 0L }
    }
}

internal object ImageInference {
    init { System.loadLibrary("ehnz_image") }
    val parallelLock = Any()
    external fun open(param: String, bin: String, threads: Int, halfPrecision: Boolean): Long
    external fun openDescriptors(paramFd: Int, weightsFd: Int, threads: Int, halfPrecision: Boolean): Long
    external fun openVulkan(param: String, bin: String): Long
    external fun openVulkanDescriptors(paramFd: Int, weightsFd: Int): Long
    external fun vulkanDeviceName(): String?
    external fun close(handle: Long)
    external fun supportsFp16(): Boolean
    external fun detect(handle: Long, input: FloatArray, width: Int, height: Int,
                        logits: FloatArray, strokes: FloatArray): IntArray?
    external fun recognize(handle: Long, input: FloatArray, width: Int,
                           indices: IntArray, probabilities: FloatArray): Boolean
    external fun inpaint(handle: Long, input: FloatArray, mask: FloatArray, size: Int, output: FloatArray): Boolean
    external fun upscale(handle: Long, input: FloatArray, width: Int, height: Int, output: FloatArray): Boolean
}
