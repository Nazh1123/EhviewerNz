package com.hippo.ehviewer.superresolution

import android.app.ActivityManager
import android.content.Context
import com.hippo.ehviewer.translation.engine.SuperResolution

/** Output pixels are only part of the cost: leave room for the source copy and native tile tensors. */
internal object SuperResolutionMemoryBudget {
    const val MIB = 1024L * 1024
    private const val WORKSPACE_RESERVE = 64 * MIB

    fun current(context: Context): Long {
        val runtime = Runtime.getRuntime()
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        return calculate(runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory(),
            if (manager == null) runtime.maxMemory() else memory.availMem, memory.lowMemory)
    }

    internal fun calculate(heapLimit: Long, usedHeap: Long, availableSystem: Long, lowMemory: Boolean): Long {
        if (lowMemory) return 0
        val headroom = (heapLimit - usedHeap.coerceAtLeast(0)).coerceAtLeast(0)
        // An output plus its independent input copy costs 1.25x output bytes; allow 1.5x.
        val allocationLimit = (headroom - WORKSPACE_RESERVE).coerceAtLeast(0) / 3 * 2
        val systemLimit = (availableSystem - WORKSPACE_RESERVE).coerceAtLeast(0) / 3
        return minOf(SuperResolution.MAX_OUTPUT_BYTES, heapLimit.coerceAtLeast(0) / 3,
            allocationLimit, systemLimit) / MIB * MIB
    }
}
