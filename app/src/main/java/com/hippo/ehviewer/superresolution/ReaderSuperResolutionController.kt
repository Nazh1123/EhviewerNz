package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.widget.ReaderProgressLayout
import com.hippo.lib.image.Image

/** Detachable controls. Enhancement and translation always read source-provider originals. */
class ReaderSuperResolutionController @JvmOverloads constructor(
    private val activity: AppCompatActivity,
    private val provider: GalleryProvider2,
    private val button: ImageButton,
    galleryInfo: GalleryInfo? = null,
    private val progressLayout: ReaderProgressLayout = ReaderProgressLayout(activity),
) : AutoCloseable {
    internal val session = SuperResolutionTasks.acquire(activity.applicationContext, provider, galleryInfo)
    private val panel = activity.findViewById<View>(R.id.super_resolution_progress_panel)
    private val pageBar = activity.findViewById<ProgressBar>(R.id.super_resolution_page_progress_bar)
    private val queueBar = activity.findViewById<ProgressBar>(R.id.super_resolution_queue_progress_bar)
    private var closed = false
    private val observer = object : GallerySuperResolutionSession.Reader {
        override fun changed() { if (!closed) update() }
        override fun display(page: Int, image: Image) {
            provider.setSuperResolutionCacheLimit(image.width.toLong() * image.height * 4)
            provider.setEnhancedPage(page, image)
        }
        override fun hasPage(page: Int) = provider.hasEnhancedPage(page)
        override fun displayTiles(page: Int, tiles: EnhancedTilePage) { provider.setEnhancedTiles(page, tiles) }
        override fun clear() { provider.clearEnhancedPages() }
    }
    init {
        button.setOnClickListener { toggle() }
        button.setOnLongClickListener { showMenu(session.current); true }
        panel.setOnClickListener {
            if (!closed && session.current >= 0) provider.toggleEnhancedPage(session.current)
        }
        session.attach(observer)
        update()
    }
    private fun update() {
        progressLayout.setEnhancementEnabled(session.enabled && !closed)
        button.isSelected = session.enabled
        button.alpha = if (session.enabled) 1f else 0.65f
        button.contentDescription = activity.getString(if (session.enabled) R.string.sr_auto_on else R.string.sr_auto_off)
        val message = session.message ?: if (session.enabled) session.states[session.current]?.let { activity.getString(it) } else null
        panel.visibility = if (session.enabled && !closed) View.VISIBLE else View.GONE
        panel.contentDescription = listOfNotNull(activity.getString(R.string.sr_compare), message).joinToString(". ")
        pageBar.progress = session.progress
        pageBar.contentDescription = session.activePage?.let { activity.getString(R.string.sr_page_progress, it + 1, session.progress) }
        queueBar.max = session.total.coerceAtLeast(1)
        queueBar.progress = session.completed
        queueBar.contentDescription = activity.getString(R.string.sr_queue_progress, session.completed, session.total)
    }
    private fun toggle() {
        if (closed) return
        if (session.enabled) { session.settings.automatic = false; session.disable() }
        else if (session.enable()) session.settings.automatic = true
        else showSettings()
    }
    fun onPageChanged(page: Int) {
        if (closed) return
        provider.retainEnhancedPage(page)
        session.pageChanged(page)
    }
    fun onPageReady(page: Int) { if (!closed && page == session.current) session.pageChanged(page) }
    fun onPageReload(page: Int) { if (!closed) session.invalidate(page) }
    @JvmOverloads fun pause(leavingGallery: Boolean = false) { if (!closed) session.pause(leavingGallery) }
    fun resume() { if (!closed) session.resume() }
    fun showMenu(page: Int) {
        if (closed || page < 0) return
        AlertDialog.Builder(activity).setTitle(R.string.sr_title).setItems(arrayOf(
            activity.getString(if (session.enabled) R.string.sr_auto_on else R.string.sr_auto_off),
            activity.getString(R.string.sr_current),
            activity.getString(if (session.fullGallery) R.string.sr_stop_full else R.string.sr_full_gallery),
            activity.getString(R.string.sr_compare),
            activity.getString(R.string.sr_settings),
        )) { _, which ->
            when (which) {
                0 -> toggle()
                1 -> session.retry(page)
                2 -> if (session.fullGallery) session.disable() else session.full()
                3 -> provider.toggleEnhancedPage(page)
                4 -> showSettings()
            }
        }.show()
    }
    private fun showSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true))
    }
    override fun close() = close(true)
    fun close(leavingGallery: Boolean) {
        if (closed) return
        closed = true
        progressLayout.setEnhancementEnabled(false)
        session.detach(observer, leavingGallery)
        if (!session.fullGallery && leavingGallery) SuperResolutionTasks.release(session)
        panel.visibility = View.GONE
        provider.clearEnhancedPages()
    }
}
