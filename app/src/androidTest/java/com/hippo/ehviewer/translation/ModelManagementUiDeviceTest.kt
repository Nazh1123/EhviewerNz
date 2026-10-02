package com.hippo.ehviewer.translation

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.fragment.TranslationModelsFragment
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only validation: reads model inventory, never downloads, selects or deletes a model. */
@RunWith(AndroidJUnit4::class)
class ModelManagementUiDeviceTest {
    @Test fun capturesLightDarkAndBlackCardsWithReadableButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = Settings.getTheme()
        val directory = File(context.getExternalFilesDir(null), "model-ui-previews").apply { mkdirs() }
        try {
            for ((theme, label) in listOf(Settings.THEME_LIGHT to "light", Settings.THEME_DARK to "dark", Settings.THEME_BLACK to "black")) {
                instrumentation.runOnMainSync { Settings.putTheme(theme) }
                val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
                try {
                    instrumentation.runOnMainSync {
                        activity.supportFragmentManager.beginTransaction().replace(R.id.settings, TranslationModelsFragment()).commitNow()
                    }
                    instrumentation.waitForIdleSync()
                    // Let the asynchronous file inventory finish and lay out before capturing.
                    SystemClock.sleep(600)
                    fun capture(section: Int, suffix: String) {
                        instrumentation.runOnMainSync {
                            val page = activity.supportFragmentManager.findFragmentById(R.id.settings) as TranslationModelsFragment
                            val root = requireNotNull(page.view)
                            root.findViewWithTag<MaterialButton>("translation_model_tab_$section").performClick()
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(200)
                        instrumentation.runOnMainSync {
                            val root = requireNotNull(activity.supportFragmentManager.findFragmentById(R.id.settings)!!.view) as ViewGroup
                            fun inspect(group: ViewGroup) {
                                if (group.tag?.toString()?.endsWith("/actions") == true && group.childCount == 3) {
                                    val buttons = (0 until group.childCount).map { group.getChildAt(it) as MaterialButton }
                                    assertEquals("Three actions must share a row", 1, buttons.map { it.top }.distinct().size)
                                    assertTrue(buttons.maxOf { it.width } - buttons.minOf { it.width } <= 1)
                                    buttons.forEach { button ->
                                        val textLayout = requireNotNull(button.layout)
                                        assertEquals(button.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                                        assertTrue(button.height >= (48 * context.resources.displayMetrics.density).toInt())
                                        for (line in 0 until textLayout.lineCount)
                                            assertTrue(textLayout.getLineWidth(line) <= textLayout.width + 1)
                                    }
                                }
                                for (i in 0 until group.childCount) {
                                    val child = group.getChildAt(i)
                                    if (child is MaterialButton && child.isEnabled) {
                                        val fill = child.backgroundTintList!!.getColorForState(child.drawableState, 0)
                                        assertTrue("$label: ${child.text}", ColorUtils.calculateContrast(child.currentTextColor, fill) >= 4.5)
                                    } else if (child is ViewGroup) inspect(child)
                                }
                            }
                            inspect(root)
                        }
                        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        File(directory, "$label-$suffix.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        screenshot.recycle()
                    }
                    capture(1, "native")
                    if (theme == Settings.THEME_BLACK) { capture(0, "manga"); capture(2, "languages") }
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally { instrumentation.runOnMainSync { Settings.putTheme(previous) } }
    }
}
