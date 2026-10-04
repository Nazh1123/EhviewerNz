package com.hippo.ehviewer.translation

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
            if (pending.isActive) pending.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            pending.resume(response, onCancellation = { _, unclaimed, _ -> unclaimed.close() })
        }
    })
}
