package com.hippo.ehviewer.superresolution

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.translation.engine.SuperResolution
import com.hippo.ehviewer.translation.engine.SuperResolutionBackend
import com.hippo.ehviewer.translation.engine.SuperResolutionPipeline
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.lib.image.Image
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Foreground, current-page-only work; translation continues to read the original source. */
class ReaderSuperResolutionController(
    private val activity: AppCompatActivity,
    private val provider: GalleryProvider2,
    private val button: ImageButton,
) : AutoCloseable {
    private val context = activity.applicationContext
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val models = SuperResolutionModels(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val status = activity.findViewById<TextView>(R.id.super_resolution_progress)
    private var job: Job? = null
    private var current = -1
    private var generation = 0
    private var paused = false
    private var closed = false
    private var failedPage = -1
    private var enabled = preferences.getBoolean(SuperResolutionModels.ENABLED, false)
    private var session = SuperResolutionSessionCache<SuperResolution>()

    init {
        button.setOnClickListener { toggle() }
        button.setOnLongClickListener { showMenu(current); true }
        status.setOnClickListener { showMenu(current) }
        updateButton()
    }

    private fun updateButton() {
        button.isSelected = enabled
        button.alpha = if (enabled) 1f else 0.65f
        button.contentDescription = activity.getString(if (enabled) R.string.sr_auto_on else R.string.sr_auto_off)
    }

    private fun cancel() { generation++; job?.cancel(); job = null }

    fun onPageChanged(page: Int) {
        if (closed || current == page) return
        cancel()
        current = page
        provider.retainEnhancedPage(page)
        failedPage = -1
        status.visibility = View.GONE
        if (enabled) enqueue(page)
    }

    fun onPageReady(page: Int) {
        if (!closed && page == current && enabled && failedPage != page) enqueue(page)
    }

    fun onPageReload(page: Int) {
        cancel()
        failedPage = -1
        provider.removeEnhancedPage(page)
        status.visibility = View.GONE
    }

    fun pause() { paused = true; cancel(); retireSession(); status.visibility = View.GONE }

    private fun retireSession() {
        val retired = session
        session = SuperResolutionSessionCache()
        // Disposal must survive activity-scope cancellation, and wait for the
        // canceled native tile on IO. Never block the UI or close a newer session.
        scope.launch(NonCancellable + Dispatchers.IO) {
            SuperResolutionModels.lock.withLock { retired.close() }
        }
    }
    fun resume() {
        if (closed) return
        paused = false
        val setting = preferences.getBoolean(SuperResolutionModels.ENABLED, false)
        if (enabled && !setting) provider.clearEnhancedPages()
        enabled = setting
        failedPage = -1
        updateButton()
        if (enabled) enqueue(current)
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun toggle() {
        if (closed) return
        if (!enabled && !models.store.ready()) { showSettings(); return }
        enabled = !enabled
        preferences.edit().putBoolean(SuperResolutionModels.ENABLED, enabled).apply()
        cancel()
        if (!enabled) retireSession()
        failedPage = -1
        provider.clearEnhancedPages()
        status.visibility = View.GONE
        updateButton()
        if (enabled) enqueue(current)
    }

    fun showMenu(page: Int) {
        if (closed || page < 0) return
        val items = arrayOf(
            activity.getString(if (enabled) R.string.sr_auto_on else R.string.sr_auto_off),
            activity.getString(R.string.sr_current),
            activity.getString(R.string.sr_compare),
            activity.getString(R.string.sr_settings))
        AlertDialog.Builder(activity).setTitle(R.string.sr_title).setItems(items) { _, which ->
            when (which) {
                0 -> toggle()
                1 -> { cancel(); failedPage = -1; provider.removeEnhancedPage(page); enqueue(page, manual = true) }
                2 -> provider.toggleEnhancedPage(page)
                3 -> showSettings()
            }
        }.show()
    }

    private fun showSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SUPER_RESOLUTION, true))
    }

    private fun enqueue(page: Int, manual: Boolean = false) {
        if (closed || paused || page < 0 || (!manual && page != current) || job?.isActive == true) return
        if (provider.hasEnhancedPage(page)) { status.visibility = View.GONE; return }
        if (!models.store.ready()) { showStatus(activity.getString(R.string.sr_models_required)); return }
        val admittedBudget = SuperResolutionMemoryBudget.current(context)
        when (provider.getSuperResolutionStatus(page, admittedBudget)) {
            -1 -> { showStatus(activity.getString(R.string.sr_animation)); return }
            -2 -> { showBudgetExceeded(page, admittedBudget); return }
            0 -> return // onPageReady will retry when the reader's decode has arrived.
        }
        val ticket = generation
        val admittedSession = session
        job = scope.launch {
            var output: Bitmap? = null
            try {
                delay(250) // Fast navigation should not admit inference on transient pages.
                showStatus(activity.getString(R.string.sr_preparing))
                withContext(Dispatchers.IO) {
                    SuperResolutionModels.lock.withLock {
                        ensureActive()
                        val budget = minOf(admittedBudget, SuperResolutionMemoryBudget.current(context))
                        if (provider.getSuperResolutionStatus(page, budget) == -2) throw BudgetExceeded(budget)
                        provider.setSuperResolutionCacheLimit(budget)
                        val source = provider.getSuperResolutionInput(page, budget) ?: return@withLock
                        try {
                            val mode = preferences.getString(SuperResolutionModels.BACKEND,
                                SuperResolutionModels.DEFAULT_BACKEND)
                            val backend = if (mode == "cpu")
                                SuperResolutionBackend.CPU else SuperResolutionBackend.AUTO
                            val pipeline = if (mode == "auto_fast") SuperResolutionPipeline.GPU_FP16
                                else SuperResolutionPipeline.GPU_FP32
                            val verifiedFiles = models.files() // Revalidate external model leases on every page.
                            val engine = admittedSession.acquire("$backend:$pipeline") {
                                SuperResolution(verifiedFiles, backend, pipeline)
                            }
                            try {
                                output = engine.upscale(source, budget) { done, total ->
                                    activity.runOnUiThread {
                                        if (!closed && generation == ticket && (manual || current == page) && !paused)
                                            showStatus(activity.getString(when {
                                                engine.usingVulkan -> R.string.sr_progress_gpu
                                                engine.fellBack -> R.string.sr_progress_fallback
                                                else -> R.string.sr_progress
                                            }, done * 100 / total))
                                    }
                                }
                            } catch (error: CancellationException) {
                                throw error // Keep weights/shaders for the next foreground page.
                            } catch (error: Throwable) {
                                admittedSession.clear()
                                throw error
                            }
                        } finally { source.recycle() }
                    }
                }
                ensureActive()
                if (generation == ticket && (manual || current == page) && !closed) {
                    output?.let { bitmap ->
                        val image = checkNotNull(Image.create(bitmap)) { "Cannot create enhanced reader image" }
                        provider.setEnhancedPage(page, image)
                        output = null // Cache now owns the bitmap; GL callbacks take their own leases.
                    }
                    status.visibility = View.GONE
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: BudgetExceeded) {
                if (!closed && generation == ticket) {
                    failedPage = page
                    showBudgetExceeded(page, error.budget)
                }
            } catch (error: Exception) {
                if (!closed && generation == ticket) {
                    failedPage = page
                    showStatus(activity.getString(R.string.sr_failed, error.message.orEmpty()))
                }
            } catch (error: LinkageError) {
                if (!closed && generation == ticket) {
                    failedPage = page
                    showStatus(activity.getString(R.string.sr_failed, error.message.orEmpty()))
                }
            } catch (error: OutOfMemoryError) {
                if (!closed && generation == ticket) {
                    failedPage = page
                    showStatus(activity.getString(R.string.sr_memory_unavailable))
                }
            } finally {
                output?.recycle()
            }
        }
    }

    private class BudgetExceeded(val budget: Long) : RuntimeException()

    private fun showBudgetExceeded(page: Int, budget: Long) {
        val size = provider.getSuperResolutionDimensions(page)
        val required = (size[0].toLong() * size[1] * 16 + SuperResolutionMemoryBudget.MIB - 1) /
            SuperResolutionMemoryBudget.MIB
        showStatus(activity.getString(R.string.sr_large, size[0], size[1], required,
            budget / SuperResolutionMemoryBudget.MIB))
    }

    override fun close() {
        closed = true
        cancel()
        retireSession()
        scope.cancel()
        status.visibility = View.GONE
        provider.clearEnhancedPages()
    }
}
