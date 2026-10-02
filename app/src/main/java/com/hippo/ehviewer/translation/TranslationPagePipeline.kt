package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** A shared engine starts serially, then overlaps pages after its native sessions are warm.
 * Admission is rechecked whenever a slot opens, so reader navigation and turn priorities
 * affect queued work. All admitted pages finish cleanup before the engine can be closed. */
internal suspend fun <T> runTranslationPages(
    concurrency: Int,
    parallelReady: () -> Boolean,
    next: suspend () -> T?,
    process: suspend (T) -> Unit,
) = coroutineScope {
    val completions = Channel<Deferred<Unit>>(Channel.UNLIMITED)
    val running = mutableSetOf<Deferred<Unit>>()
    try {
        while (true) {
            val limit = if (parallelReady()) concurrency.coerceAtLeast(1) else 1
            while (running.size < limit) {
                ensureActive()
                val request = next() ?: break
                val task = async(start = CoroutineStart.LAZY) { process(request) }
                running.add(task)
                task.invokeOnCompletion { completions.trySend(task) }
                task.start()
            }
            if (running.isEmpty()) break
            val finished = completions.receive()
            running.remove(finished)
            finished.await()
        }
    } finally {
        completions.close()
    }
}
