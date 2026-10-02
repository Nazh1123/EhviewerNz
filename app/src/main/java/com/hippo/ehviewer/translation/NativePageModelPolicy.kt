package com.hippo.ehviewer.translation

/** One page owns both native branches until they have joined. Visibility changes
 * affect residency at that boundary and scheduling on the following page. */
internal class NativePageModelPolicy(
    private val canRetain: () -> Boolean,
    private val releaseImages: () -> Unit,
    private val unloadLanguageModel: () -> Unit,
) {
    @Volatile private var parallel = false

    fun beginPage(): Boolean {
        parallel = canRetain()
        return parallel
    }

    fun beforeImage() {
        if (!parallel && !canRetain()) {
            try { releaseImages() } finally { unloadLanguageModel() }
        }
    }

    fun afterImage() {
        if (!parallel && !canRetain()) releaseImages()
    }

    fun languageBoundary() {
        if (!parallel && !canRetain()) releaseImages()
    }

    // Called only after translation and any started inpainting have returned.
    fun endPage() {
        parallel = false
        if (!canRetain()) {
            try { releaseImages() } finally { unloadLanguageModel() }
        }
    }
}
