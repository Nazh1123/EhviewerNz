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
            enable(translation)
            measure(host, 1000)
            val expandedWidth = baseWidth + (32 * activity.resources.displayMetrics.density).toInt()
            assertEquals(expandedWidth, left.width)
            assertEquals(expandedWidth, right.width)
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

    private fun withReader(block: (AppCompatActivity, Provider, ReaderTranslationController,
                                  ReaderSuperResolutionController, FrameLayout) -> Unit) {
        Robolectric.buildActivity(AppCompatActivity::class.java).use { controller ->
            val activity = controller.get()
            activity.setTheme(R.style.AppTheme_Gallery)
            controller.setup()
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
