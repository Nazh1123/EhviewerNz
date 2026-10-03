package com.hippo.ehviewer.translation

import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import li.joye.yakuyomi.engine.Translator

/** Never downloads implicitly. Only model preparation may access the network. */
class OfflineTranslator(private val target: String, private val source: String = TranslationLanguages.DEFAULT_SOURCE) : Translator, AutoCloseable {
    // A same-language pair is already translated; no SDK client or download is needed.
    private val delegate = if (source == target || source == TranslationLanguages.AUTO_SOURCE) null else Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(source).setTargetLanguage(target).build())

    suspend fun prepare() {
        if (source == TranslationLanguages.AUTO_SOURCE) {
            for (language in TranslationLanguages.detectedSources - "en")
                OfflineTranslator(target, language).use { it.prepare() }
        } else delegate?.downloadModelIfNeeded(modelDownloadConditions())?.await()
    }

    override suspend fun translate(queries: List<String>): List<String> {
        check(source != TranslationLanguages.AUTO_SOURCE) { "Source language must be identified before ML Kit translation" }
        return delegate?.let { client -> queries.map { client.translate(it).await() } } ?: queries
    }

    override fun close() { delegate?.close() }

    companion object {
        internal fun modelDownloadConditions(): DownloadConditions = DownloadConditions.Builder().build()

        suspend fun downloadedLanguages(): Set<String> = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

        suspend fun deleteLanguage(language: String) {
            RemoteModelManager.getInstance().deleteDownloadedModel(
                TranslateRemoteModel.Builder(language).build()).await()
        }

        fun requiredLanguages(source: String, target: String): Set<String> =
            if (source == TranslationLanguages.AUTO_SOURCE) (TranslationLanguages.detectedSources + target) - "en"
            else if (source == target) emptySet() else setOf(source, target) - "en"

        fun isReady(source: String, target: String, installed: Set<String>): Boolean =
            installed.containsAll(requiredLanguages(source, target))

        suspend fun isReady(target: String, source: String = TranslationLanguages.DEFAULT_SOURCE): Boolean {
            val required = requiredLanguages(source, target)
            return required.isEmpty() || downloadedLanguages().containsAll(required)
        }
    }
}
