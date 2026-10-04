package com.hippo.ehviewer.translation

import kotlinx.coroutines.delay

/** Delay admission during a swipe burst without pausing the reader or restarting its worker. */
internal class TranslationNavigationSettler(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile private var readyAt = 0L

    fun pageChanged() { readyAt = nowMillis() + 150L }

    suspend fun await(shouldWait: suspend () -> Boolean) {
        while (shouldWait()) {
            val remaining = readyAt - nowMillis()
            if (remaining <= 0L) return
            waitMillis(remaining)
        }
    }
}
