package com.hippo.ehviewer.translation.engine

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** A read lease keeps model disposal behind every admitted native inference. */
internal class ImageModel(param: String, threads: Int, halfPrecision: Boolean, bin: String = param.removeSuffix(".param") + ".bin") : AutoCloseable {
    private val lifecycle = ReentrantReadWriteLock()
    private var handle: Long
    @Volatile private var ocrReady = false
    init {
        require(param.endsWith(".param") && File(param).isFile && File(bin).isFile) { "Image model files are missing" }
        handle = ImageInference.open(param, bin, threads, halfPrecision)
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
    fun warmOcr() = recognize(FloatArray(48 * 64 * 3), 64, IntArray(15), FloatArray(15))
    override fun close() = lifecycle.write {
        if (handle != 0L) { ImageInference.close(handle); handle = 0L }
    }
}

internal object ImageInference {
    init { System.loadLibrary("ehnz_image") }
    val parallelLock = Any()
    external fun open(param: String, bin: String, threads: Int, halfPrecision: Boolean): Long
    external fun close(handle: Long)
    external fun supportsFp16(): Boolean
    external fun detect(handle: Long, input: FloatArray, width: Int, height: Int,
                        logits: FloatArray, strokes: FloatArray): IntArray?
    external fun recognize(handle: Long, input: FloatArray, width: Int,
                           indices: IntArray, probabilities: FloatArray): Boolean
    external fun inpaint(handle: Long, input: FloatArray, mask: FloatArray, size: Int, output: FloatArray): Boolean
}
