package com.hippo.ehviewer.translation

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hippo.ehviewer.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class TranslationModelPreparation internal constructor(private val activity: AppCompatActivity,
    private val onComplete: () -> Unit,
    private val models: TranslationModels = TranslationModels(activity.applicationContext),
    private val downloadManga: suspend ((String, Long, Long) -> Unit) -> Unit = models::download,
    private val downloadLanguage: suspend (String, String) -> Unit = { source, target -> OfflineTranslator(target, source).use { it.prepare() } },
    private val notifyDownloadNetwork: suspend () -> Unit = { notifyModelDownloadNetwork(activity.applicationContext) }) : AutoCloseable {
    private val context = activity.applicationContext
    private val settings = TranslationSettings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preparation: Job? = null
    private var progressDialog: AlertDialog? = null
    private var closed = false
    fun prepareManga() = prepare(languageOnly = false)

    fun prepareMlKit() {
        if (settings.read().backend == TranslationBackend.ML_KIT) prepare(languageOnly = true)
    }

    private fun prepare(languageOnly: Boolean) {
        if (preparation?.isActive == true) return
        val options = settings.read()
        val progress = TextView(activity).apply {
            setPadding(32, 24, 32, 24)
            setText(if (languageOnly) R.string.translation_language_download else R.string.translation_downloading)
        }
        progressDialog = AlertDialog.Builder(activity).setTitle(
            if (languageOnly) R.string.translation_prepare_mlkit else R.string.translation_prepare)
            .setView(progress).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> preparation?.cancel(); models.cancel() }.show()
        preparation = scope.launch(Dispatchers.IO) {
            try {
                TranslationRuntime.lock.withLock {
                    notifyDownloadNetwork()
                    if (languageOnly) {
                        downloadLanguage(options.mlKitSource, options.target)
                    } else downloadManga { name, bytes, total ->
                        Handler(Looper.getMainLooper()).post {
                            if (!closed) progress.text = activity.getString(R.string.translation_download_progress,
                                name, bytes / 1048576, total / 1048576)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!closed) Toast.makeText(activity,
                        if (languageOnly) R.string.translation_mlkit_ready else R.string.translation_manga_ready,
                        Toast.LENGTH_LONG).show()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (!closed) Toast.makeText(activity,
                        if (languageOnly) R.string.translation_mlkit_download_failed else R.string.translation_download_failed,
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { progressDialog?.dismiss(); progressDialog = null; if (!closed) onComplete() }
            }
        }
    }

    override fun close() {
        closed = true
        models.cancel()
        progressDialog?.dismiss()
        scope.cancel()
    }
}

/** Inform the user without restricting the network used by any model downloader. */
internal suspend fun notifyModelDownloadNetwork(context: android.content.Context) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    notifyModelDownloadNetwork(context, connectivity.getNetworkCapabilities(connectivity.activeNetwork))
}

internal suspend fun notifyModelDownloadNetwork(context: android.content.Context, capabilities: NetworkCapabilities?) {
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, R.string.translation_mobile_download, Toast.LENGTH_LONG).show()
        }
    }
}
