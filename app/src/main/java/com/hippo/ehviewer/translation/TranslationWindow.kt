package com.hippo.ehviewer.translation

/** Recomputed at each dispatch, optionally retaining the unfinished prefix after a small page turn. */
object TranslationWindow {
    /** Finish the pending reader prefix, sweep forward, then wrap even after the reader leaves. */
    fun fullPages(current: Int, size: Int, pendingStart: Int = current): List<Int> =
        fullPageSequence(current, size, pendingStart).toList()

    internal fun fullPageSequence(current: Int, size: Int, pendingStart: Int = current): Sequence<Int> {
        if (size <= 0) return emptySequence()
        if (current !in 0 until size) return (0 until size).asSequence()
        val start = minOf(current, pendingStart.coerceAtLeast(0))
        return (start until size).asSequence() + (0 until start).asSequence()
    }

    fun pages(current: Int, size: Int, ahead: Int, pendingStart: Int = current): List<Int> {
        if (current < 0 || current >= size) return emptyList()
        val end = (current.toLong() + ahead.coerceIn(0, 10)).coerceAtMost(size.toLong() - 1).toInt()
        val start = minOf(current, pendingStart.coerceAtLeast(0))
        return (start..end).toList()
    }
}
