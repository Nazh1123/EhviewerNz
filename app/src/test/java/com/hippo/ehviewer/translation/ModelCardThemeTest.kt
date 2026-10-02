package com.hippo.ehviewer.translation

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.fragment.*
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TranslationNavigationTest.TestApplication::class, sdk = [28], qualifiers = "zh-rCN")
class ModelCardThemeTest {
    @Test fun actualButtonsAndIconsHaveReadableContrastInLightDarkAndBlackThemes() {
        for (theme in listOf(Settings.THEME_LIGHT, Settings.THEME_DARK, Settings.THEME_BLACK)) {
            Settings.putTheme(theme)
            Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
                val context = controller.get()
                val colors = ModelPalette.forTheme(theme)
                for (kind in ModelButtonKind.entries) {
                    var clicked = false
                    val button = modelButton(context, colors, "下载", R.drawable.v_download_dark_x24, kind) { clicked = true }
                    val fill = button.backgroundTintList!!.getColorForState(button.drawableState, 0)
                    assertTrue("Theme $theme / $kind text must be readable", ColorUtils.calculateContrast(button.currentTextColor, fill) >= 4.5)
                    assertEquals(button.currentTextColor, button.iconTint!!.getColorForState(button.drawableState, 0))
                    button.performClick()
                    assertTrue(clicked)
                    clicked = false
                    button.isEnabled = false
                    val disabledFill = button.backgroundTintList!!.getColorForState(button.drawableState, 0)
                    assertTrue(ColorUtils.calculateContrast(button.currentTextColor, disabledFill) >= 3.0)
                    button.performClick()
                    assertFalse(clicked)
                }
                assertTrue(ColorUtils.calculateContrast(colors.text, colors.surface) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(colors.secondary, colors.surface) >= 4.5)
            }
        }
        Settings.putTheme(Settings.THEME_LIGHT)
    }

    @Test fun threeCardActionsShareOneRowOnNarrowScreensWithLargeTextAndKeepTouchTargets() {
        Settings.putTheme(Settings.THEME_BLACK)
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val context = controller.get()
            val card = TranslationModelCard(context, ModelPalette.forTheme(Settings.THEME_BLACK), "fixture",
                "漫画识别套件", "文字检测 · 日文 OCR · 背景修复", R.drawable.v_translate_x24,
                "已安装", true, listOf("236 MB", "7 个文件"), listOf(
                    ModelCardAction(R.string.translation_model_check_download, ModelButtonKind.PRIMARY) {},
                    ModelCardAction(R.string.translation_model_import) {},
                    ModelCardAction(R.string.translation_model_delete, ModelButtonKind.DELETE) {}))
            fun buttons(group: ViewGroup): List<MaterialButton> = (0 until group.childCount).flatMap { index ->
                val child = group.getChildAt(index)
                when (child) { is MaterialButton -> listOf(child); is ViewGroup -> buttons(child); else -> emptyList() }
            }
            val actions = buttons(card)
            actions.forEach { it.textSize *= 1.6f }
            val width = context.modelDp(288)
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, width, card.measuredHeight)
            assertEquals(3, actions.size)
            assertEquals(1, actions.map { it.top }.distinct().size)
            assertTrue(actions.maxOf { it.width } - actions.minOf { it.width } <= 1)
            assertEquals("校验", actions.first().text.toString())
            assertTrue(actions.first().contentDescription.contains("校验 / 补全"))
            for (button in actions) {
                assertTrue(button.measuredHeight >= context.modelDp(48))
                assertTrue(button.measuredWidth >= context.modelDp(48))
                assertTrue(button.right <= (button.parent as ViewGroup).width)
                val layout = requireNotNull(button.layout)
                assertEquals(button.text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) assertTrue(layout.getLineWidth(line) <= layout.width + 1)
            }
        }
        Settings.putTheme(Settings.THEME_LIGHT)
    }
}
