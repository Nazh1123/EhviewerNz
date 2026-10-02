package com.hippo.ehviewer.translation

import kotlinx.coroutines.CompletableDeferred

/** Main-thread admission for one model owner. Waiting workers suspend without loading models.
 * Priority is evaluated at dispatch/page boundaries so navigation can reorder existing waiters.
 */
internal class TranslationScheduler(private val pageQuantum: Int = 4) {
    class Turn internal constructor(internal val priority: () -> Int) {
        internal val ready = CompletableDeferred<Unit>()
        internal var pages = 0
    }

    private val waiting = mutableListOf<Turn>()
    private var owner: Turn? = null

    suspend fun acquire(priority: () -> Int): Turn {
        val turn = Turn(priority)
        waiting.add(turn)
        dispatch()
        try {
            turn.ready.await()
            return turn
        } catch (error: Throwable) {
            release(turn)
            throw error
        }
    }

    fun pageFinished(turn: Turn) {
        if (owner === turn) turn.pages++
    }

    fun shouldYield(turn: Turn): Boolean {
        if (owner !== turn) return true
        val next = waiting.minByOrNull { it.priority() } ?: return false
        val priority = turn.priority()
        return next.priority() < priority ||
            (turn.pages >= pageQuantum && next.priority() == priority)
    }

    fun release(turn: Turn) {
        waiting.remove(turn)
        if (owner === turn) owner = null
        dispatch()
    }

    private fun dispatch() {
        if (owner != null) return
        val next = waiting.minByOrNull { it.priority() } ?: return
        waiting.remove(next)
        owner = next
        next.ready.complete(Unit)
    }
}
