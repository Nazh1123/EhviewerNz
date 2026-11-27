package com.hippo.ehviewer.translation

import com.hippo.ehviewer.translation.engine.PreparedPage
import com.hippo.ehviewer.translation.engine.TranslationResume

/** Process-local LRU. take transfers ownership, so memory-pressure cleanup cannot recycle active work. */
internal class PreparedPageCache(private val limit: Long = 64L * 1024 * 1024, private val maxPages: Int = 4) {
    private data class Entry(val page: PreparedPage, val translationIdentity: String?)
    private data class Checkpoint(val identity: String, val resume: TranslationResume) {
        val bytes: Long = resume.regions.sumOf { it.length * 2L } +
            resume.translations.values.sumOf { it.length * 2L } + resume.regions.size * 64L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val checkpoints = LinkedHashMap<String, Checkpoint>()

    @Synchronized fun take(key: String): PreparedPage? = entries.remove(key)?.page

    @Synchronized fun takeCheckpoint(key: String, identity: String): TranslationResume? =
        checkpoints[key]?.takeIf { it.identity == identity }?.let { checkpoints.remove(key)?.resume }

    @Synchronized fun put(key: String, page: PreparedPage, translationIdentity: String? = null) {
        entries.remove(key)?.page?.takeIf { it !== page }?.close()
        entries[key] = Entry(page, translationIdentity)
        var bytes = entries.values.sumOf { it.page.byteCount }
        while (bytes > limit || entries.size > maxPages) {
            val (oldestKey, entry) = entries.entries.first()
            entries.remove(oldestKey)
            bytes -= entry.page.byteCount
            try {
                val identity = entry.translationIdentity
                val resume = identity?.let { entry.page.translationResume(it) }
                if (identity != null && resume != null && resume.translations.isNotEmpty())
                    rememberCheckpoint(oldestKey, Checkpoint(identity, resume))
            } finally {
                entry.page.close()
            }
        }
    }

    private fun rememberCheckpoint(key: String, checkpoint: Checkpoint) {
        if (checkpoint.bytes > 2L * 1024 * 1024) return
        checkpoints.remove(key)
        checkpoints[key] = checkpoint
        var bytes = checkpoints.values.sumOf { it.bytes }
        while (bytes > 2L * 1024 * 1024 || checkpoints.size > 16)
            bytes -= checkNotNull(checkpoints.remove(checkpoints.keys.first())).bytes
    }

    @Synchronized fun invalidate(key: String) {
        entries.remove(key)?.page?.close()
        checkpoints.remove(key)
    }

    @Synchronized fun clear() {
        entries.values.forEach { it.page.close() }
        entries.clear()
        checkpoints.clear()
    }
}
