package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observe the first manga pass and switch only when the language model is installed. */
internal class LanguageOcr(
    private val source: () -> String,
    private val observe: suspend (List<TextLine>) -> Unit,
    private val available: (String) -> Boolean = { true },
    private val create: suspend (String) -> PageOcr,
) : PageOcr {
    private val lock = Mutex()
    private var selected: PageOcr? = null
    private var selectedKey: String? = null
    private suspend fun select(key: String): PageOcr {
        if (key != selectedKey) {
            selected?.close(); selected = null; selectedKey = null
            selected = create(key); selectedKey = key
        }
        return checkNotNull(selected)
    }
    override suspend fun recognize(page: Bitmap, lines: List<TextLine>) = lock.withLock {
        fun recognitionKey(): String = PpOcrModels.key(source())?.takeIf(available) ?: "ja"
        val firstKey = recognitionKey()
        suspend fun run(key: String) {
            val backend = select(key)
            recognizeTranslationBatches(lines, 4) { backend.recognize(page, it) }
            lines.forEach { it.text = TranslationOcrText.clean(it.text) }
        }
        run(firstKey)
        observe(lines)
        val resolvedKey = recognitionKey()
        if (resolvedKey != firstKey) {
            lines.forEach { it.text = "" }
            run(resolvedKey)
        }
    }
    override fun close() { selected?.close(); selected = null; selectedKey = null }
}
