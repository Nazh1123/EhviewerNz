package com.hippo.ehviewer.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native calls finish before releasing their images; obsolete waiters never enter JNI. */
internal class TranslationImageWork {
    private val lock = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = lock.withLock {
        ensureTranslationImageRelevant()
        block()
    }
}

private suspend fun ensureTranslationImageRelevant() {
    currentCoroutineContext().ensureActive()
    currentCoroutineContext()[TranslationPageRequest]?.ensureRelevant()
}

/** Bound both CPU concurrency and the amount of OCR left after a page is superseded. */
internal suspend fun <T> recognizeTranslationBatches(
    lines: List<T>, concurrency: Int, recognize: suspend (List<T>) -> Unit,
) {
    ensureTranslationImageRelevant()
    for (batch in lines.chunked(concurrency.coerceAtLeast(1))) {
        ensureTranslationImageRelevant()
        recognize(batch)
        ensureTranslationImageRelevant()
    }
}
