package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.translation.ReaderTranslationController
import com.hippo.ehviewer.translation.TranslationTasks
import com.hippo.ehviewer.widget.ReaderProgressLayout
import com.hippo.lib.image.Image
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderSuperResolutionControllerTest {
    @Test fun allModeAndExistingResultCombinationsUseIndependentProgressOrIconControls() {
        withReader { activity, provider, translation, enhancement, host ->
            val left = host.findViewById<View>(R.id.translation_progress_panel)
            val right = host.findViewById<View>(R.id.super_resolution_progress_panel)
            ReflectionHelpers.setField(translation.session, "current", 0)
            ReflectionHelpers.setField(enhancement.session, "current", 0)
            provider.setPreferExistingResults(true)
            for (modes in 0..3) for (results in 0..3) {
                val tlMode = modes and 1 != 0; val srMode = modes and 2 != 0
                val tlResult = results and 1 != 0; val srResult = results and 2 != 0
                ReflectionHelpers.setField(translation.session, "enabled", tlMode)
                ReflectionHelpers.setField(enhancement.session, "enabled", srMode)
                provider.clearTranslatedPages(); provider.clearEnhancedPages()
                ReflectionHelpers.callInstanceMethod<Unit>(translation, "updateUi")
                ReflectionHelpers.callInstanceMethod<Unit>(enhancement, "update")
                if (tlResult) provider.setExistingTranslation(0, requireNotNull(Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
                if (srResult) provider.setExistingEnhancedTiles(0, EnhancedTilePage(java.io.File(activity.cacheDir, "floating-test"), 8, 8, 2))
                val tlVisible = tlMode || tlResult; val srVisible = srMode || srResult
                val both = tlVisible && srVisible
                val label = "modes=$modes results=$results"
                assertEquals(label, if (tlVisible) View.VISIBLE else View.GONE, left.visibility)
                assertEquals(label, if (srVisible) View.VISIBLE else View.GONE, right.visibility)
                if (tlVisible) {
                    assertEquals(label, if (tlMode) View.VISIBLE else View.GONE, host.findViewById<View>(R.id.translation_progress_bars).visibility)
                    assertEquals(label, if (!tlMode || both) View.VISIBLE else View.GONE, host.findViewById<View>(R.id.translation_progress_icon).visibility)
                }
                if (srVisible) {
                    assertEquals(label, if (srMode) View.VISIBLE else View.GONE, host.findViewById<View>(R.id.super_resolution_progress_bars).visibility)
                    assertEquals(label, if (!srMode || both) View.VISIBLE else View.GONE, host.findViewById<View>(R.id.super_resolution_progress_icon).visibility)
                }
                for (width in listOf(1000, 500)) {
                    measure(host, width)
                    if (both) {
                        assertEquals(label, Gravity.TOP or Gravity.LEFT, params(left).gravity)
                        assertEquals(label, Gravity.TOP or Gravity.RIGHT, params(right).gravity)
                        assertTrue(label, left.right < right.left)
                    }
                    val iconWidth = (40 * activity.resources.displayMetrics.density).toInt()
                    if (!tlMode && tlResult) assertEquals(label, iconWidth, left.width)
                    if (!srMode && srResult) assertEquals(label, iconWidth, right.width)
                }
                if (!tlMode && tlResult) {
                    assertNotNull(provider.getTranslationOverlay(0))
                    left.performClick(); assertNull(provider.getTranslationOverlay(0))
                    assertEquals(View.VISIBLE, left.visibility)
                    left.performClick(); assertNotNull(provider.getTranslationOverlay(0))
                    assertFalse(translation.session.enabled)
                }
                if (!srMode && srResult) {
                    assertNotNull(provider.getEnhancedTiles(0))
                    right.performClick(); assertNull(provider.getEnhancedTiles(0))
                    assertEquals(View.VISIBLE, right.visibility)
                    right.performClick(); assertNotNull(provider.getEnhancedTiles(0))
                    assertFalse(enhancement.session.enabled)
                }
            }
            ReflectionHelpers.setField(translation.session, "enabled", false)
            ReflectionHelpers.setField(enhancement.session, "enabled", false)
            provider.setPreferExistingResults(false)
            ReflectionHelpers.callInstanceMethod<Unit>(translation, "updateUi")
            ReflectionHelpers.callInstanceMethod<Unit>(enhancement, "update")
            assertEquals(View.GONE, left.visibility); assertEquals(View.GONE, right.visibility)
        }
    }
    @Test fun bothSubmenusOpenTheSameImportActionWithoutEnablingEitherProcessingMode() {
        withReader { activity, provider, translation, enhancement, _ ->
            for (open in listOf({ translation.showMenu(0) }, { enhancement.showMenu(0) })) {
                open()
                val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val position = menu.listView.adapter.count - 1
                assertEquals(activity.getString(R.string.result_import), menu.listView.adapter.getItem(position))
                menu.listView.performItemClick(null, position, position.toLong())
                val expected = activity.getString(R.string.result_import_report, 0, 0, 0, 0)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
                var report: androidx.appcompat.app.AlertDialog? = null
                while (report == null) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    val latest = org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
                    if (latest?.findViewById<TextView>(android.R.id.message)?.text?.toString() == expected) report = latest
                    else { assertTrue("Import did not finish", System.nanoTime() < deadline); Thread.sleep(5) }
                }
                assertFalse(translation.session.enabled); assertFalse(enhancement.session.enabled)
                assertFalse(provider.hasTranslatedPage(0)); assertFalse(provider.hasEnhancedPage(0))
                report.dismiss()
            }
        }
    }
    @Test fun singleFlowUsesTopLeftAndBothFlowsExpandAroundIconsAndRestoreInEitherOrder() {
        withReader { activity, _, translation, enhancement, host ->
            val left = host.findViewById<View>(R.id.translation_progress_panel)
            val right = host.findViewById<View>(R.id.super_resolution_progress_panel)
            val leftIcon = left.findViewById<View>(R.id.translation_progress_icon)
            val rightIcon = right.findViewById<View>(R.id.super_resolution_progress_icon)
            val baseWidth = left.layoutParams.width
            assertEquals(350, baseWidth)
            assertEquals(baseWidth, right.layoutParams.width)
            assertEquals(left.alpha, right.alpha, 0f)
            assertEquals(Gravity.TOP or Gravity.LEFT, params(right).gravity)
            val bars = (right as ViewGroup).getChildAt(0) as ViewGroup
            assertEquals(2, bars.childCount)
            repeat(bars.childCount) { assertTrue(bars.getChildAt(it) is ProgressBar) }
            assertFalse((0 until right.childCount).any { right.getChildAt(it) is TextView })

            enable(enhancement)
            assertEquals(View.VISIBLE, right.visibility)
            assertEquals(Gravity.TOP or Gravity.LEFT, params(right).gravity)
            assertEquals(View.GONE, rightIcon.visibility)
            measure(host, 1000)
            val singleBarWidth = right.findViewById<View>(R.id.super_resolution_page_progress_bar).width
            val singleHeight = right.height
            enable(translation)
            measure(host, 1000)
            val expandedWidth = baseWidth + (16 * activity.resources.displayMetrics.density).toInt()
            assertEquals(expandedWidth, left.width)
            assertEquals(expandedWidth, right.width)
            assertEquals(singleHeight, left.height)
            assertEquals(singleHeight, right.height)
            val iconSize = (24 * activity.resources.displayMetrics.density).toInt()
            for (icon in listOf(leftIcon, rightIcon)) {
                assertEquals(iconSize, icon.width)
                assertEquals(iconSize, icon.height - icon.paddingTop - icon.paddingBottom)
            }
            val innerPadding = (8 * activity.resources.displayMetrics.density).toInt()
            assertEquals(innerPadding, left.paddingRight)
            assertEquals(innerPadding, right.paddingLeft)
            assertEquals(Gravity.TOP or Gravity.LEFT, params(left).gravity)
            assertEquals(Gravity.TOP or Gravity.RIGHT, params(right).gravity)
            assertEquals(left.top, right.top)
            assertEquals(left.left, host.width - right.right)
            assertEquals(View.VISIBLE, leftIcon.visibility)
            assertEquals(View.VISIBLE, rightIcon.visibility)
            assertEquals(singleBarWidth, right.findViewById<View>(R.id.super_resolution_page_progress_bar).width)
            assertEquals(singleBarWidth, left.findViewById<View>(R.id.translation_page_progress_bar).width)
            assertTrue(leftIcon.right <= (left.findViewById<View>(R.id.translation_page_progress_bar).parent as View).left)
            assertTrue(bars.right <= rightIcon.left)

            translation.toggle()
            assertEquals(Gravity.TOP or Gravity.LEFT, params(right).gravity)
            assertEquals(baseWidth, right.layoutParams.width)
            assertEquals(left.paddingRight, right.paddingLeft)
            assertEquals(View.GONE, rightIcon.visibility)
            enable(translation)
            enhancement.session.disable()
            assertEquals(baseWidth, left.layoutParams.width)
            assertEquals(View.GONE, leftIcon.visibility)
            enable(enhancement)
            measure(host, 500)
            assertTrue("left=${left.left}..${left.right}, right=${right.left}..${right.right}", left.right < right.left)
        }
    }

    @Test fun superResolutionButtonMatchesTranslationTintAndAlphaAcrossReaderThemes() {
        for (theme in listOf(R.style.AppTheme_Gallery, R.style.AppTheme_Gallery_Dark, R.style.AppTheme_Gallery_Black)) {
            withReader(theme = theme) { _, _, translation, enhancement, _ ->
                val left = ReflectionHelpers.getField<ImageButton>(translation, "button")
                val right = ReflectionHelpers.getField<ImageButton>(enhancement, "button")
                fun compare() {
                    assertEquals(left.alpha, right.alpha, 0f)
                    assertEquals(left.imageTintList?.defaultColor, right.imageTintList?.defaultColor)
                }
                compare()
                enable(translation)
                enable(enhancement)
                compare()
                translation.toggle()
                enhancement.session.disable()
                compare()
            }
        }
    }

    @Test fun readerStartsDisabledDespiteLegacyPreferenceAndToggleOnlyLastsForCurrentGallery() {
        withReader(configure = { activity ->
            SuperResolutionSettings(activity).preferences.edit()
                .putBoolean(SuperResolutionModels.ENABLED, true).commit()
        }) { _, _, _, enhancement, _ ->
            val button = ReflectionHelpers.getField<ImageButton>(enhancement, "button")
            enhancement.onPageChanged(0)
            enhancement.resume()
            assertFalse(enhancement.session.enabled)
            assertFalse(button.isSelected)
            button.performClick()
            assertTrue(enhancement.session.enabled)
            assertFalse(enhancement.session.settings.automatic)
            enhancement.pause()
            enhancement.resume()
            assertTrue(enhancement.session.enabled)
        }
        withReader { _, _, _, enhancement, _ ->
            enhancement.onPageChanged(0)
            enhancement.resume()
            assertFalse(enhancement.session.enabled)
            val button = ReflectionHelpers.getField<ImageButton>(enhancement, "button")
            button.performClick()
            button.performClick()
            enhancement.resume()
            assertFalse(enhancement.session.enabled)
        }
    }

    @Test fun bothPanelsFollowQuickSettingsAndSafeInsetsWithoutAccumulatingMargins() {
        withReader { _, _, translation, enhancement, host ->
            enable(translation)
            enable(enhancement)
            val panels = listOf(host.findViewById<View>(R.id.translation_progress_panel),
                host.findViewById<View>(R.id.super_resolution_progress_panel))
            val left = params(panels[0]).leftMargin
            val right = params(panels[0]).rightMargin
            val top = params(panels[0]).topMargin
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),
                Insets.of(24, 300, 12, 80)).build()
            repeat(2) { panels.forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) } }
            panels.forEach {
                assertEquals(left + 24, params(it).leftMargin)
                assertEquals(right + 12, params(it).rightMargin)
                assertEquals(maxOf(top, 300), params(it).topMargin)
            }
            translation.onQuickSettingsPositionChanged(true, 500f)
            val gap = 8 * host.resources.displayMetrics.density
            panels.forEach { assertEquals(500f + gap - params(it).topMargin, it.translationY, 0f) }
            translation.onQuickSettingsPositionChanged(false, 500f)
            panels.forEach { assertEquals(0f, it.translationY, 0f) }
        }
    }

    @Test fun progressClickSwitchesCurrentResultAndPreservesTranslationAndProcessing() {
        withReader { _, provider, translation, enhancement, host ->
            enable(translation)
            enable(enhancement)
            fun image(width: Int) = requireNotNull(Image.create(Bitmap.createBitmap(width, 8, Bitmap.Config.ARGB_8888)))
            provider.notifyPageSucceed(0, image(8))
            provider.setEnhancedPage(0, image(16))
            provider.setTranslationOverlay(0, image(8))
            provider.setShowTranslations(true)
            provider.setEnhancedPage(1, image(24))
            ReflectionHelpers.setField(enhancement.session, "progress", 37)
            val panel = host.findViewById<View>(R.id.super_resolution_progress_panel)
            panel.performClick()
            assertNull(provider.getEnhancedPage(0))
            assertNotNull(provider.getEnhancedPage(1))
            assertTrue(provider.hasEnhancedPage(0))
            assertNotNull(provider.getTranslationOverlay(0))
            panel.performClick()
            assertEquals(16, provider.getEnhancedPage(0)?.width)
            assertTrue(enhancement.session.enabled)
            assertTrue(translation.session.enabled)
            assertEquals(37, enhancement.session.progress)
            assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            provider.removeEnhancedPage(0)
            panel.performClick()
            provider.setEnhancedPage(0, image(32))
            assertNotNull(provider.getEnhancedPage(0))
        }
    }

    private fun enable(reader: ReaderTranslationController) {
        ReflectionHelpers.setField(reader.session, "enabled", true)
        reader.onPageChanged(0)
    }

    private fun enable(reader: ReaderSuperResolutionController) {
        ReflectionHelpers.setField(reader.session, "enabled", true)
        reader.onPageChanged(0)
    }

    private fun params(view: View) = view.layoutParams as FrameLayout.LayoutParams
    private fun measure(host: View, width: Int) {
        repeat(2) {
            host.forceLayout()
            (host as ViewGroup).let { group -> repeat(group.childCount) { group.getChildAt(it).forceLayout() } }
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, 1800)
        }
    }

    private fun withReader(theme: Int = R.style.AppTheme_Gallery,
                           configure: (AppCompatActivity) -> Unit = {},
                           block: (AppCompatActivity, Provider, ReaderTranslationController,
                                  ReaderSuperResolutionController, FrameLayout) -> Unit) {
        Robolectric.buildActivity(AppCompatActivity::class.java).use { controller ->
            val activity = controller.get()
            activity.setTheme(theme)
            controller.setup()
            configure(activity)
            shadowOf(activity.windowManager.defaultDisplay).apply { setRealWidth(1000); setRealHeight(1800) }
            val host = FrameLayout(activity)
            activity.layoutInflater.inflate(R.layout.reader_translation_progress, host)
            activity.layoutInflater.inflate(R.layout.reader_super_resolution_progress, host)
            activity.setContentView(host)
            val provider = Provider()
            provider.start()
            val layout = ReaderProgressLayout(activity)
            try {
                ReaderTranslationController(activity, provider, ImageButton(activity), null, layout).use { translation ->
                    ReaderSuperResolutionController(activity, provider, ImageButton(activity), null, layout).use { enhancement ->
                        block(activity, provider, translation, enhancement, host)
                    }
                }
            } finally {
                SuperResolutionTasks.stopAll()
                TranslationTasks.stopAll()
                provider.stop()
            }
        }
    }

    private class Provider : GalleryProvider2() {
        override fun size() = 10
        override fun getError(): String? = null
        override fun save(index: Int, file: com.hippo.unifile.UniFile) = false
        override fun save(index: Int, dir: com.hippo.unifile.UniFile, filename: String): com.hippo.unifile.UniFile? = null
        override fun saveWithResult(index: Int, dir: com.hippo.unifile.UniFile, filename: String): SaveResult? = null
        override fun getImageFilename(index: Int) = "$index.jpg"
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
    }
}
