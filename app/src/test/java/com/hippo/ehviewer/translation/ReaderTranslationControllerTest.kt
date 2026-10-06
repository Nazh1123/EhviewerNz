package com.hippo.ehviewer.translation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import com.hippo.lib.glgallery.GalleryProvider
import com.hippo.lib.glview.image.ImageWrapper
import com.hippo.lib.glview.view.GLRoot
import com.hippo.unifile.UniFile
import com.hippo.ehviewer.translation.engine.TranslationStage
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
class ReaderTranslationControllerTest {
    @Test fun progressClickTogglesOnlyCurrentOverlayWithoutInterruptingTranslationOrReplacingSource() {
        withReaderUi { reader, provider, button, panel ->
            val shown = observePages(provider)
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            val active = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            reader.session.pageProgress.update(TranslationStage.DETECT, 0.5f)
            val progress = reader.session.pageProgress.value
            provider.notifyPageSucceed(0, Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))!!)
            provider.setTranslationOverlay(0, Image.create(Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888))!!)
            provider.setTranslationOverlay(1, Image.create(Bitmap.createBitmap(24, 8, Bitmap.Config.ARGB_8888))!!)
            provider.request(0)
            assertEquals(8, shown[0])
            assertEquals(16, provider.getTranslationOverlay(0)?.width)

            panel.performClick()
            assertEquals(8, shown[0])
            assertNull(provider.getTranslationOverlay(0))
            provider.request(1)
            assertNull(shown[1]) // An overlay cannot stand in for a source that has not loaded.
            assertEquals(24, provider.getTranslationOverlay(1)?.width)
            reader.onPageReady(0)
            provider.request(0)
            assertEquals(8, shown[0])

            panel.performClick()
            assertEquals(8, shown[0])
            assertEquals(16, provider.getTranslationOverlay(0)?.width)
            assertTrue(reader.session.enabled)
            assertTrue(button.isSelected)
            assertFalse(active.isObsolete)
            assertEquals(active, ReflectionHelpers.getField<TranslationPageRequest>(reader.session, "activeRequest"))
            assertEquals(progress, reader.session.pageProgress.value)
            assertEquals(View.VISIBLE, panel.visibility)

            provider.notifyPageSucceed(1, Image.create(Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888))!!)
            reader.onPageChanged(1)
            panel.performClick()
            assertEquals(12, shown[1])
            provider.request(0)
            assertEquals(8, shown[0])
            assertEquals(16, provider.getTranslationOverlay(0)?.width)
            // A late source callback must respect the user's choice to view the original.
            provider.notifyPageSucceed(1, Image.create(Bitmap.createBitmap(10, 8, Bitmap.Config.ARGB_8888))!!)
            assertEquals(10, shown[1])
            panel.performClick()
            assertEquals(10, shown[1])
            assertEquals(24, provider.getTranslationOverlay(1)?.width)
        }
    }

    @Test fun progressClickWithoutResultLeavesPageReadyToShowItsFirstTranslation() {
        withReaderUi { reader, provider, _, panel ->
            val shown = observePages(provider)
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            provider.notifyPageSucceed(0, Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))!!)
            shown.clear()
            panel.performClick()
            assertTrue(shown.isEmpty())
            provider.request(0)
            assertEquals(8, shown[0])
            assertTrue(reader.session.enabled)
            provider.setTranslationOverlay(0, Image.create(Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888))!!)
            provider.request(0)
            assertEquals(8, shown[0])
            assertEquals(16, provider.getTranslationOverlay(0)?.width)
        }
    }

    private fun observePages(provider: TestProvider): MutableMap<Int, Int> {
        val shown = mutableMapOf<Int, Int>()
        provider.setGLRoot(java.lang.reflect.Proxy.newProxyInstance(
            GLRoot::class.java.classLoader, arrayOf(GLRoot::class.java)) { _, method, args ->
            if (method.name == "addOnGLIdleListener")
                (args!![0] as GLRoot.OnGLIdleListener).onGLIdle(null, false)
            null
        } as GLRoot)
        provider.setListener(object : GalleryProvider.Listener {
            override fun onPageSucceed(index: Int, image: ImageWrapper) { shown[index] = image.width }
            override fun onDataChanged(index: Int) { provider.request(index) }
            override fun onPageOverlayChanged(index: Int) = Unit
            override fun onDataChanged() = Unit
            override fun onPageWait(index: Int) = Unit
            override fun onPagePercent(index: Int, percent: Float) = Unit
            override fun onPageFailed(index: Int, error: String?) = Unit
        })
        return shown
    }

    @Test fun partialPageShowsStatusAndRetriesMissingRegionsWithoutDroppingThePreview() {
        withReaderUi { reader, provider, _, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            provider.setTranslationOverlay(0, Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))!!)
            ReflectionHelpers.getField<MutableMap<Int, UniFile>>(reader.session, "completedFiles")[0] =
                UniFile.fromFile(java.io.File(panel.context.cacheDir, "preview.partial"))!!
            reader.session.states[0] = R.string.translation_partial
            reader.onPageReady(0)
            val status = panel.findViewById<android.widget.TextView>(R.id.translation_result_status)
            assertEquals(View.VISIBLE, status.visibility)
            assertEquals(panel.context.getString(R.string.translation_partial_short), status.text.toString())
            reader.showMenu(0)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(panel.context.getString(R.string.translation_retry_missing), dialog.listView.adapter.getItem(1))
            dialog.listView.performItemClick(null, 1, 1)
            assertTrue(provider.hasTranslatedPage(0))
            val request = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            assertTrue(request.retryMissing)
            assertTrue(request.force)
            // A higher priority gallery can interrupt between OCR and the missing-region request.
            reader.session.states[0] = R.string.translation_waiting
            ReflectionHelpers.callInstanceMethod<Unit>(reader.session, "finishRequest",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
                ReflectionHelpers.ClassParameter.from(TranslationPageRequest::class.java, request))
            val resumed = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            assertEquals(0, resumed.page)
            assertTrue(resumed.retryMissing)
            assertTrue(resumed.force)
            assertTrue(provider.hasTranslatedPage(0))
            reader.onPageChanged(20)
            assertTrue(resumed.isObsolete)
            assertEquals(R.string.translation_partial, reader.session.states[0])
            reader.onPageChanged(0)
            reader.showMenu(0)
            val reopened = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(panel.context.getString(R.string.translation_retry_missing), reopened.listView.adapter.getItem(1))
            reopened.dismiss()
            dialog.dismiss()
        }
    }

    @Test fun menuContainsReaderActionsAndDiagnosticsFollowTheBuildType() {
        withReader { reader, _ ->
            reader.showMenu(0)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val items = dialog.listView.adapter
            assertEquals(if (BuildConfig.DEBUG) 5 else 4, items.count)
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            assertEquals(app.getString(R.string.translation_show), items.getItem(0).toString())
            assertEquals(app.getString(R.string.translation_retry), items.getItem(1).toString())
            assertEquals(app.getString(R.string.translation_full_gallery), items.getItem(2).toString())
            assertEquals(app.getString(R.string.translation_source), items.getItem(3).toString())
            if (BuildConfig.DEBUG) assertEquals(app.getString(R.string.translation_timings), items.getItem(4).toString())
            dialog.dismiss()
        }
    }
    @Test fun sourceMenuSavesSelectionAndRestartsActiveFullGalleryTranslation() {
        withReaderUi { reader, _, button, panel ->
            reader.session.settings.save(reader.session.settings.read().copy(source = "auto"))
            reader.onPageChanged(0)
            reader.toggle()
            reader.session.translateFullGallery()
            val previous = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            reader.showMenu(0)
            val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            menu.listView.performItemClick(null, 3, 3)
            val languages = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(TranslationLanguages.sources.size, languages.listView.count)
            assertEquals(panel.context.getString(R.string.translation_source_auto), languages.listView.adapter.getItem(0))
            assertEquals(0, languages.listView.checkedItemPosition)
            val english = TranslationLanguages.sources.indexOf("en")
            languages.listView.performItemClick(null, english, english.toLong())
            assertTrue(previous.isObsolete)
            assertEquals("en", reader.session.settings.read().source)
            assertEquals("en", reader.session.activeOptions.source)
            assertTrue(reader.session.fullGallery)
            assertTrue(reader.session.enabled)
            assertTrue(button.isSelected)
            reader.toggle()
            assertFalse(reader.session.enabled)
            assertFalse(button.isSelected)
            assertEquals(View.GONE, panel.visibility)
        }
    }

    @Test fun selectingSourceWhileDisabledDoesNotStartTranslation() {
        withReader { reader, _ ->
            reader.showMenu(0)
            val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            menu.listView.performItemClick(null, 3, 3)
            val languages = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val korean = TranslationLanguages.sources.indexOf("ko")
            languages.listView.performItemClick(null, korean, korean.toLong())
            assertEquals("ko", reader.session.settings.read().source)
            assertFalse(reader.session.enabled)
        }
    }
    @Test fun apiConcurrentPagesHaveIndependentProgressAndOutOfOrderCompletion() {
        withReaderUi { reader, _, _, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions",
                TranslationOptions(backend = TranslationBackend.LLM_API, ahead = 3))
            reader.onPageChanged(0)
            fun next() = ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(reader.session, "nextRequest")
            val requests = List(4) { checkNotNull(next()) }
            assertEquals(listOf(0, 1, 2, 3), requests.map { it.page })
            assertNull(next()) // In-flight pages cannot be dispatched a second time.
            assertEquals(0, reader.session.activePage)
            requests[0].progress.update(TranslationStage.DETECT, 1f)
            requests[1].progress.update(TranslationStage.DETECT, 1f)
            requests[1].progress.update(TranslationStage.OCR, 1f)
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            fun finish(request: TranslationPageRequest) {
                states[request.page] = R.string.translation_done
                ReflectionHelpers.callInstanceMethod<Unit>(reader.session, "finishRequest",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
                    ReflectionHelpers.ClassParameter.from(TranslationPageRequest::class.java, request))
            }
            finish(requests[2])
            assertEquals(0, reader.session.activePage)
            assertEquals(100, reader.session.pageProgress.value)
            finish(requests[0])
            assertEquals(1, reader.session.activePage)
            assertSame(requests[1].progress, reader.session.pageProgress)
            assertEquals(350, panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar).progress)
            reader.onPageChanged(20)
            assertTrue(requests[1].isObsolete)
            assertTrue(requests[3].isObsolete)
            assertFalse(states.containsKey(1))
            assertFalse(states.containsKey(3))
            assertEquals(R.string.translation_done, states[2])
            assertEquals(20, next()!!.page)
        }
    }

    @Test fun apiNavigationKeepsInFlightLookaheadAndStoppingCancelsEveryPage() {
        withReader { reader, _ ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions",
                TranslationOptions(backend = TranslationBackend.LLM_API, ahead = 6))
            reader.onPageChanged(0)
            val requests = List(7) {
                ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            }
            reader.onPageChanged(3)
            assertTrue(requests[0].isObsolete)
            for (request in requests.drop(1)) assertFalse(request.isObsolete)
            reader.session.disable()
            assertTrue(requests.all { it.isObsolete })
            assertNull(reader.session.activePage)
        }
    }

    @Test fun turningTwoPagesAheadKeepsInFlightPageAndIntermediatePagesInOrder() {
        withReader { reader, provider ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 3))
            reader.onPageChanged(0)
            fun next() = ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(reader.session, "nextRequest")
            val first = next()!!
            reader.onPageChanged(1)
            reader.onPageChanged(2)
            assertFalse(first.isObsolete)
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            fun complete(page: Int) {
                states[page] = R.string.translation_done
                provider.setTranslationOverlay(page, requireNotNull(Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
            }
            complete(first.page)
            val pages = mutableListOf(first.page + 1)
            repeat(5) {
                val request = next()!!
                pages.add(request.page + 1)
                complete(request.page)
            }
            assertEquals(listOf(1, 2, 3, 4, 5, 6), pages)
            assertNull(next())
        }
    }

    @Test fun thirdPageMovementCancelsPageOneEvenThoughPageFourWasInOriginalLookAhead() {
        withReader { reader, provider ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 3))
            reader.onPageChanged(0)
            fun next() = ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(reader.session, "nextRequest")
            val first = next()!!
            reader.onPageChanged(1)
            reader.onPageChanged(2)
            assertFalse(first.isObsolete)
            reader.onPageChanged(3)
            assertTrue(first.isObsolete)
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            val pages = (0..3).map {
                val request = next()!!
                states[request.page] = R.string.translation_done
                provider.setTranslationOverlay(request.page, requireNotNull(Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
                request.page + 1
            }
            assertEquals(listOf(4, 5, 6, 7), pages)
            assertNull(next())
        }
    }

    @Test fun jumpingFromPageThreeToTwentyDropsOldWindowAndDispatchesTwentyThroughTwentyThree() {
        withReader { reader, provider ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 3))
            reader.onPageChanged(0)
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            fun next() = ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(reader.session, "nextRequest")
            fun complete(page: Int) {
                provider.setTranslationOverlay(page, requireNotNull(Image.create(
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
                states[page] = R.string.translation_done
            }
            assertEquals(0, next()!!.page)
            complete(0)
            assertEquals(1, next()!!.page)
            complete(1)
            val old = next()!!
            assertEquals(2, old.page)
            reader.onPageChanged(19)
            assertTrue(old.isObsolete)
            assertFalse(states.containsKey(2))
            val actual = (19..22).map {
                val dispatched = next()!!
                complete(dispatched.page)
                dispatched.page + 1
            }
            assertEquals(listOf(20, 21, 22, 23), actual)
            assertNull(next())
        }
    }

    @Test fun pageStillInNewWindowContinuesButReturningToAbandonedPageRetriesIt() {
        withReader { reader, _ ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 3))
            reader.onPageChanged(2)
            val active = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            reader.onPageChanged(1)
            assertFalse(active.isObsolete)
            reader.onPageChanged(19)
            assertTrue(active.isObsolete)
            reader.onPageChanged(2)
            val retry = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            assertEquals(2, retry.page)
            assertFalse(retry.isObsolete)
        }
    }

    @Test fun progressShowsActivePrefetchPageWhileShortcutKeepsEnabledIcon() {
        withReaderUi { reader, provider, button, panel ->
            val pageBar = panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar)
            val bar = panel.findViewById<ProgressBar>(R.id.translation_progress_bar)
            assertEquals(View.GONE, panel.visibility)
            assertEquals(R.drawable.v_translate_x24, shadowOf(button.drawable).createdFromResId)

            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 2))
            reader.onPageChanged(0)
            assertEquals(View.VISIBLE, panel.visibility)
            assertEquals(3, bar.max)
            assertEquals(0, bar.progress)
            assertEquals(0, pageBar.progress)

            ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            states[0] = R.string.translation_done
            provider.setTranslationOverlay(0, requireNotNull(Image.create(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
            val prefetch = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            assertEquals(1, prefetch.page)
            val weighted = ReflectionHelpers.getField<TranslationPageProgress>(reader.session, "pageProgress")
            weighted.update(TranslationStage.DETECT, 1f)
            weighted.update(TranslationStage.OCR, 1f)
            weighted.update(TranslationStage.TRANSLATE, 0.5f)
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
            assertEquals(575, pageBar.progress)
            assertEquals(1000, pageBar.max)
            assertEquals(1f, pageBar.scaleX, 0f)
            assertEquals(button.context.getString(R.string.translation_current_progress, 2, 57), pageBar.contentDescription)
            assertEquals(button.context.getString(R.string.translation_queue_progress, 1, 3), bar.contentDescription)
            assertEquals(1, bar.progress)
            assertTrue(button.isSelected)
            assertEquals(R.drawable.v_translate_x24, shadowOf(button.drawable).createdFromResId)
            assertEquals(button.context.getString(R.string.translation_accessibility,
                button.context.getString(R.string.translation_enabled)), button.contentDescription)

            reader.pause()
            assertEquals(View.VISIBLE, panel.visibility)
            assertTrue(button.isSelected)
            assertEquals(R.drawable.v_translate_x24, shadowOf(button.drawable).createdFromResId)
            reader.onPageReady(1)
            reader.onPageChanged(1)
            assertEquals(View.VISIBLE, panel.visibility)
        }
    }

    @Test fun progressCountsFinishedAndSkippedPagesAndResetsAfterJump() {
        withReaderUi { reader, _, button, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 2))
            reader.onPageChanged(0)
            val active = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            states[0] = R.string.translation_failed
            states[1] = R.string.translation_no_text
            states[2] = R.string.translation_animation
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
            val bar = panel.findViewById<ProgressBar>(R.id.translation_progress_bar)
            assertEquals(3, bar.progress)
            assertEquals(0, panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar).progress)
            assertEquals(R.drawable.v_translate_x24, shadowOf(button.drawable).createdFromResId)

            reader.onPageChanged(19)
            assertTrue(active.isObsolete)
            assertEquals(0, bar.progress)
            assertEquals(3, bar.max)
            assertEquals(button.context.getString(R.string.translation_current_progress, 20, 0),
                panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar).contentDescription)
            reader.close()
            assertEquals(View.GONE, panel.visibility)
            reader.onPageChanged(20)
            assertEquals(View.GONE, panel.visibility)
        }
    }

    @Test fun browsingCompletedPagesKeepsPrefetchProgressWithoutRefreshingTheFirstBar() {
        withReaderUi { reader, provider, _, panel ->
            val original = panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar)
            val parent = original.parent as ViewGroup
            val index = parent.indexOfChild(original)
            val bar = RecordingProgressBar(panel.context).apply {
                id = original.id
                layoutParams = original.layoutParams
            }
            parent.removeView(original)
            parent.addView(bar, index)
            ReflectionHelpers.setField(reader, "pageProgressBar", bar)
            ReflectionHelpers.setField(reader.session, "enabled", true)
            ReflectionHelpers.setField(reader.session, "activeOptions", TranslationOptions(ahead = 2))
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            for (page in 2..3) {
                states[page] = R.string.translation_done
                provider.setTranslationOverlay(page, requireNotNull(Image.create(
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
            }
            reader.onPageChanged(2)
            val request = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            assertEquals(4, request.page)
            val weighted = ReflectionHelpers.getField<TranslationPageProgress>(reader.session, "pageProgress")
            weighted.update(TranslationStage.DETECT, 1f)
            weighted.update(TranslationStage.OCR, 1f)
            weighted.update(TranslationStage.TRANSLATE, 0.5f)
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")

            fun browse(expectedProgress: Int) {
                val refreshes = bar.refreshes
                for (page in listOf(3, 2, 4, 3, 2)) {
                    reader.onPageChanged(page)
                    reader.onPageReady(page)
                    assertFalse(request.isObsolete)
                    assertSame(request, ReflectionHelpers.getField<TranslationPageRequest>(reader.session, "activeRequest"))
                    assertSame(weighted, ReflectionHelpers.getField<TranslationPageProgress>(reader.session, "pageProgress"))
                    assertEquals(1000, bar.max)
                    assertEquals(expectedProgress, bar.progress)
                    assertEquals(1f, bar.scaleX, 0f)
                    assertEquals(panel.context.getString(R.string.translation_current_progress,
                        5, expectedProgress / 10), bar.contentDescription)
                    assertEquals(refreshes, bar.refreshes)
                }
            }
            browse(575)
            weighted.update(TranslationStage.TRANSLATE, 0.75f)
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
            browse(688)
            // An available reader image is not a completion signal for the running request.
            provider.setTranslationOverlay(4, requireNotNull(Image.create(
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
            browse(688)
            reader.onPageChanged(7)
            assertTrue(request.isObsolete)
            assertEquals(0, bar.progress)
        }
    }

    @Test fun firstBarReversesForOneOrTwoPageDistanceThenRestoresWeightedProgress() {
        withReaderUi { reader, _, _, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            val request = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            val weighted = ReflectionHelpers.getField<TranslationPageProgress>(reader.session, "pageProgress")
            weighted.update(TranslationStage.DETECT, 1f)
            weighted.update(TranslationStage.OCR, 1f)
            val bar = panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar)
            reader.onPageChanged(1)
            assertFalse(request.isObsolete)
            assertEquals(3, bar.max)
            assertEquals(1, bar.progress)
            assertEquals(-1f, bar.scaleX, 0f)
            reader.onPageChanged(2)
            assertFalse(request.isObsolete)
            assertEquals(3, bar.max)
            assertEquals(2, bar.progress)
            assertEquals(-1f, bar.scaleX, 0f)
            weighted.update(TranslationStage.TRANSLATE, 0.5f)
            reader.onPageChanged(0)
            assertEquals(1000, bar.max)
            assertEquals(575, bar.progress)
            assertEquals(1f, bar.scaleX, 0f)
            reader.onPageChanged(3)
            assertTrue(request.isObsolete)
            assertEquals(1000, bar.max)
            assertEquals(0, bar.progress)
            assertEquals(1f, bar.scaleX, 0f)
        }
    }

    @Test fun completingPagesKeepsDistanceModeDuringHandoffUntilTranslationCatchesUp() {
        withReaderUi { reader, provider, _, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            fun next() = ReflectionHelpers.callInstanceMethod<TranslationPageRequest>(reader.session, "nextRequest")
            val first = next()
            reader.onPageChanged(2)
            val bar = panel.findViewById<ProgressBar>(R.id.translation_page_progress_bar)
            val queue = panel.findViewById<ProgressBar>(R.id.translation_progress_bar)
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            fun complete(request: TranslationPageRequest) {
                states[request.page] = R.string.translation_done
                provider.setTranslationOverlay(request.page, requireNotNull(Image.create(
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
                ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
                ReflectionHelpers.callInstanceMethod<Unit>(reader.session, "finishRequest",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
                    ReflectionHelpers.ClassParameter.from(TranslationPageRequest::class.java, request))
            }
            fun assertDistance(distance: Int) {
                assertEquals(3, bar.max)
                assertEquals(distance, bar.progress)
                assertEquals(-1f, bar.scaleX, 0f)
            }
            fun assertQueue(completed: Int) {
                assertEquals(3, queue.max)
                assertEquals(completed, queue.progress)
            }
            assertDistance(2)
            assertQueue(0)
            complete(first)
            assertNull(ReflectionHelpers.getField<TranslationPageRequest?>(reader.session, "activeRequest"))
            assertDistance(1)
            assertQueue(0)
            reader.onPageReady(1)
            assertDistance(1)
            assertQueue(0)

            val second = next()
            assertEquals(1, second.page)
            assertQueue(0)
            val weighted = ReflectionHelpers.getField<TranslationPageProgress>(reader.session, "pageProgress")
            weighted.update(TranslationStage.DETECT, 1f)
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
            assertDistance(1)
            complete(second)
            assertQueue(0)
            assertEquals(1000, bar.max)
            assertEquals(0, bar.progress)
            assertEquals(1f, bar.scaleX, 0f)
            val current = next()
            assertEquals(2, current.page)
            assertQueue(0)
            ReflectionHelpers.getField<TranslationPageProgress>(reader.session, "pageProgress")
                .update(TranslationStage.DETECT, 1f)
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
            assertEquals(100, bar.progress)
            assertEquals(1f, bar.scaleX, 0f)
            complete(current)
            assertQueue(1)
            val ahead = next()
            assertEquals(3, ahead.page)
            assertQueue(1)
            reader.onPageReady(3)
            assertQueue(1)
            complete(ahead)
            assertQueue(2)
            val last = next()
            assertEquals(4, last.page)
            assertQueue(2)
            complete(last)
            assertQueue(3)
            assertNull(ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(reader.session, "nextRequest"))
            assertQueue(3)
        }
    }

    @Test fun releasingTranslatedImagesDoesNotUndoCompletedQueueProgress() {
        withReaderUi { reader, provider, _, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            val states = ReflectionHelpers.getField<MutableMap<Int, Int>>(reader.session, "states")
            repeat(3) { page ->
                states[page] = R.string.translation_done
                provider.setTranslationOverlay(page, requireNotNull(Image.create(
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))))
            }
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateUi")
            val queue = panel.findViewById<ProgressBar>(R.id.translation_progress_bar)
            assertEquals(3, queue.progress)
            provider.clearTranslatedPages()
            reader.onPageReady(0)
            assertEquals(3, queue.max)
            assertEquals(3, queue.progress)
        }
    }

    @Test fun progressWidthUsesScreenShortSideAndStaysFixedAcrossRotationAndWindowResizes() {
        for ((screenWidth, screenHeight) in listOf(1000 to 1800, 1800 to 1000)) {
            withReaderUi(configureDisplay = { activity ->
                shadowOf(activity.windowManager.defaultDisplay).apply {
                    setRealWidth(screenWidth)
                    setRealHeight(screenHeight)
                    setWidth(600)
                    setHeight(800)
                }
            }) { reader, _, _, panel ->
                ReflectionHelpers.setField(reader.session, "enabled", true)
                reader.onPageChanged(0)
                val host = panel.parent as View
                fun resize(width: Int, height: Int, expected: Int) {
                    repeat(2) {
                        panel.forceLayout()
                        host.forceLayout()
                        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        host.layout(0, 0, width, height)
                    }
                    assertEquals(expected, panel.layoutParams.width)
                    assertEquals(expected, panel.measuredWidth)
                }
                resize(1000, 1800, 350)
                resize(1800, 1000, 350)
                resize(600, 800, 350)
            }
        }
    }

    @Test fun progressFollowsQuickSettingsAnimationAndReturnsWhenHidden() {
        withReaderUi { reader, _, _, panel ->
            ReflectionHelpers.setField(reader.session, "enabled", true)
            reader.onPageChanged(0)
            val top = (panel.layoutParams as FrameLayout.LayoutParams).topMargin
            val gap = 8f * panel.resources.displayMetrics.density
            assertEquals(0f, panel.translationY, 0f)
            reader.onQuickSettingsPositionChanged(true, 0f)
            assertEquals(0f, panel.translationY, 0f)
            reader.onQuickSettingsPositionChanged(true, top + 20f)
            assertEquals(20f + gap, panel.translationY, 0f)
            reader.onQuickSettingsPositionChanged(true, top + 48f)
            assertEquals(48f + gap, panel.translationY, 0f)
            reader.onQuickSettingsPositionChanged(true, top + 12f)
            assertEquals(12f + gap, panel.translationY, 0f)
            reader.onQuickSettingsPositionChanged(false, top + 48f)
            assertEquals(0f, panel.translationY, 0f)
            assertEquals(top, (panel.layoutParams as FrameLayout.LayoutParams).topMargin)
            assertEquals(View.VISIBLE, panel.visibility)
        }
    }

    @Test fun progressStaysAtTopLeftAndAppliesSafeInsetsWithoutAccumulatingMargins() {
        withReaderUi { _, _, _, panel ->
            val params = panel.layoutParams as FrameLayout.LayoutParams
            assertEquals(Gravity.TOP or Gravity.LEFT, params.gravity)
            assertTrue(panel.isClickable)
            assertTrue(panel.isFocusable)
            assertEquals(0.45f, panel.alpha, 0f)
            assertEquals(3, (panel as ViewGroup).childCount)
            assertEquals(View.GONE, panel.findViewById<View>(R.id.translation_result_status).visibility)
            (1 until panel.childCount).forEach { assertTrue(panel.getChildAt(it) is ProgressBar) }
            val left = params.leftMargin
            val top = params.topMargin
            assertEquals(panel.resources.getDimensionPixelSize(R.dimen.gallery_notice_margin_top), top)
            val right = params.rightMargin
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(24, 30, 12, 0)).build()
            repeat(2) { ViewCompat.dispatchApplyWindowInsets(panel, insets) }
            val adjusted = panel.layoutParams as FrameLayout.LayoutParams
            assertEquals(left + 24, adjusted.leftMargin)
            assertEquals(maxOf(top, 30), adjusted.topMargin)
            assertEquals(right + 12, adjusted.rightMargin)
        }
    }

    private fun withReader(block: (ReaderTranslationController, TestProvider) -> Unit) =
        withReaderUi { reader, provider, _, _ -> block(reader, provider) }

    private fun withReaderUi(configureDisplay: (AppCompatActivity) -> Unit = {},
                             block: (ReaderTranslationController, TestProvider, ImageButton, View) -> Unit) {
        Robolectric.buildActivity(AppCompatActivity::class.java).use {
            val activity = it.get()
            activity.setTheme(R.style.AppTheme_Gallery)
            it.setup()
            configureDisplay(activity)
            val content = FrameLayout(activity)
            val panel = activity.layoutInflater.inflate(R.layout.reader_translation_progress, content, false)
            content.addView(panel)
            activity.setContentView(content)
            val button = ImageButton(activity)
            val provider = TestProvider(100)
            provider.start()
            try {
                ReaderTranslationController(activity, provider, button).use { reader -> block(reader, provider, button, panel) }
            } finally {
                TranslationTasks.stopAll()
                provider.stop()
            }
        }
    }

    @Test fun initializesAndUpdatesInDefaultGalleryTheme() = checkTheme(R.style.AppTheme_Gallery)

    @Test fun initializesAndUpdatesInDarkGalleryTheme() = checkTheme(R.style.AppTheme_Gallery_Dark)

    @Test fun initializesAndUpdatesInBlackGalleryTheme() = checkTheme(R.style.AppTheme_Gallery_Black)

    private fun checkTheme(theme: Int) {
        Robolectric.buildActivity(AppCompatActivity::class.java).use { activityController ->
            val activity = activityController.get()
            activity.setTheme(theme)
            activityController.setup()
            val button = ImageButton(activity)
            val colors = activity.obtainStyledAttributes(intArrayOf(
                android.R.attr.textColorSecondary, R.attr.drawableColorPrimary))
            val inactiveColor: Int
            val activeColor: Int
            try {
                // The real reader theme uses a ColorStateList for secondary text.
                assertTrue(requireNotNull(colors.getColorStateList(0)).isStateful)
                inactiveColor = colors.getColor(0, 0)
                activeColor = colors.getColor(1, 0)
            } finally {
                colors.recycle()
            }

            // Construction runs as soon as the reader opens, before translation is enabled.
            ReaderTranslationController(activity, TestProvider(), button).use { reader ->
                assertFalse(button.isSelected)
                assertNotNull(button.drawable)
                assertEquals(inactiveColor, button.imageTintList?.defaultColor)
                reader.onPageChanged(0)
                assertEquals(activity.getString(R.string.translation_accessibility,
                    activity.getString(R.string.translation_disabled)), button.contentDescription)

                // Exercise the active tint without downloading models or starting inference.
                ReflectionHelpers.setField(reader.session, "enabled", true)
                reader.onPageChanged(1)
                assertTrue(button.isSelected)
                assertEquals(activeColor, button.imageTintList?.defaultColor)

                reader.pause()
                assertTrue(button.isSelected)
                assertEquals(activeColor, button.imageTintList?.defaultColor)
            }
        }
    }

    private class RecordingProgressBar(context: Context) :
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
        var refreshes = 0
            private set

        override fun setMax(max: Int) {
            refreshes++
            super.setMax(max)
        }

        override fun setProgress(progress: Int) {
            refreshes++
            super.setProgress(progress)
        }

        override fun setProgress(progress: Int, animate: Boolean) {
            refreshes++
            super.setProgress(progress, animate)
        }

        override fun setScaleX(scaleX: Float) {
            refreshes++
            super.setScaleX(scaleX)
        }

        override fun setContentDescription(description: CharSequence?) {
            refreshes++
            super.setContentDescription(description)
        }
    }

    private class TestProvider(private val pages: Int = 2) : GalleryProvider2() {
        override fun size() = pages
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index.jpg"
        override fun save(index: Int, file: UniFile) = false
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
