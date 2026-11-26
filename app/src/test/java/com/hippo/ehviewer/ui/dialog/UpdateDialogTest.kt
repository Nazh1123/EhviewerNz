package com.hippo.ehviewer.ui.dialog

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.Spanned
import android.text.style.ClickableSpan
import androidx.appcompat.app.AlertDialog
import com.alibaba.fastjson.JSON
import com.hippo.ehviewer.R
import com.hippo.ehviewer.updater.AppUpdateDownloadService
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.ehviewer.updater.ReleaseNotes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class UpdateDialogTest {
    private fun showMethods(apk: Boolean): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val content = JSON.parseObject("""{
            "title":"Update", "content":["Changes"],
            "releaseUrl":"https://example.com/releases/3"
        }""")
        if (apk) content[AppUpdater.FILE_DOWNLOAD_URL] = "https://example.com/app.apk"
        val data = JSON.parseObject("""{"mustUpdate":false,"version":"3.0"}""")
        data[AppUpdater.UPDATE_CONTENT] = content
        UpdateDialog(activity).showUpdateDialog(data)
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        return activity
    }

    @Test fun releaseChoiceUsesReleasePage() {
        val activity = showMethods(true)
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        ShadowLooper.idleMainLooper()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/releases/3", intent.data.toString())
    }

    @Test fun directChoiceStartsNotificationServiceWithoutOpeningProgressActivity() {
        val activity = showMethods(true)
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertNull(shadowOf(activity).nextStartedActivity)
        val intent = shadowOf(activity).nextStartedService
        assertEquals(AppUpdateDownloadService::class.java.name, intent.component!!.className)
        assertEquals("https://example.com/app.apk", intent.getStringExtra(AppUpdater.FILE_DOWNLOAD_URL))
    }

    @Test fun missingApkDisablesDirectChoiceAndKeepsReleaseAvailable() {
        showMethods(false)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
    }

    @Test fun markdownBodyIsRenderedInScrollableViewAndUpdateButtonsRemainAvailable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val content = JSON.parseObject("""{"title":"Update","releaseUrl":"https://example.com/releases/3"}""")
        content[AppUpdater.CONTENT] = "## Changes\n\n**New** feature"
        val data = JSON.parseObject("""{"mustUpdate":false}""")
        data[AppUpdater.UPDATE_CONTENT] = content
        UpdateDialog(activity).showUpdateDialog(data)
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val body = dialog.findViewById<FrameLayout>(androidx.appcompat.R.id.custom)!!.getChildAt(0) as UpdateNotesView
        assertEquals("Changes\n\nNew feature", body.notes.text.toString())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
    }

    @Test fun olderVersionsHaveTheirOwnMarkdownSectionsAndReleaseLinks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val latest = JSON.parseObject("""{"name":"3.0","body":"```\nLatest change"}""")
        val data = AppUpdater.createDialogData(latest, "3.0", "https://example.com/releases/3")
        UpdateDialog(activity).showUpdateDialog(data, listOf(
            ReleaseNotes("2.0", "[Details](#changes)", "https://example.com/releases/2"),
            ReleaseNotes("1.5", "**Earlier** change", "https://example.com/releases/1.5")
        ))
        ShadowLooper.idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val body = dialog.findViewById<FrameLayout>(androidx.appcompat.R.id.custom)!!.getChildAt(0) as UpdateNotesView
        val sections = body.getChildAt(0) as LinearLayout
        val sectionText = (0 until sections.childCount).map { (sections.getChildAt(it) as TextView).text.toString() }
        assertTrue(sectionText.first().contains("Latest change"))
        assertEquals(listOf("2.0", "Details", "1.5", "Earlier change"), sectionText.drop(1))
        val olderNotes = sections.getChildAt(2) as TextView
        val markdown = olderNotes.text as Spanned
        markdown.getSpans(0, markdown.length, ClickableSpan::class.java).single().onClick(olderNotes)
        assertEquals("https://example.com/releases/2#changes", shadowOf(activity).nextStartedActivity.data.toString())
    }
}
