package com.hippo.ehviewer.updater

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.hippo.ehviewer.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDownloadManager
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34], manifest = Config.NONE)
class AppUpdateDownloadServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val prefs get() = AppUpdateDownload.pending(app)
    private val id get() = prefs.getLong("id", -1)
    private lateinit var controller: ServiceController<AppUpdateDownloadService>
    private val notification get() = shadowOf(notifications).getNotification(AppUpdateDownloadService.NOTIFICATION_ID)
    private fun request(): ShadowDownloadManager.ShadowRequest = shadowOf(shadowOf(manager).getRequest(id))
    private fun tick() = ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
    private fun start() {
        controller.get().onStartCommand(Intent()
            .putExtra(AppUpdater.FILE_DOWNLOAD_URL, "https://example.com/update.apk")
            .putExtra(AppUpdater.RELEASE_URL, "https://example.com/releases/3"), 0, 1)
        ShadowLooper.idleMainLooper()
    }

    @Before fun setUp() {
        prefs.edit().clear().commit()
        controller = Robolectric.buildService(AppUpdateDownloadService::class.java).create()
    }

    @After fun cleanup() { controller.destroy() }

    @Test fun startsOneAppProgressNotificationAndUsesHiddenSystemTransfer() {
        start()
        assertEquals(1, notifications.activeNotifications.size)
        assertEquals(AppUpdateDownloadService.NOTIFICATION_ID, shadowOf(controller.get()).lastForegroundNotificationId)
        assertEquals(R.drawable.ic_stat_download, notification.smallIcon.resId)
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(DownloadManager.Request.VISIBILITY_HIDDEN, request().notificationVisibility)
        assertEquals("https://example.com/update.apk", request().uri.toString())
        assertEquals("application/vnd.android.package-archive", request().mimeType)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun notificationTracksPercentageAndPausedNetworkState() {
        start()
        request().apply {
            status = DownloadManager.STATUS_RUNNING
            totalSize = 1000
            bytesSoFar = 420
        }
        tick()
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(42, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        request().status = DownloadManager.STATUS_PAUSED
        tick()
        assertEquals(app.getString(R.string.app_update_waiting_network),
            notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun completedNotificationInstallsInForegroundAndRemainsAfterServiceStops() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        start()
        request().status = DownloadManager.STATUS_SUCCESSFUL
        tick()
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(Notification.CATEGORY_STATUS, notification.category)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        val install = shadowOf(activity.get()).nextStartedActivity
        assertEquals(AppUpdateActivity::class.java.name, install.component!!.className)
        assertEquals(id, install.getLongExtra(AppUpdateDownload.EXTRA_DOWNLOAD_ID, -1))
        assertFalse(prefs.getBoolean("autoInstall", true))
        activity.pause().resume()
        ShadowLooper.idleMainLooper()
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        assertNotNull(notification.contentIntent)
        activity.pause().stop().destroy()
    }

    @Test fun backgroundCompletionInstallsOnlyWhenAppResumes() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        start()
        activity.pause()
        request().status = DownloadManager.STATUS_SUCCESSFUL
        tick()
        assertNull(shadowOf(activity.get()).nextStartedActivity)
        val notificationTarget = shadowOf(notification.contentIntent).savedIntent
        assertEquals(AppUpdateActivity::class.java.name, notificationTarget.component!!.className)
        activity.resume()
        ShadowLooper.idleMainLooper()
        assertNotNull(shadowOf(activity.get()).nextStartedActivity)
        activity.pause().stop().destroy()
    }

    @Test fun notificationCancelRemovesDownloadAndNotification() {
        start()
        val cancel = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(AppUpdateDownloadService.ACTION_CANCEL, cancel.action)
        controller.get().onStartCommand(cancel, 0, 2)
        assertEquals(0, shadowOf(manager).requestCount)
        assertEquals(-1L, id)
        assertNull(notification)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun failureKeepsRetryAndReleaseActionsAndRetryReplacesFailedTransfer() {
        start()
        val failedId = id
        request().status = DownloadManager.STATUS_FAILED
        tick()
        assertEquals(Notification.CATEGORY_ERROR, notification.category)
        assertEquals(2, notification.actions.size)
        val retry = shadowOf(notification.actions.first().actionIntent)
        assertTrue(retry.isForegroundService)
        val release = shadowOf(notification.actions.last().actionIntent).savedIntent
        assertEquals("https://example.com/releases/3", release.data.toString())
        controller.get().onStartCommand(retry.savedIntent, 0, 2)
        ShadowLooper.idleMainLooper()
        assertNotEquals(failedId, id)
        assertEquals(1, shadowOf(manager).requestCount)
        assertEquals("https://example.com/releases/3", prefs.getString("release", null))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun serviceRestartReusesTransferAndStaleCancelCannotRemoveIt() {
        start()
        val original = id
        controller.destroy()
        controller = Robolectric.buildService(AppUpdateDownloadService::class.java).create()
        controller.get().onStartCommand(null, 0, 2)
        ShadowLooper.idleMainLooper()
        assertEquals(original, id)
        assertEquals(1, shadowOf(manager).requestCount)
        controller.get().onStartCommand(Intent().setAction(AppUpdateDownloadService.ACTION_CANCEL)
            .putExtra(AppUpdateDownload.EXTRA_DOWNLOAD_ID, original + 100), 0, 3)
        assertEquals(1, shadowOf(manager).requestCount)
        assertEquals(original, id)
    }
}
