package com.hippo.ehviewer.superresolution

/** Access only while holding SuperResolutionModels.lock, including asynchronous disposal. */
internal class SuperResolutionSessionCache<T : AutoCloseable> : AutoCloseable {
    private var key: String? = null
    private var value: T? = null
    private var closed = false

    fun acquire(requested: String, create: () -> T): T {
        check(!closed) { "Super-resolution session is closed" }
        value?.takeIf { key == requested }?.let { return it }
        clear()
        return create().also { value = it; key = requested }
    }

    fun clear() {
        val previous = value
        value = null
        key = null
        previous?.close()
    }

    override fun close() { closed = true; clear() }
}
