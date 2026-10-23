package com.hippo.ehviewer.ui.dialog

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.alibaba.fastjson.JSON
import com.hippo.ehviewer.R
import com.hippo.ehviewer.updater.AppUpdateActivity
import com.hippo.ehviewer.updater.AppUpdater
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

    @Test fun directChoicePassesApkToDownloadActivity() {
        val activity = showMethods(true)
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(AppUpdateActivity::class.java.name, intent.component!!.className)
        assertEquals("https://example.com/app.apk", intent.getStringExtra(AppUpdater.FILE_DOWNLOAD_URL))
    }

    @Test fun missingApkDisablesDirectChoiceAndKeepsReleaseAvailable() {
        showMethods(false)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
    }
}
