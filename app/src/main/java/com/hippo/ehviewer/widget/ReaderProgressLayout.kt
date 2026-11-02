package com.hippo.ehviewer.widget

import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hippo.ehviewer.R
import kotlin.math.roundToInt

/** Shared placement for the reader's translation and enhancement progress controls. */
class ReaderProgressLayout(activity: AppCompatActivity) {
    private val translation = activity.findViewById<View>(R.id.translation_progress_panel)
    private val enhancement = activity.findViewById<View>(R.id.super_resolution_progress_panel)
    private val density = activity.resources.displayMetrics.density
    private val iconSpace = (28 * density).roundToInt() // 24dp icon + 4dp gap.
    private val iconPadding = (4 * density).roundToInt()
    private val innerPadding = (8 * density).roundToInt()
    private val gap = 8 * density
    private val screen = DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }
    private val width = (minOf(screen.widthPixels, screen.heightPixels) * 0.35f).roundToInt()
    private var translationEnabled = false
    private var enhancementEnabled = false
    private var translationResult = false
    private var enhancementResult = false
    private var quickSettingsVisible = false
    private var quickSettingsBottom = 0f
    private val panels = listOfNotNull(translation, enhancement)
    private val paddings = panels.associateWith { it.paddingLeft to it.paddingRight }

    init {
        panels.forEach { panel ->
            val margins = panel.layoutParams as FrameLayout.LayoutParams
            val left = margins.leftMargin
            val top = margins.topMargin
            val right = margins.rightMargin
            ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val params = view.layoutParams as FrameLayout.LayoutParams
                params.leftMargin = left + safe.left
                params.topMargin = maxOf(top, safe.top)
                params.rightMargin = right + safe.right
                view.layoutParams = params
                update()
                insets
            }
            ViewCompat.requestApplyInsets(panel)
        }
        (panels.firstOrNull()?.parent as? View)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        update()
    }

    fun setTranslationEnabled(enabled: Boolean) {
        if (translationEnabled == enabled) return
        translationEnabled = enabled
        update()
    }

    fun setEnhancementEnabled(enabled: Boolean) {
        if (enhancementEnabled == enabled) return
        enhancementEnabled = enabled
        update()
    }
    fun setTranslationResultAvailable(available: Boolean) {
        if (translationResult == available) return
        translationResult = available
        update()
    }
    fun setEnhancementResultAvailable(available: Boolean) {
        if (enhancementResult == available) return
        enhancementResult = available
        update()
    }

    fun onQuickSettingsPositionChanged(visible: Boolean, bottom: Float) {
        quickSettingsVisible = visible
        quickSettingsBottom = bottom
        update()
    }

    private fun update() {
        val both = (translationEnabled || translationResult) && (enhancementEnabled || enhancementResult)
        panels.forEach { panel ->
            val enabled = if (panel === translation) translationEnabled else enhancementEnabled
            val result = if (panel === translation) translationResult else enhancementResult
            val iconOnly = !enabled && result
            panel.visibility = if (enabled || result) View.VISIBLE else View.GONE
            val icon = panel.findViewById<View>(if (panel === translation) R.id.translation_progress_icon else R.id.super_resolution_progress_icon)
            icon.visibility = if (iconOnly || both) View.VISIBLE else View.GONE
            val iconParams = icon.layoutParams as LinearLayout.LayoutParams
            val iconMargin = (4 * density).roundToInt()
            val leftMargin = if (!iconOnly && panel === enhancement) iconMargin else 0
            val rightMargin = if (!iconOnly && panel === translation) iconMargin else 0
            if (iconParams.leftMargin != leftMargin || iconParams.rightMargin != rightMargin) {
                iconParams.leftMargin = leftMargin; iconParams.rightMargin = rightMargin
                icon.layoutParams = iconParams
            }
            panel.findViewById<View>(if (panel === translation) R.id.translation_progress_bars else R.id.super_resolution_progress_bars)
                .visibility = if (iconOnly) View.GONE else View.VISIBLE
            val params = panel.layoutParams as FrameLayout.LayoutParams
            val gravity = Gravity.TOP or if (both && panel === enhancement) Gravity.RIGHT else Gravity.LEFT
            val (leftPadding, rightPadding) = paddings.getValue(panel)
            panel.setPadding(
                if (iconOnly) innerPadding else if (both) { if (panel === translation) iconPadding else innerPadding } else leftPadding,
                panel.paddingTop,
                if (iconOnly) innerPadding else if (both) { if (panel === enhancement) iconPadding else innerPadding } else rightPadding,
                panel.paddingBottom,
            )
            // Reclaim horizontal padding while preserving the original bar length and height.
            val savedPadding = leftPadding + rightPadding - panel.paddingLeft - panel.paddingRight
            var targetWidth = if (iconOnly) (40 * density).roundToInt() else width + if (both) iconSpace - savedPadding else 0
            if (both && !iconOnly) {
                // Keep the two controls separate in narrow windows and beside display cutouts.
                val available = (panel.parent as? View)?.width?.takeIf { it > 0 } ?: screen.widthPixels
                val margin = maxOf(params.leftMargin, params.rightMargin)
                targetWidth = minOf(targetWidth, ((available - gap) / 2).toInt() - margin).coerceAtLeast(1)
            }
            if (params.width != targetWidth || params.gravity != gravity) {
                params.width = targetWidth
                params.gravity = gravity
                panel.layoutParams = params
            }
            panel.translationY = if (quickSettingsVisible) maxOf(0f, quickSettingsBottom + gap - params.topMargin) else 0f
        }
    }
}
