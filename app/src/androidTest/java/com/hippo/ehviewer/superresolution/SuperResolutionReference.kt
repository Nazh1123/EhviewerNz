package com.hippo.ehviewer.superresolution

import android.graphics.Bitmap
import com.hippo.ehviewer.translation.engine.NcnnModelFiles

/** Only the debug native library exports this pinned-upstream comparison adapter. */
internal object SuperResolutionReference {
    init { System.loadLibrary("ehnz_image") }
    external fun open(param: String, weights: String): Long
    external fun render(handle: Long, source: Bitmap, destination: Bitmap): Boolean
    external fun close(handle: Long)

    class Model(files: NcnnModelFiles) : AutoCloseable {
        private var handle = files.param.read { path, fd ->
            files.weights.read { weights, binary ->
                open(path ?: "/proc/self/fd/${checkNotNull(fd).fd}",
                    weights ?: "/proc/self/fd/${checkNotNull(binary).fd}")
            }
        }.also { check(it != 0L) { "Upstream model failed to load" } }
        fun upscale(source: Bitmap): Bitmap = Bitmap.createBitmap(source.width * 2, source.height * 2, Bitmap.Config.ARGB_8888).also {
            try { check(render(handle, source, it)) { "Upstream pipeline failed" } }
            catch (error: Throwable) { it.recycle(); throw error }
        }
        override fun close() { if (handle != 0L) { close(handle); handle = 0L } }
    }
}
