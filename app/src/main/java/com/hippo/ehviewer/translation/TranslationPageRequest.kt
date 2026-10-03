package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Invalidates one page without cancelling the worker or interrupting native inference. */
internal class TranslationPageRequest(val page: Int, val force: Boolean, val restoring: Boolean = false,
                                      val retryMissing: Boolean = false) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranslationPageRequest>
    val progress = TranslationPageProgress()
    var sourceName: String? = null
    @Volatile var isObsolete = false
        private set
    private var apiJob: Job? = null

    fun supersede() {
        val job = synchronized(this) {
            isObsolete = true
            apiJob
        }
        job?.cancel()
    }

    fun ensureRelevant() {
        if (isObsolete) throw SupersededTranslationPage()
    }

    /** Only the cancellable API segment is interrupted; Pipeline can finish native cleanup. */
    suspend fun <T> apiCall(block: suspend () -> T): T = coroutineScope {
        ensureRelevant()
        val call = async(start = CoroutineStart.LAZY) { block() }
        synchronized(this@TranslationPageRequest) {
            if (isObsolete) call.cancel() else apiJob = call
        }
        try {
            call.await().also { ensureRelevant() }
        } catch (cancel: CancellationException) {
            // A page change must not cancel the long-lived worker or the enclosing Pipeline.
            currentCoroutineContext().ensureActive()
            if (isObsolete) throw SupersededTranslationPage()
            throw cancel
        } finally {
            synchronized(this@TranslationPageRequest) { if (apiJob === call) apiJob = null }
        }
    }
}

internal class SupersededTranslationPage : Exception("Page left the translation window")
