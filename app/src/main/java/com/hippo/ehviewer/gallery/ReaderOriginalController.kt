package com.hippo.ehviewer.gallery

import android.graphics.drawable.BitmapDrawable
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.widget.ReaderProgressLayout
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Reader-owned download queue and display selection for indexed originals. */
class ReaderOriginalController(
    private val activity: GalleryActivity,
    private val provider: GalleryProvider2,
    private val gallery: GalleryInfo,
    private val results: ReaderResultIndex,
    private val layout: ReaderProgressLayout,
) : AutoCloseable {
    private val app = activity.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val panel = activity.findViewById<View>(R.id.original_progress_panel)
    private val icon = activity.findViewById<View>(R.id.original_progress_icon)
    private val pageBar = activity.findViewById<ProgressBar>(R.id.original_page_progress_bar)
    private val queueBar = activity.findViewById<ProgressBar>(R.id.original_queue_progress_bar)
    private val waiting = ArrayDeque<Int>()
    private var queueJob: Job? = null
    private var displayJob: Job? = null
    private var transfer: OriginalImageDownload? = null
    private var currentPage = -1
    private var currentSourcePage = -1
    private var activePage = -1
    private var finished = 0
    private var total = 0
    private var available = false
    private val resultChanged = Runnable { updatePanel() }
    private val changed: (GalleryResultIndex.Companion.Change) -> Unit = { event ->
        if (event.identity == "gallery:${gallery.gid}" && event.kind == GalleryResultIndex.Kind.ORIGINAL &&
            currentSourcePage >= 0 && event.source == pageName(currentSourcePage)) refreshDisplay(reloadImage = true)
    }

    init {
        GalleryResultIndex.listeners.add(changed)
        provider.addResultStateListener(resultChanged)
        controllers.add(this)
        panel.setOnClickListener {
            if (available) toggle(currentPage) else openMenu(currentPage, false)
        }
        panel.setOnLongClickListener { openMenu(currentPage, false); true }
    }

    fun onPageChanged(page: Int) {
        val source = if (page in 0 until provider.size()) provider.getSourcePage(page) else -1
        if (currentPage == page && currentSourcePage == source) return
        currentPage = page
        currentSourcePage = source
        available = false
        provider.setOriginalResult(-1, null, null)
        updatePanel()
        refreshDisplay()
    }

    private fun pageName(source: Int) = SpiderDen.generateImageFilename(source, "")

    fun reloadDisplay() = refreshDisplay()

    fun showMenu(page: Int) = openMenu(page, true)

    private fun openMenu(page: Int, requestDownload: Boolean) {
        if (page !in 0 until provider.size()) return
        val source = provider.getSourcePage(page)
        scope.launch {
            try {
                val saved = results.page(page).original?.file
                if (activity.isFinishing || activity.isDestroyed || page !in 0 until provider.size() ||
                    provider.getSourcePage(page) != source) return@launch
                val queued = source == activePage || source in waiting
                if (requestDownload && !queued) {
                    requestDownload(source, saved != null)
                    return@launch
                }
                val labels = mutableListOf<Int>()
                val actions = mutableListOf<() -> Unit>()
                if (saved != null) {
                    labels += comparisonLabel(page)
                    actions += { toggle(page) }
                }
                if (!queued) {
                    labels += if (saved == null) R.string.original_enqueue else R.string.original_redownload
                    actions.add { scope.launch { requestDownload(source, saved != null) } }
                }
                if (activePage >= 0) {
                    labels += R.string.original_cancel_queue
                    actions += { cancelQueue() }
                }
                if (labels.isNotEmpty()) AlertDialog.Builder(activity)
                    .setTitle(R.string.original_title)
                    .setItems(labels.map { activity.getText(it) }.toTypedArray()) { _, which -> actions[which]() }
                    .show()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                reportFailure(source, error)
            }
        }
    }

    private suspend fun requestDownload(source: Int, saved: Boolean) {
        try {
            if (saved && withContext(Dispatchers.IO) { resultDirectory() } != null) confirmOverwrite(source)
            else enqueue(source)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            reportFailure(source, error)
        }
    }

    private fun resultDirectory(): UniFile? {
        val info = EhApplication.getDownloadManager(app).getDownloadInfo(gallery.gid)
        return TranslationStorage.resultDirectory(app, info, provider, true)
    }

    private fun confirmOverwrite(source: Int) {
        AlertDialog.Builder(activity).setMessage(R.string.original_overwrite_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.original_overwrite) { _, _ -> enqueue(source) }.show()
    }

    private fun toggle(page: Int) {
        if (page in 0 until provider.size()) provider.toggleOriginalResult(page)
    }

    private fun comparisonLabel(page: Int) = when {
        !provider.isOriginalResultChosen(page) -> R.string.original_show_result
        provider.hasDisplayableEnhancedPage(page) -> R.string.reader_show_enhanced_result
        else -> R.string.original_show_source
    }

    private fun refreshDisplay(reloadImage: Boolean = false) {
        displayJob?.cancel()
        val page = currentPage
        val source = currentSourcePage
        if (source < 0) return
        displayJob = scope.launch {
            var decoded: Image? = null
            var saved: ReaderResultIndex.Page? = null
            try {
                saved = results.page(page)
                val file = saved.original?.file
                if (file == null) {
                    available = false
                    provider.setOriginalResult(-1, null, null)
                    updatePanel()
                    return@launch
                }
                if (reloadImage || provider.getOriginalResultFile(page)?.uri != file.uri) {
                    withContext(Dispatchers.IO) { decoded = decode(file, page) }
                    val image = checkNotNull(decoded) { activity.getString(R.string.original_display_failed) }
                    available = true
                    provider.setOriginalResult(page, image, file)
                    decoded = null
                }
                updatePanel()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                saved?.let { entry -> entry.original?.let { results.discardUnreadable(entry, GalleryResultIndex.Kind.ORIGINAL, it) } }
                displayFailed(source, error)
            } catch (error: OutOfMemoryError) {
                displayFailed(source, error)
            } finally {
                decoded?.recycle()
            }
        }
    }

    private fun decode(file: UniFile, page: Int): Image? {
        file.openInputStream().use { stream ->
            if (stream is FileInputStream) Image.decode(stream, false, provider.getAnimatedWebpDecodeMode(page))?.let { return it }
        }
        return file.openInputStream().use { Image.decode(BitmapDrawable.createFromStream(it, null), false) }
    }

    private fun displayFailed(source: Int, error: Throwable) {
        Log.w(TAG, "Cannot display original page ${source + 1}", error)
        available = false
        provider.setOriginalResult(-1, null, null)
        updatePanel()
        Toast.makeText(activity, R.string.original_display_failed, Toast.LENGTH_SHORT).show()
    }

    private fun enqueue(source: Int) {
        if (source == activePage || source in waiting ||
            queueJob?.let { it.isCancelled && !it.isCompleted } == true) return
        waiting.addLast(source)
        total++
        updatePanel()
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            try {
                while (waiting.isNotEmpty()) {
                    val page = waiting.removeFirst()
                    activePage = page
                    pageBar.isIndeterminate = true
                    pageBar.contentDescription = activity.getString(R.string.original_resolving, page + 1)
                    updatePanel()
                    val download = OriginalImageDownload(app)
                    transfer = download
                    var staged: File? = null
                    try {
                        val token = SpiderQueen.getCachedPageToken(gallery.gid, page)
                        val exportName = withContext(Dispatchers.IO) {
                            val dir = resultDirectory()
                            val job = currentCoroutineContext()
                            val write: (java.io.OutputStream) -> Unit = { output ->
                                download.transfer(gallery, page, token, output) { received, length ->
                                    job.ensureActive()
                                    scope.launch { if (transfer === download) updatePageProgress(page, received, length) }
                                }
                                job.ensureActive()
                            }
                            if (dir == null) {
                                val file = File.createTempFile("reader-original-", ".part", app.cacheDir)
                                staged = file
                                file.outputStream().use(write)
                                "${gallery.gid}-${pageName(page)}_orn.${GalleryResultIndex.originalExtension(file)}"
                            } else {
                                val index = GalleryResultIndex.open(app, "gallery:${gallery.gid}", gallery.gid, dir, true)
                                index.writeOriginal(GalleryResultIndex.Source(pageName(page)), write)
                                null
                            }
                        }
                        if (exportName != null) {
                            suspendCancellableCoroutine { done ->
                                val accepted = activity.launchImageSaveTo(checkNotNull(staged), exportName) {
                                    if (done.isActive) done.resume(Unit)
                                }
                                if (accepted) staged = null // The picker result now owns cleanup.
                                else done.resume(Unit)
                            }
                        } else {
                            Toast.makeText(activity, activity.getString(R.string.original_saved, page + 1),
                                Toast.LENGTH_SHORT).show()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        ensureActive()
                        reportFailure(page, error)
                    } finally {
                        staged?.delete()
                        transfer = null
                        download.close()
                    }
                    finished++
                    updatePanel()
                }
            } finally {
                activePage = -1
                finished = 0
                total = 0
                waiting.clear()
                updatePanel()
            }
        }
    }

    private fun updatePageProgress(page: Int, received: Long, length: Long) {
        pageBar.isIndeterminate = length <= 0
        val receivedText = Formatter.formatShortFileSize(activity, received)
        if (length > 0) {
            pageBar.progress = (received.toDouble() / length * pageBar.max).toInt().coerceIn(0, pageBar.max)
            pageBar.contentDescription = activity.getString(R.string.original_transfer_progress, page + 1,
                receivedText, Formatter.formatShortFileSize(activity, length))
        } else pageBar.contentDescription = activity.getString(R.string.original_transfer_unknown, page + 1, receivedText)
    }

    private fun updatePanel() {
        layout.setOriginalState(activePage >= 0 || waiting.isNotEmpty(), available)
        queueBar.max = total.coerceAtLeast(1)
        queueBar.progress = finished
        queueBar.contentDescription = activity.getString(R.string.original_queue_progress, finished, total)
        val displaying = provider.getDisplayedOriginalFile(currentPage) != null
        val selected = displaying || provider.isShowingEnhancedFromOriginal(currentPage)
        icon.alpha = if (available && !selected) .5f else 1f
        icon.isSelected = selected
        panel.contentDescription = activity.getString(
            if (available) comparisonLabel(currentPage) else R.string.original_title)
    }

    private fun reportFailure(page: Int, error: Exception) {
        Log.w(TAG, "Cannot download original page ${page + 1}", error)
        Toast.makeText(activity, activity.getString(R.string.original_failed, page + 1,
            error.localizedMessage ?: activity.getString(R.string.error_unknown)), Toast.LENGTH_LONG).show()
    }

    private fun cancelQueue() {
        waiting.clear()
        queueJob?.cancel()
        transfer?.close()
    }

    override fun close() {
        controllers.remove(this)
        GalleryResultIndex.listeners.remove(changed)
        provider.removeResultStateListener(resultChanged)
        cancelQueue()
        scope.cancel()
        provider.setOriginalResult(-1, null, null)
    }

    companion object {
        private const val TAG = "ReaderOriginal"
        private val controllers = mutableSetOf<ReaderOriginalController>()

        internal fun cancelDownloads(galleryIds: Set<Long>) {
            controllers.filter { it.gallery.gid in galleryIds }.forEach { it.cancelQueue() }
        }
    }
}
