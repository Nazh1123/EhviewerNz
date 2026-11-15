package com.hippo.ehviewer.gallery

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.translation.TranslationStorage
import kotlinx.coroutines.*

/** Download rows reuse a bounded in-memory count of the gallery's result manifests. */
class DownloadResultProgress(context: Context, private val onChanged: GalleryProcessingProgress.Listener) {
    private val app = context.applicationContext
    private data class Entry(val info: DownloadInfo, val counts: GalleryProcessingProgress.Counts)
    private val counts = LruCache<Long, Entry>(128)
    private val pending = mutableMapOf<Long, Job>()
    private var scope: CoroutineScope? = null
    private val changed: (GalleryResultIndex.Companion.Change) -> Unit = { event ->
        event.identity.removePrefix("gallery:").toLongOrNull()?.let { gid ->
            val loading = pending.remove(gid)
            loading?.cancel()
            val cached = counts.get(gid)
            if (cached != null) request(cached.info, refresh = false)
            else if (loading != null) onChanged.onChanged(gid)
        }
    }

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        GalleryResultIndex.listeners.add(changed)
    }

    fun stop() {
        GalleryResultIndex.listeners.remove(changed)
        scope?.cancel()
        scope = null
        pending.clear()
        counts.evictAll()
    }

    fun get(info: DownloadInfo): GalleryProcessingProgress.Counts {
        counts.get(info.gid)?.let { return it.counts }
        request(info, refresh = true)
        return empty
    }

    private fun request(info: DownloadInfo, refresh: Boolean) {
        val owner = scope ?: return
        if (info.gid in pending) return
        val job = owner.launch(start = CoroutineStart.LAZY) {
            try {
                // Coalesce writes posted by one result commit before sampling the manifest.
                yield()
                val result = withContext(Dispatchers.IO) { read(app, info, refresh) }
                val previous = counts.put(info.gid, Entry(info, result))?.counts ?: empty
                if (result != previous) onChanged.onChanged(info.gid)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("DownloadResultProgress", "Cannot read result counts for ${info.gid}", error)
            } finally {
                if (pending[info.gid] === currentCoroutineContext()[Job]) pending.remove(info.gid)
            }
        }
        pending[info.gid] = job
        job.start()
    }

    internal companion object {
        private val empty = GalleryProcessingProgress.Counts(0, 0)

        fun read(context: Context, info: DownloadInfo, refresh: Boolean = true): GalleryProcessingProgress.Counts {
            val directory = TranslationStorage.galleryDir(context, info)
            return GalleryResultIndex.open(context, "gallery:${info.gid}", info.gid, directory, false)
                .snapshot(refresh = refresh).counts()
        }
    }
}
