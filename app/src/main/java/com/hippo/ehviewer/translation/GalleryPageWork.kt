package com.hippo.ehviewer.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Main-thread page admission. Never wait here while holding an inference/model lock. */
internal class GalleryPageWork(
    private val identity: String = "",
    private val galleryQueue: GalleryTaskQueue = GalleryTaskQueue(),
) {
    enum class Stage { TRANSLATION, SUPER_RESOLUTION }
    class Queue(val active: () -> Boolean, val pages: () -> List<Int>, val settled: (Int) -> Boolean,
                val ready: () -> Boolean = { true },
                val fullGallery: () -> Boolean = { false },
                val foregroundPages: () -> List<Int> = { emptyList() })

    private val queues = mutableMapOf<Stage, Queue>()
    private val revision = MutableStateFlow(0L)
    private val changes = combine(revision, galleryQueue.revision) { _, _ -> Unit }
    private var page: Int? = null
    val empty: Boolean get() = queues.isEmpty()
    @Volatile var coupled = false
        private set

    fun register(stage: Stage, queue: Queue) { queues[stage] = queue; changed() }
    fun remove(stage: Stage, queue: Queue) { if (queues[stage] === queue) queues.remove(stage); changed() }
    fun changed() {
        coupled = queues.values.count { it.active() } == 2
        // Keep the slot until BOTH full-gallery stages have finished or been stopped.
        galleryQueue.changed(identity, queues.values.any { it.active() && it.fullGallery() })
        revision.value++
    }

    fun admits(stage: Stage, page: Int): Boolean = queues[stage]?.let {
        !it.fullGallery() || galleryQueue.admits(identity) || queues.values.any { queue ->
            // Both stages of a visible page must be admitted, even with different lookaheads.
            queue.active() && page in queue.foregroundPages()
        }
    } == true

    /** Unknown sources must wait outside model locks too, before their page list is known. */
    suspend fun awaitAdmission(stage: Stage) {
        awaitChangeUntil {
            queues[stage]?.let { queue ->
                !queue.active() || !queue.fullGallery() || galleryQueue.admits(identity) ||
                    queues.values.any { it.active() && it.foregroundPages().isNotEmpty() }
            } != false
        }
    }

    private suspend fun awaitChangeUntil(ready: () -> Boolean) {
        if (!ready()) changes.first { ready() }
    }

    private fun remaining(): Map<Stage, List<Int>> = queues.mapValues { (stage, queue) ->
        if (queue.active()) queue.pages().filter { !queue.settled(it) && admits(stage, it) } else emptyList()
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
        awaitChangeUntil { next(stage) != null || queues[stage]?.let {
            // A queued gallery has unfinished pages even when none are admitted yet.
            !it.active() || it.ready() && it.pages().all(it.settled)
        } != false }
        return next(stage)
    }
}

/** Sessions for the same source identity share ordering, but keep independent windows/results. */
internal object GalleryPageWorkRegistry {
    private val galleries = mutableMapOf<String, GalleryPageWork>()
    private val galleryQueue = GalleryTaskQueue()
    fun fullGalleryOrder(): List<String> = galleryQueue.identities
    fun acquire(identity: String) = galleries.getOrPut(identity) { GalleryPageWork(identity, galleryQueue) }
    fun release(identity: String, work: GalleryPageWork) {
        if (work.empty && galleries[identity] === work) galleries.remove(identity)
    }
}
