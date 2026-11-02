package com.hippo.ehviewer.translation

import kotlinx.coroutines.flow.MutableStateFlow

/** Main-thread FIFO for whole galleries, shared by translation and super resolution. */
internal class GalleryTaskQueue {
    private val waiting = linkedSetOf<String>()
    val identities: List<String> get() = waiting.toList()
    val revision = MutableStateFlow(0L)

    fun changed(identity: String, fullGallery: Boolean) {
        val previous = waiting.firstOrNull()
        if (fullGallery) waiting.add(identity) else waiting.remove(identity)
        // Progress updates in one gallery must not wake every queued gallery.
        if (previous != waiting.firstOrNull()) revision.value++
    }

    fun admits(identity: String): Boolean = waiting.firstOrNull() == identity
}
