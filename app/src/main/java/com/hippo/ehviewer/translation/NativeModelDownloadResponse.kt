package com.hippo.ehviewer.translation

import com.hippo.ehviewer.R
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitModelResponse(): Response = suspendCancellableCoroutine { pending ->
    pending.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (pending.isActive) pending.resumeWithException(
                TranslationRequestFailure(R.string.translation_error_network, "Model download connection failed").apply { initCause(e) }
            )
        }
        override fun onResponse(call: Call, response: Response) {
            pending.resume(response, onCancellation = { _, unclaimed, _ -> unclaimed.close() })
        }
    })
}

internal fun Response.requireModelDownloadSuccess() {
    if (!isSuccessful)
        throw TranslationModelFailure(R.string.translation_error_http, "Model download HTTP ${code()}", code())
}
