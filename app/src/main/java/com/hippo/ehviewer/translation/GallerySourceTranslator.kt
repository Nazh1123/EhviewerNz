package com.hippo.ehviewer.translation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hippo.ehviewer.translation.engine.DetailedTranslator
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.Translator

/** Source selection happens after OCR. Keep backend creation lazy and close replaced delegates. */
internal class GallerySourceTranslator(
    private val options: () -> TranslationOptions,
    private val create: suspend (TranslationOptions) -> Translator,
) : DetailedTranslator, AutoCloseable {
    private val lock = Mutex()
    private var selected: Translator? = null
    private var selectedIdentity: String? = null
    private var closed = false

    override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult {
        val backend = lock.withLock {
            check(!closed) { "Translator is closed" }
            val current = options()
            val identity = current.cacheIdentity()
            if (selected == null || selectedIdentity != identity) {
                (selected as? AutoCloseable)?.close()
                selected = null
                selectedIdentity = null
                selected = create(current)
                selectedIdentity = identity
            }
            checkNotNull(selected)
        }
        // The worker admits parallel API pages only after source selection is frozen.
        // Delegate selection is synchronized; requests retain their ordinary concurrency.
        return when (backend) {
            is DetailedTranslator -> backend.translateDetailed(queries)
            else -> LlmTranslator.TranslateResult(backend.translate(queries))
        }
    }

    fun unloadNativeModel() { (selected as? NativeTranslator)?.unloadModel() }

    override fun close() {
        closed = true
        (selected as? AutoCloseable)?.close()
        selected = null
    }
}
