package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.ehviewer.translation.TranslationTasks
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/** Worker-only bridge: wait for in-flight result writers before pruning a changed range. */
object RangeDownloadProcessing {
    @JvmStatic fun stop(gid: Long, directory: UniFile?) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val identities = directory?.let { setOf("dir:${it.uri}") }.orEmpty()
            TranslationTasks.stopForResultDeletion(setOf(gid), identities)
            SuperResolutionTasks.stopForResultDeletion(setOf(gid), identities)
        }
    }
    @JvmStatic fun prune(context: Context, gid: Long, directory: UniFile?, pages: IntArray) {
        GalleryResultIndex.open(context, "gallery:$gid", gid, directory, false).reconcile(
            pages.map { GalleryResultIndex.Source(String.format(Locale.US, "%08d", it + 1)) })
    }
}
