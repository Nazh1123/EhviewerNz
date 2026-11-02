package com.hippo.ehviewer.translation

import kotlinx.coroutines.CompletableDeferred

/** Shared admission for heavy image work. Translation wins at every dispatch/tile boundary. */
internal class ImageWorkPriority {
    private class Ticket(val translation: Boolean) { val ready = CompletableDeferred<Unit>() }
    private val waiting = mutableListOf<Ticket>()
    private var owner: Ticket? = null
    val translationPending: Boolean
        @Synchronized get() = owner?.translation == true || waiting.any { it.translation }

    private fun dispatch() {
        if (owner != null) return
        val next = waiting.firstOrNull { it.translation } ?: waiting.firstOrNull() ?: return
        waiting.remove(next)
        owner = next
        next.ready.complete(Unit)
    }

    private suspend fun acquire(translation: Boolean): Ticket {
        val ticket = Ticket(translation)
        synchronized(this) { waiting.add(ticket); dispatch() }
        try { ticket.ready.await(); return ticket }
        catch (error: Throwable) { release(ticket); throw error }
    }

    @Synchronized private fun release(ticket: Ticket) {
        waiting.remove(ticket)
        if (owner === ticket) owner = null
        dispatch()
    }

    suspend fun <T> run(translation: Boolean, block: suspend () -> T): T {
        val ticket = acquire(translation)
        try { return block() } finally { release(ticket) }
    }
}

internal object ImageProcessingPriority {
    val work = ImageWorkPriority()
}
