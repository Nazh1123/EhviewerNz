package com.hippo.ehviewer.updater

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.hippo.ehviewer.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDownloadManager
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class AppUpdateActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
    private val manager get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val downloadId get() = prefs.getLong("id", -1)

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun create(): ActivityController<AppUpdateActivity> {
        val intent = Intent(app, AppUpdateActivity::class.java)
            .putExtra(AppUpdater.FILE_DOWNLOAD_URL, "https://example.com/update.apk")
            .putExtra(AppUpdater.RELEASE_URL, "https://example.com/releases/3")
        return Robolectric.buildActivity(AppUpdateActivity::class.java, intent).apply {
            get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
            setup()
        }
    }

    private fun request(): ShadowDownloadManager.ShadowRequest =
        shadowOf(shadowOf(manager).getRequest(downloadId))

    private fun tick() = ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)

    @Test fun downloadUsesApkAssetAndRecreationReusesTask() {
        val controller = create()
        assertEquals("https://example.com/update.apk", request().uri.toString())
        assertEquals("application/vnd.android.package-archive", request().mimeType)
        // Robolectric uses the host filesystem, including Windows separators.
        assertTrue(request().destination.path!!.replace('\\', '/').contains("/Download/updates/"))
        val id = downloadId
        controller.recreate()
        assertEquals(id, downloadId)
        assertEquals(1, shadowOf(manager).requestCount)
        controller.pause().stop().destroy()
    }

    @Test fun completedDownloadOpensInstallerWithReadGrantOnlyOnce() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val controller = create()
        request().status = DownloadManager.STATUS_SUCCESSFUL
        tick()
        val installer = shadowOf(controller.get()).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, installer.action)
        assertEquals("content", installer.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", installer.type)
        assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        controller.pause().resume()
        tick()
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }

    @Test fun permissionGrantReturnsToInstallerWithoutRedownload() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val controller = create()
        request().status = DownloadManager.STATUS_SUCCESSFUL
        tick()
        val permission = shadowOf(controller.get()).nextStartedActivityForResult.intent
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
        assertEquals("package:${controller.get().packageName}", permission.data.toString())
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(controller.get()).receiveResult(permission, 0, null)
        val installer = shadowOf(controller.get()).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, installer.action)
        assertEquals(1, shadowOf(manager).requestCount)
        controller.pause().stop().destroy()
    }

    @Test fun denialDoesNotLoopAndManualInstallCanRetry() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val controller = create()
        request().status = DownloadManager.STATUS_SUCCESSFUL
        tick()
        val permission = shadowOf(controller.get()).nextStartedActivityForResult.intent
        shadowOf(controller.get()).receiveResult(permission, 0, null)
        controller.pause().resume()
        tick()
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.get().findViewById<Button>(R.id.app_update_action).performClick()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            shadowOf(controller.get()).nextStartedActivityForResult.intent.action)
        controller.pause().stop().destroy()
    }

    @Test fun backgroundCompletionWaitsUntilResumed() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val controller = create()
        controller.pause()
        request().status = DownloadManager.STATUS_SUCCESSFUL
        tick()
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.resume()
        ShadowLooper.idleMainLooper()
        assertEquals(Intent.ACTION_VIEW,
            shadowOf(controller.get()).nextStartedActivityForResult.intent.action)
        controller.pause().stop().destroy()
    }

    @Test fun failureOffersRetryAndCancelRemovesPartialDownload() {
        val controller = create()
        request().status = DownloadManager.STATUS_FAILED
        tick()
        assertEquals(controller.get().getString(R.string.app_update_download_failed),
            controller.get().findViewById<TextView>(R.id.app_update_message).text.toString())
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        val oldId = downloadId
        controller.get().findViewById<Button>(R.id.app_update_action).performClick()
        assertNotEquals(oldId, downloadId)
        assertEquals(1, shadowOf(manager).requestCount)
        controller.get().findViewById<Button>(R.id.app_update_cancel).performClick()
        assertTrue(controller.get().isFinishing)
        assertEquals(0, shadowOf(manager).requestCount)
        assertEquals(-1L, downloadId)
        controller.pause().stop().destroy()
    }
}
