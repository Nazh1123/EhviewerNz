package com.hippo.ehviewer.translation

import com.hippo.ehviewer.translation.engine.PreparedPage

/** Process-local LRU. take transfers ownership, so memory-pressure cleanup cannot recycle active work. */
internal class PreparedPageCache(private val limit: Long = 64L * 1024 * 1024, private val maxPages: Int = 4) {
    private val entries = LinkedHashMap<String, PreparedPage>()
    @Synchronized fun take(key: String): PreparedPage? = entries.remove(key)
    @Synchronized fun put(key: String, page: PreparedPage) {
        entries.remove(key)?.takeIf { it !== page }?.close()
        entries[key] = page
        var bytes = entries.values.sumOf { it.byteCount }
        while (bytes > limit || entries.size > maxPages) {
            val oldest = entries.entries.first()
            bytes -= oldest.value.byteCount
            entries.remove(oldest.key)
            oldest.value.close()
        }
    }
    @Synchronized fun invalidate(key: String) { entries.remove(key)?.close() }
    @Synchronized fun clear() { entries.values.forEach { it.close() }; entries.clear() }
}
