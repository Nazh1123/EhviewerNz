package com.hippo.ehviewer.widget

import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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

    fun onQuickSettingsPositionChanged(visible: Boolean, bottom: Float) {
        quickSettingsVisible = visible
        quickSettingsBottom = bottom
        update()
    }

    private fun update() {
        val both = translationEnabled && enhancementEnabled
        translation?.findViewById<View>(R.id.translation_progress_icon)?.visibility = if (both) View.VISIBLE else View.GONE
        enhancement?.findViewById<View>(R.id.super_resolution_progress_icon)?.visibility = if (both) View.VISIBLE else View.GONE
        panels.forEach { panel ->
            val params = panel.layoutParams as FrameLayout.LayoutParams
            val gravity = Gravity.TOP or if (both && panel === enhancement) Gravity.RIGHT else Gravity.LEFT
            val (leftPadding, rightPadding) = paddings.getValue(panel)
            panel.setPadding(
                if (both) { if (panel === translation) iconPadding else innerPadding } else leftPadding,
                panel.paddingTop,
                if (both) { if (panel === enhancement) iconPadding else innerPadding } else rightPadding,
                panel.paddingBottom,
            )
            // Reclaim horizontal padding while preserving the original bar length and height.
            val savedPadding = leftPadding + rightPadding - panel.paddingLeft - panel.paddingRight
            var targetWidth = width + if (both) iconSpace - savedPadding else 0
            if (both) {
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
