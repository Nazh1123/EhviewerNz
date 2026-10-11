package com.hippo.ehviewer.translation

/** Learned only within one translator (model and language pair), including model unloads. */
internal class NativeBatchPolicy {
    private var regionLimit = MAX_REGIONS
    private var successes = 0

    fun batchSize(tokenCounts: List<Int>, offset: Int): Int {
        var tokens = 0L
        var count = 0
        while (offset + count < tokenCounts.size && count < regionLimit) {
            // Reserve space for numbering and separators; begin() still checks the exact context.
            val next = tokenCounts[offset + count].toLong() + 8
            if (count > 0 && tokens + next > SOURCE_TOKENS) break
            tokens += next
            count++
        }
        return count
    }

    fun failed(size: Int) {
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

    private companion object {
        const val MAX_REGIONS = 8
        const val SOURCE_TOKENS = 512
    }
}
