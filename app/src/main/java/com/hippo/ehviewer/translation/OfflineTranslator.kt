package com.hippo.ehviewer.translation

import android.content.Context
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import li.joye.yakuyomi.engine.Translator

/** Never downloads implicitly. Only model preparation may access the network. */
class OfflineTranslator(target: String) : Translator, AutoCloseable {
    private val delegate = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslationLanguages.SOURCE).setTargetLanguage(target).build())

    suspend fun prepare() = delegate.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()

    override suspend fun translate(queries: List<String>): List<String> =
        queries.map { delegate.translate(it).await() }

    override fun close() = delegate.close()

    companion object {
        suspend fun downloadedLanguages(): Set<String> = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

        suspend fun deleteLanguage(language: String) {
            RemoteModelManager.getInstance().deleteDownloadedModel(
                TranslateRemoteModel.Builder(language).build()).await()
        }

        suspend fun isReady(target: String): Boolean {
            val languages = downloadedLanguages()
            return TranslationLanguages.SOURCE in languages && (target == "en" || target in languages)
        }
    }
}
