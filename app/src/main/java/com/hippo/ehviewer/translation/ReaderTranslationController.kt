package com.hippo.ehviewer.translation

import android.util.DisplayMetrics
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import java.io.File
import kotlin.math.roundToInt

/** Detachable reader controls and progress for an application-owned translation session. */
class ReaderTranslationController @JvmOverloads constructor(
    private val activity: AppCompatActivity,
    private val provider: GalleryProvider2,
    private val button: ImageButton,
    private val galleryInfo: GalleryInfo? = null,
) : AutoCloseable {
    private val context = activity.applicationContext
    internal val session = TranslationTasks.acquire(context, provider, galleryInfo)
    private val activeOptions get() = session.activeOptions
    private val states get() = session.states
    private val timings get() = session.timings
    private val progressPanel = activity.findViewById<View>(R.id.translation_progress_panel)
    private val pageProgressBar = progressPanel?.findViewById<ProgressBar>(R.id.translation_page_progress_bar)
    private val progressBar = progressPanel?.findViewById<ProgressBar>(R.id.translation_progress_bar)
    private val resultStatus = progressPanel?.findViewById<TextView>(R.id.translation_result_status)
    private val pageProgress get() = session.pageProgress
    private var quickSettingsVisible = false
    private var quickSettingsBottom = 0f
    private val current get() = session.current
    private val enabled get() = session.enabled
    private val activeRequest get() = session.activePage
    private var closed = false
    private val observer = object : GalleryTranslationSession.Reader {
        override fun changed() {
            if (!closed) {
                if (button.isSelected != enabled) updateUi()
                else {
                    updatePageProgress()
                    updateQueueProgress()
                }
            }
        }
        override fun hasTranslatedPage(page: Int) = provider.hasTranslatedPage(page)
        override fun display(page: Int, image: Image) = provider.setTranslatedPage(page, image)
        override fun clearTranslations() = provider.clearTranslatedPages()
    }

    init {
        session.attach(observer)
        button.setImageResource(R.drawable.v_translate_x24)
        button.setOnClickListener { if (enabled) disable() else enable() }
        button.setOnLongClickListener { showMenu(current); true }
        progressPanel?.let { panel ->
            panel.setOnClickListener {
                if (enabled && !closed) provider.toggleTranslatedPage(current)
            }
            val margins = panel.layoutParams as FrameLayout.LayoutParams
            val left = margins.leftMargin
            val top = margins.topMargin
            val right = margins.rightMargin
            val screen = DisplayMetrics()
            activity.windowManager.defaultDisplay.getRealMetrics(screen)
            margins.width = (minOf(screen.widthPixels, screen.heightPixels) * 0.35f).roundToInt()
            panel.layoutParams = margins
            ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val params = view.layoutParams as FrameLayout.LayoutParams
                params.leftMargin = left + safe.left
                params.topMargin = maxOf(top, safe.top)
                params.rightMargin = right + safe.right
                view.layoutParams = params
                updateProgressPosition()
                insets
            }
            ViewCompat.requestApplyInsets(panel)
        }
        updateUi()
    }

    fun onPageChanged(page: Int) {
        if (!closed) session.onPageChanged(page)
    }

    fun onPageReady(page: Int) {
        if (!closed && page in pendingPages()) {
            updatePageProgress()
            updateQueueProgress()
            session.enqueue(page, false)
        }
    }

    fun onQuickSettingsPositionChanged(visible: Boolean, bottom: Float) {
        if (closed) return
        quickSettingsVisible = visible
        quickSettingsBottom = bottom
        updateProgressPosition()
    }

    private fun updateProgressPosition() {
        progressPanel?.let { panel ->
            val top = (panel.layoutParams as FrameLayout.LayoutParams).topMargin
            val gap = 8f * activity.resources.displayMetrics.density
            panel.translationY = if (quickSettingsVisible) maxOf(0f, quickSettingsBottom + gap - top) else 0f
        }
    }

    fun onPageReload(page: Int) {
        // A refreshed source must never receive a result from its previous bytes.
        disable()
        session.invalidateResult(page)
        states.remove(page)
        timings.remove(page)
        provider.removeTranslatedPage(page)
    }

    @JvmOverloads fun pause(leavingGallery: Boolean = false) {
        if (!closed) {
            if (leavingGallery) session.detach(observer) else session.setBrowsing(false)
        }
    }

    fun resume() {
        if (!closed) session.setBrowsing(true)
    }

    private fun enable() {
        if (closed) return
        if (!session.enable()) { showSettings(); return }
        updateUi()
    }

    private fun disable() {
        session.disable()
        updateUi()
    }

    private fun pendingPages() = session.pendingPages()
    private fun isPageSettled(page: Int) = session.isPageSettled(page)

    private fun updateUi() {
        if (button.isSelected != enabled) provider.setShowTranslations(enabled)
        // textColorSecondary is a ColorStateList in reader themes, not a raw color.
        button.imageTintList = android.content.res.ColorStateList.valueOf(
            AttrResources.getAttrColor(activity,
                if (enabled) R.attr.drawableColorPrimary else android.R.attr.textColorSecondary))
        button.isSelected = enabled
        button.contentDescription = activity.getString(R.string.translation_accessibility,
            activity.getString(if (enabled) R.string.translation_enabled else R.string.translation_disabled))
        button.alpha = if (enabled) 1f else 0.65f
        updatePageProgress()
        updateQueueProgress()
    }

    private fun updatePageProgress() {
        resultStatus?.apply {
            val message = when (states[current]) {
                R.string.translation_partial -> R.string.translation_partial_short
                R.string.translation_models_required -> R.string.translation_models_required_short
                else -> null
            }
            visibility = if (message == null) View.GONE else View.VISIBLE
            if (message != null) setText(message)
        }
        val pages = pendingPages()
        progressPanel?.visibility = if (enabled && !closed && pages.isNotEmpty()) View.VISIBLE else View.GONE
        if (!enabled || closed || pages.isEmpty()) return
        val active = activeRequest
        val page = active
            ?: pages.firstOrNull { !isPageSettled(it) } ?: current
        // Between requests the next pending page keeps the distance indicator's meaning stable.
        val distance = current - page
        val showsDistance = distance in 1..2
        val maximum = if (showsDistance) 3 else 1000
        val value = if (showsDistance) distance else if (active != null) {
            // A running request owns its progress; browsing or restoring reader images cannot reset it.
            if (states[page] in listOf(R.string.translation_done, R.string.translation_partial, R.string.translation_no_text,
                    R.string.translation_animation)) 1000 else pageProgress.value
        } else if (isPageSettled(page) && states[page] !in listOf(R.string.translation_failed, R.string.translation_models_required)) 1000 else 0
        val direction = if (showsDistance) -1f else 1f
        val description = if (showsDistance)
            activity.getString(R.string.translation_progress_page_distance, distance)
        else activity.getString(R.string.translation_current_progress, page + 1, value / 10)
        pageProgressBar?.apply {
            if (max != maximum) max = maximum
            if (progress != value) setProgress(value, false)
            if (scaleX != direction) scaleX = direction
            if (contentDescription != description) contentDescription = description
        }
    }

    private fun updateQueueProgress() {
        if (!enabled || closed) return
        // Count a fixed reading window. Advancing the worker must not shrink the range or reset the bar.
        val pages = session.queuePages()
        if (pages.isEmpty()) return
        val settled = session.queueCompleted()
        progressBar?.apply {
            if (max != pages.size) max = pages.size
            if (progress != settled) setProgress(settled, false)
            val description = activity.getString(R.string.translation_queue_progress, settled, pages.size)
            if (contentDescription != description) contentDescription = description
        }
    }

    fun showMenu(page: Int) {
        if (closed) return
        val partial = states[page] == R.string.translation_partial
        val needsModels = states[page] == R.string.translation_models_required
        val state = states[page]?.let { activity.getString(it) } ?: activity.getString(R.string.translation_original)
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.translation_page_status, page + 1, state))
            .setItems((listOf(
                activity.getString(if (enabled) R.string.translation_hide else R.string.translation_show),
                activity.getString(if (partial) R.string.translation_retry_missing else R.string.translation_retry),
                activity.getString(if (session.fullGallery) R.string.translation_stop_full else R.string.translation_full_gallery),
            ) + (if (BuildConfig.DEBUG) listOf(activity.getString(R.string.translation_timings)) else emptyList()) +
                (if (needsModels) listOf(activity.getString(R.string.translation_settings)) else emptyList()))
                .toTypedArray()) { _, which ->
                if (needsModels && which == (if (BuildConfig.DEBUG) 4 else 3)) {
                    showSettings()
                    return@setItems
                }
                when (which) {
                    0 -> if (enabled) disable() else enable()
                    1 -> {
                        if (partial) {
                            enable()
                            session.enqueue(page, true, retryMissing = true)
                        } else {
                            disable()
                            provider.removeTranslatedPage(page)
                            timings.remove(page)
                            enable()
                            session.enqueue(page, true)
                        }
                    }
                    3 -> {
                        val stats = timings[page]
                        val message = if (stats == null) activity.getString(R.string.translation_timings_unavailable)
                        else activity.getString(R.string.translation_timings_report,
                            stats.wallMs / 1000.0, stats.detectMs / 1000.0, stats.ocrMs / 1000.0,
                            stats.translateMs / 1000.0, stats.inpaintMs / 1000.0, stats.renderMs / 1000.0)
                        AlertDialog.Builder(activity).setTitle(R.string.translation_timings)
                            .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                    }
                    2 -> {
                        if (session.fullGallery) disable()
                        else {
                            enable()
                            if (enabled) session.translateFullGallery()
                        }
                    }
                }
            }.show()
    }

    private fun showSettings() {
        disable()
        activity.startActivity(android.content.Intent(activity, com.hippo.ehviewer.ui.SettingsActivity::class.java)
            .putExtra(com.hippo.ehviewer.ui.SettingsActivity.EXTRA_TRANSLATION, true))
    }

    override fun close() = close(true)

    fun close(leavingGallery: Boolean) {
        if (closed) return
        closed = true
        session.detach(observer, leavingGallery)
        if (!enabled && session.reader == null) TranslationTasks.release(session)
        progressPanel?.visibility = View.GONE
    }

    companion object {
        internal fun decodeBounded(file: File) = GalleryTranslationSession.decodeBounded(file)
    }
}
