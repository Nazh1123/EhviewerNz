package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.translation.TranslationStorage
import kotlinx.coroutines.*

/** Main-thread-owned reading session. Metadata is gallery-wide; file validation is lazy and shared. */
class ReaderResultIndex(context: Context, private val provider: GalleryProvider2,
                        private val info: GalleryInfo?) : AutoCloseable {
    internal data class Page(val source: GalleryResultIndex.Source?, val translation: GalleryResultIndex.Hit?,
                             val enhanced: GalleryResultIndex.Hit?) {
        val flags: Int get() = (if (translation?.state == "complete") TRANSLATION else 0) or
            (if (enhanced?.state == "complete" && enhanced.scale in listOf(2, 4, 8)) SUPER_RESOLUTION else 0)
    }
    private data class State(val index: GalleryResultIndex, val snapshot: GalleryResultIndex.Snapshot)
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Deferred<State>? = null
    private var ready: State? = null
    private val changedSources = mutableSetOf<String>()
    private val pages = mutableMapOf<Int, Page>()
    private val pending = mutableMapOf<Int, Deferred<Page>>()
    private val previewRequests = linkedSetOf<Int>()
    private var previewJob: Job? = null
    private val listeners = mutableSetOf<Runnable>()
    private var refreshFromDisk = true
    private var closed = false
    private val changed: (GalleryResultIndex.Companion.Change) -> Unit = { event ->
        if (event.identity == GalleryResultIndex.identity(provider, info)) {
            changedSources.add(event.source)
            pages.entries.removeAll { it.value.source?.name.let { name -> name == null || name == event.source } }
            invalidate(false)
        }
    }

    init {
        GalleryResultIndex.listeners.add(changed)
        warm()
    }
    fun addListener(listener: Runnable) { listeners.add(listener) }
    fun removeListener(listener: Runnable) { listeners.remove(listener) }
    fun flags(page: Int): Int = pages[page]?.flags ?: 0
    fun request(page: Int) {
        if (closed || page !in 0 until provider.size() || pages.containsKey(page) || pending.containsKey(page)) return
        previewRequests.add(page)
        // Fast scrubbing must not queue file queries for every page passed on the way.
        while (previewRequests.size > 8) previewRequests.remove(previewRequests.first())
        if (previewJob?.isActive == true) return
        previewJob = scope.launch {
            yield()
            while (previewRequests.isNotEmpty()) {
                val next = previewRequests.first()
                previewRequests.remove(next)
                try { page(next) }
                catch (cancel: CancellationException) { ensureActive() }
                catch (error: Exception) { android.util.Log.w("ReaderResultIndex", "Cannot validate page", error) }
            }
        }
    }
    /** Resume refreshes only this gallery's manifests, retaining the session rather than evicting all galleries. */
    fun reload() {
        if (closed) return
        pages.clear()
        invalidate(true)
    }
    private fun invalidate(fromDisk: Boolean) {
        refreshFromDisk = refreshFromDisk || fromDisk
        task?.cancel(); task = null
        pending.values.forEach { it.cancel() }; pending.clear()
        notifyChanged()
        warm()
    }
    private fun warm() {
        scope.launch {
            try { state().await() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { android.util.Log.w("ReaderResultIndex", "Cannot read manifest", error) }
        }
    }
    private fun state(): Deferred<State> = task ?: scope.async {
        // Coalesce a batch of publication/deletion events before copying the manifests.
        yield()
        val refresh = refreshFromDisk
        val previous = ready
        val sources = changedSources.toSet()
        val result = withContext(Dispatchers.IO) {
            val index = if (previous != null && !refresh) previous.index else {
                val download = info?.let { EhApplication.getDownloadManager(app).getDownloadInfo(it.gid) }
                val directory = TranslationStorage.resultDirectory(app, download, provider, false)
                GalleryResultIndex.open(app, GalleryResultIndex.identity(provider, info), info?.gid, directory, false)
            }
            val snapshot = if (previous == null || refresh) index.snapshot(refresh) else {
                val updates = index.snapshot(names = sources)
                GalleryResultIndex.Snapshot(previous.snapshot.pages.toMutableMap().apply {
                    sources.forEach { remove(it) }
                    putAll(updates.pages)
                })
            }
            State(index, snapshot)
        }
        ready = result
        changedSources.removeAll(sources)
        refreshFromDisk = false
        result
    }.also { task = it }

    internal suspend fun index(): GalleryResultIndex = state().await().index
    internal suspend fun hasResults(): Boolean = state().await().snapshot.pages.isNotEmpty()
    internal suspend fun page(position: Int): Page {
        while (true) {
            if (closed) throw CancellationException("Reader result session closed")
            pages[position]?.let { return it }
            val work = pending[position] ?: scope.async {
                val state = state().await()
                val result = withContext(Dispatchers.IO) {
                    if (state.snapshot.pages.isEmpty()) Page(null, null, null)
                    else {
                        val source = GalleryResultIndex.source(provider, position, info?.gid)
                        fun find(kind: GalleryResultIndex.Kind) = if (state.snapshot.contains(source, kind))
                            state.index.find(source, kind) else null
                        Page(source, find(GalleryResultIndex.Kind.TL), find(GalleryResultIndex.Kind.SR))
                    }
                }
                pages[position] = result
                notifyChanged()
                result
            }.also { pending[position] = it }
            try { return work.await() }
            catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() }
            finally { if (pending[position] === work && work.isCompleted) pending.remove(position) }
        }
    }
    private fun notifyChanged() { listeners.toList().forEach { it.run() } }
    override fun close() {
        closed = true
        GalleryResultIndex.listeners.remove(changed)
        listeners.clear()
        scope.cancel()
        pages.clear(); pending.clear(); previewRequests.clear(); task = null; ready = null
    }
    companion object {
        const val TRANSLATION = 1
        const val SUPER_RESOLUTION = 2
    }
}
