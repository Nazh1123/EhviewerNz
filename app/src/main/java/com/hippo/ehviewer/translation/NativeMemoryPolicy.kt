package com.hippo.ehviewer.translation

import android.app.ActivityManager
import android.content.Context

/** Retaining all image models beside GGUF is an optional speed optimization. */
internal class NativeMemoryPolicy(context: Context) {
    private val manager = context.getSystemService(ActivityManager::class.java)

    fun canRetain(): Boolean {
        val manager = manager ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return canRetain(manager.isLowRamDevice, info.lowMemory, info.totalMem, info.availMem, info.threshold)
    }

    companion object {
        private const val MIB = 1024L * 1024L

        // A conservative retention heuristic, not a GGUF admission limit.
        // 5 GiB reported by Android admits typical 6 GB phones; smaller devices
        // use the existing sequential stage policy even while the reader is open.
        internal fun canRetain(lowRam: Boolean, lowMemory: Boolean, total: Long, available: Long,
                               threshold: Long): Boolean =
            !lowRam && !lowMemory && total >= 5 * 1024 * MIB &&
                available >= 512 * MIB && available / 2 >= threshold
    }
}
