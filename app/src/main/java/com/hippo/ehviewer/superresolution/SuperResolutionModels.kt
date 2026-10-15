package com.hippo.ehviewer.superresolution

import android.content.Context
import com.hippo.ehviewer.translation.TranslationModels
import com.hippo.ehviewer.translation.awaitModelResponse
import com.hippo.ehviewer.translation.engine.NcnnModelFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Uses the translation model store's checksums, atomic publication and SAF reference leases. */
class SuperResolutionModels internal constructor(
    context: Context,
    manifestOverride: String?,
    private val downloadBaseUrl: String?,
) {
    constructor(context: Context) : this(context, null, null)

    private val manifest = manifestOverride ?: context.assets.open("super-resolution-models.json").bufferedReader().use { it.readText() }
    val store = TranslationModels(context, manifest, "super-resolution-models", managesTranslationRuntime = false)

    suspend fun files(): NcnnModelFiles = store.verifiedSources().let {
        NcnnModelFiles(it.getValue("scale2.0x_model.param"), it.getValue("scale2.0x_model.bin"))
    }

    suspend fun download(progress: (String, Long, Long) -> Unit) {
        val revision = JSONObject(manifest).getString("revision")
        val base = downloadBaseUrl ?: "https://raw.githubusercontent.com/nihui/waifu2x-ncnn-vulkan/$revision/models/models-upconv_7_anime_style_art_rgb/"
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).build()
        try {
            for (name in store.requiredNames) {
                currentCoroutineContext().ensureActive()
                val call = client.newCall(Request.Builder().url(base + name).build())
                coroutineScope {
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.awaitModelResponse().use { response ->
                            check(response.isSuccessful) { "Model download HTTP ${response.code()}" }
                            store.installFile(name, requireNotNull(response.body()).byteStream(), progress)
                        }
                    } finally { cancellation.cancel() }
                }
            }
        } finally {
            client.dispatcher().cancelAll()
            client.connectionPool().evictAll()
            client.dispatcher().executorService().shutdown()
        }
    }

    companion object {
        /** Also excludes import/delete while an inference owns a model read lease. */
        val lock = Mutex()
        const val ENABLED = "reader_super_resolution_enabled"
        const val BACKEND = "reader_super_resolution_backend"
        const val DEFAULT_BACKEND = "auto_fast"
    }
}
