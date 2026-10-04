package com.hippo.ehviewer.ui.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings

/** Explicit colors avoid the legacy Settings buttonStyle, including its black text override. */
internal data class ModelPalette(val background: Int, val surface: Int, val text: Int, val secondary: Int,
    val accent: Int, val onAccent: Int, val tonal: Int, val border: Int, val danger: Int,
    val disabledText: Int, val disabledFill: Int) {
    companion object {
        fun forTheme(theme: Int): ModelPalette {
            fun c(value: String) = Color.parseColor(value)
            return if (theme == Settings.THEME_LIGHT) ModelPalette(c("#FAFAFA"), Color.WHITE,
                c("#212121"), c("#616161"), c("#00796B"), Color.WHITE, c("#EEEEEE"),
                c("#E0E0E0"), c("#B3261E"), c("#757575"), c("#EEEEEE"))
            else ModelPalette(if (theme == Settings.THEME_BLACK) Color.BLACK else c("#323232"),
                c(if (theme == Settings.THEME_BLACK) "#191919" else "#424242"),
                c("#EEEEEE"), c("#CCCCCC"), c("#80CBC4"), c("#00332F"),
                c(if (theme == Settings.THEME_BLACK) "#292929" else "#4A4A4A"),
                c(if (theme == Settings.THEME_BLACK) "#3D3D3D" else "#616161"),
                c("#FFB4AB"), c("#BDBDBD"), c("#424242"))
        }
    }
}

internal fun Context.modelDp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
internal fun modelText(context: Context, value: CharSequence, size: Float, color: Int, bold: Boolean = false) =
    TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal enum class ModelButtonKind { PRIMARY, SECONDARY, DELETE }
internal fun modelButton(context: Context, palette: ModelPalette, label: CharSequence, icon: Int = 0,
                         kind: ModelButtonKind = ModelButtonKind.SECONDARY, run: () -> Unit): MaterialButton {
    val primary = kind == ModelButtonKind.PRIMARY
    val foreground = when (kind) {
        ModelButtonKind.PRIMARY -> palette.onAccent
        ModelButtonKind.SECONDARY -> palette.accent
        ModelButtonKind.DELETE -> palette.danger
    }
    fun states(enabled: Int, disabled: Int) = ColorStateList(arrayOf(
        intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, enabled))
    return MaterialButton(context).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        cornerRadius = context.modelDp(6)
        elevation = 0f
        stateListAnimator = null
        insetTop = 0
        insetBottom = 0
        minHeight = context.modelDp(48)
        minimumHeight = context.modelDp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(context.modelDp(14), 0, context.modelDp(14), 0)
        setTextColor(states(foreground, palette.disabledText))
        iconTint = states(foreground, palette.disabledText)
        if (icon != 0) this.icon = AppCompatResources.getDrawable(context, icon)
        iconSize = context.modelDp(20)
        iconPadding = context.modelDp(if (label.isEmpty()) 0 else 6)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        backgroundTintList = states(if (primary) palette.accent else palette.surface, palette.disabledFill)
        strokeWidth = if (primary) 0 else context.modelDp(1)
        strokeColor = ColorStateList.valueOf(palette.border)
        rippleColor = ColorStateList.valueOf(palette.tonal)
        setOnClickListener { if (isEnabled) run() }
    }
}

internal data class ModelCardAction(val label: Int, val kind: ModelButtonKind = ModelButtonKind.SECONDARY,
                                  val run: () -> Unit)

/** A standalone view, independent of PreferenceFragmentCompat and its row/button styling. */
internal class TranslationModelCard(context: Context, private val palette: ModelPalette,
    val key: String, title: String, description: String, icon: Int, status: String,
    active: Boolean, metadata: List<String>, actions: List<ModelCardAction>, details: (() -> Unit)? = null,
) : MaterialCardView(context) {
    init {
        tag = key
        radius = context.modelDp(10).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(palette.surface)
        strokeColor = if (active) palette.accent else palette.border
        strokeWidth = context.modelDp(1)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.modelDp(14), context.modelDp(12), context.modelDp(14), context.modelDp(12))
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val header = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val image = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = GradientDrawable().apply { setColor(palette.tonal); cornerRadius = context.modelDp(8).toFloat() }
            setPadding(context.modelDp(8), context.modelDp(8), context.modelDp(8), context.modelDp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(image, LinearLayout.LayoutParams(context.modelDp(40), context.modelDp(40)))
        header.addView(modelText(context, title, 16f, palette.text, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.modelDp(10) })
        if (details != null) header.addView(modelButton(context, palette, "", R.drawable.v_info_outline_dark_x24, run = details).apply {
            contentDescription = context.getString(R.string.translation_model_details_for, title)
            tag = "$key/details"
            minWidth = 0
            minimumWidth = 0
            strokeWidth = 0
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(context.modelDp(48), context.modelDp(48)))
        body.addView(header)
        body.addView(modelText(context, description, 13f, palette.secondary), LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.modelDp(8) })
        val badges = FlexboxLayout(context).apply { flexWrap = com.google.android.flexbox.FlexWrap.WRAP }
        (listOf(status) + metadata).filter { it.isNotBlank() }.forEachIndexed { index, label ->
            badges.addView(modelText(context, label, 12f, if (index == 0 && active) palette.accent else palette.secondary).apply {
                background = GradientDrawable().apply { setColor(palette.tonal); cornerRadius = context.modelDp(4).toFloat() }
                setPadding(context.modelDp(9), context.modelDp(5), context.modelDp(9), context.modelDp(5))
                tag = "$key/badge/$index"
            }, FlexboxLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.modelDp(6); topMargin = context.modelDp(8)
            })
        }
        body.addView(badges)
        // Share the available width instead of allowing intrinsic button widths to wrap.
        val buttons = LinearLayout(context).apply { tag = "$key/actions" }
        actions.forEachIndexed { index, action ->
            val actionIcon = when (action.label) {
                R.string.translation_model_download -> R.drawable.v_download_dark_x24
                R.string.translation_model_import, R.string.translation_model_choose_file -> R.drawable.v_folders_import_dark_x24
                R.string.translation_model_delete -> R.drawable.v_delete_dark_x24
                R.string.translation_model_refresh -> R.drawable.v_refresh_dark_x24
                R.string.translation_model_use, R.string.translation_model_selected_button -> R.drawable.v_check_dark_x24
                else -> R.drawable.v_arrow_down_x24
            }
            val compactLabel = when (action.label) {
                R.string.translation_model_choose_language -> R.string.translation_model_language_short
                R.string.translation_model_refresh -> R.string.translation_model_refresh_short
                R.string.translation_model_use -> R.string.translation_model_use_short
                else -> action.label
            }
            buttons.addView(modelButton(context, palette, context.getString(compactLabel), actionIcon, action.kind, action.run).apply {
                tag = "$key/action/${action.label}"
                isSelected = action.label == R.string.translation_model_selected_button
                if (isSelected) {
                    isClickable = false
                    isFocusable = false
                }
                contentDescription = "${context.getString(action.label)} · $title"
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, context.getString(action.label))
                maxLines = 2
                iconSize = context.modelDp(16)
                iconPadding = context.modelDp(4)
                if (actions.size > 1) setPadding(context.modelDp(4), 0, context.modelDp(4), 0)
            }, LinearLayout.LayoutParams(if (actions.size > 1) 0 else LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT, if (actions.size > 1) 1f else 0f).apply {
                if (index > 0) marginStart = context.modelDp(6)
            })
        }
        body.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.modelDp(12)
        })
    }
}
