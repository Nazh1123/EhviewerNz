package com.hippo.ehviewer.translation

/** Learned only within one translator (model and language pair), including model unloads. */
internal class NativeBatchPolicy {
    private var regionLimit = MAX_REGIONS
    private var successes = 0

    fun batchSize(remaining: Int, tokenCount: (Int) -> Int): Int {
        val limit = minOf(remaining, regionLimit)
        if (limit <= 1) return limit
        // Reserve space for numbering and separators; begin() still checks the exact context.
        var tokens = tokenCount(0).toLong() + 8
        var count = 1
        while (count < limit) {
            val next = tokenCount(count).toLong() + 8
            if (tokens + next > SOURCE_TOKENS) break
            tokens += next
            count++
        }
        return count
    }

    fun protocolFailed(size: Int) {
        successes = 0
        if (size <= 1) return
        regionLimit = minOf(regionLimit, (size / 2).coerceAtLeast(1))
    }

    fun succeeded(size: Int) {
        // Compatibility retries do not call this: they are not evidence that batching works.
        if (size < regionLimit || regionLimit == MAX_REGIONS) return
        if (++successes >= if (regionLimit == 1) 8 else 3) {
            regionLimit++
            successes = 0
        }
    }

    companion object {
        const val MAX_REGIONS = 8
        private const val SOURCE_TOKENS = 512
    }
}
