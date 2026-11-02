package com.hippo.ehviewer.updater

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
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
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34], manifest = Config.NONE)
class AppUpdateActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var id = -1L

    @Before fun setUp() {
        AppUpdateDownload.initialize(app)
        id = manager.enqueue(DownloadManager.Request("https://example.com/update.apk".toUri()))
        shadowOf(shadowOf(manager).getRequest(id)).status = DownloadManager.STATUS_SUCCESSFUL
        AppUpdateDownload.pending(app).edit().clear().putLong("id", id).putBoolean("ready", true).commit()
    }

    private fun create(downloadId: Long = id): ActivityController<AppUpdateActivity> =
        Robolectric.buildActivity(AppUpdateActivity::class.java,
            AppUpdateDownload.installIntent(app, downloadId)).setup()

    @Test fun completedDownloadOpensInstallerWithReadGrantWithoutProgressScreen() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val controller = create()
        val activity = controller.get()
        val installer = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_VIEW, installer.action)
        assertEquals("content", installer.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", installer.type)
        assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, activity.findViewById<android.view.ViewGroup>(android.R.id.content).childCount)
        controller.pause().resume()
        ShadowLooper.idleMainLooper()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        shadowOf(activity).receiveResult(installer, 0, null)
        assertTrue(activity.isFinishing)
        assertEquals(1, shadowOf(manager).requestCount)
        controller.pause().stop().destroy()
    }

    @Test fun permissionGrantReturnsToInstallerAndDenialReturnsToApp() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val controller = create()
        val activity = controller.get()
        val permission = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
        assertEquals("package:${activity.packageName}", permission.data.toString())
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(activity).receiveResult(permission, 0, null)
        assertEquals(Intent.ACTION_VIEW, shadowOf(activity).nextStartedActivityForResult.intent.action)
        controller.pause().stop().destroy()

        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val denied = create()
        val permissionAgain = shadowOf(denied.get()).nextStartedActivityForResult.intent
        shadowOf(denied.get()).receiveResult(permissionAgain, 0, null)
        assertTrue(denied.get().isFinishing)
        assertNull(shadowOf(denied.get()).nextStartedActivityForResult)
        denied.pause().stop().destroy()
    }

    @Test fun staleNotificationCannotInstallAnotherDownload() {
        val controller = create(id + 1)
        assertTrue(controller.get().isFinishing)
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }

    @Test fun recreationWhilePermissionScreenIsOpenDoesNotRelaunchPermission() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val controller = create()
        assertNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.recreate()
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }
}
