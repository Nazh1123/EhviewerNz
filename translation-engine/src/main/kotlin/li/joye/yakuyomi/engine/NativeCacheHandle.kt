package li.joye.yakuyomi.engine

/** Owns one cache reference; model creation borrows an independent native reference. */
internal class NativeCacheHandle(
    private var handle: Long,
    private val retain: (Long) -> Long,
    private val destroy: (Long) -> Unit,
) : AutoCloseable {
    @Synchronized fun <T> withHandle(block: (Long) -> T): T {
        check(handle != 0L) { "Native prefix cache is closed" }
        return block(handle)
    }

    fun <T> withLease(block: (Long) -> T): T {
        // Retaining is brief. Model IO runs without the owner's monitor so Main
        // can clear snapshots, and closing the owner cannot invalidate this lease.
        val borrowed = withHandle(retain)
        return try { block(borrowed) } finally { destroy(borrowed) }
    }

    @Synchronized override fun close() {
        if (handle != 0L) { destroy(handle); handle = 0L }
    }
}
