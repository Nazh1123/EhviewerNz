package com.hippo.ehviewer.translation.engine


class NativePrefixCache : AutoCloseable {
    private val handle = NativeCacheHandle(createCache(), ::retainCache, ::destroyCache)

    internal fun createModel(path: String, threads: Int): Long = handle.withLease {
        createModel(it, path.toByteArray(Charsets.UTF_8), threads)
    }

    fun sizeBytes(): Long = handle.withHandle(::sizeBytes)


    fun clear() = handle.withHandle(::clearCache)

    override fun close() = handle.close()

    private external fun createCache(): Long
    private external fun retainCache(handle: Long): Long
    private external fun createModel(handle: Long, path: ByteArray, threads: Int): Long
    private external fun sizeBytes(handle: Long): Long
    private external fun clearCache(handle: Long)
    private external fun destroyCache(handle: Long)

    companion object { init { System.loadLibrary("ehnz_llama") } }
}
