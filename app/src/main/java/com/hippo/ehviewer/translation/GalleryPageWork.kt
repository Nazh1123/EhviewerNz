package com.hippo.ehviewer.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Main-thread page admission. Never wait here while holding an inference/model lock. */
internal class GalleryPageWork {
    enum class Stage { TRANSLATION, SUPER_RESOLUTION }
    class Queue(val active: () -> Boolean, val pages: () -> List<Int>, val settled: (Int) -> Boolean,
                val ready: () -> Boolean = { true })

    private val queues = mutableMapOf<Stage, Queue>()
    private val revision = MutableStateFlow(0L)
    private var page: Int? = null
    val empty: Boolean get() = queues.isEmpty()
    @Volatile var coupled = false
        private set

    fun register(stage: Stage, queue: Queue) { queues[stage] = queue; changed() }
    fun remove(stage: Stage, queue: Queue) { if (queues[stage] === queue) queues.remove(stage); changed() }
    fun changed() { coupled = queues.values.count { it.active() } == 2; revision.value++ }

    private fun remaining(): Map<Stage, List<Int>> = queues.mapValues { (_, queue) ->
        if (queue.active()) queue.pages().filterNot(queue.settled) else emptyList()
    }

    private fun select(remaining: Map<Stage, List<Int>>): Int? {
        val outstanding = remaining.values.flatten().toSet()
        if (page !in outstanding) {
            // Include settled translation pages in the ordering: their pending SR stage
            // still precedes the next page's translation, including after a cache restore.
            val ordered = Stage.entries.flatMap { stage ->
                queues[stage]?.takeIf { it.active() }?.pages().orEmpty()
            }
            page = ordered.firstOrNull { it in outstanding }
        }
        return page
    }

    fun next(stage: Stage): Int? {
        if (coupled && queues.values.any { it.active() && !it.ready() }) return null
        val remaining = remaining()
        val selected = select(remaining) ?: return null
        if (selected !in remaining[stage].orEmpty()) return null
        if (stage == Stage.SUPER_RESOLUTION && selected in remaining[Stage.TRANSLATION].orEmpty()) return null
        return selected
    }

    suspend fun await(stage: Stage): Int? {
        revision.first { next(stage) != null || queues[stage]?.let {
            !it.active() || it.ready() && remaining()[stage].isNullOrEmpty()
        } != false }
        return next(stage)
    }
}

/** Sessions for the same source identity share ordering, but keep independent windows/results. */
internal object GalleryPageWorkRegistry {
    private val galleries = mutableMapOf<String, GalleryPageWork>()
    fun acquire(identity: String) = galleries.getOrPut(identity) { GalleryPageWork() }
    fun release(identity: String, work: GalleryPageWork) {
        if (work.empty && galleries[identity] === work) galleries.remove(identity)
    }
}
