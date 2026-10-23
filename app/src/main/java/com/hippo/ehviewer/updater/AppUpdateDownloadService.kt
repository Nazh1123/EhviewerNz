package com.hippo.ehviewer.updater

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.dialog.UpdateDialog
import java.util.UUID

/** Uses the same notification presentation as gallery downloads; DownloadManager owns the transfer. */
class AppUpdateDownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val downloads by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val notifications by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val pending by lazy { AppUpdateDownload.pending(this) }
    private val channelId get() = "$packageName.app_update"
    private var downloadId = -1L

    override fun onCreate() {
        super.onCreate()
        AppUpdateDownload.initialize(application)
        notifications.createNotificationChannel(NotificationChannel(channelId,
            getString(R.string.app_update_download_title), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (intent.getLongExtra(AppUpdateDownload.EXTRA_DOWNLOAD_ID, -1) == pending.getLong("id", -1)) {
                handler.removeCallbacks(checkProgress)
                val id = pending.getLong("id", -1)
                if (id != -1L) downloads.remove(id)
                pending.edit().clear().apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifications.cancel(NOTIFICATION_ID)
                stopSelf()
            } else if (downloadId == -1L) stopSelf(startId)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(checkProgress)
        // Promote immediately, including retry/restart paths which can reach a terminal state below.
        val initial = progressNotification(getString(R.string.app_update_downloading), 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, initial)
        try {
            val retry = intent?.action == ACTION_RETRY
            val url = intent?.getStringExtra(AppUpdater.FILE_DOWNLOAD_URL) ?: pending.getString("url", null)
            val release = intent?.getStringExtra(AppUpdater.RELEASE_URL) ?: pending.getString("release", null)
                ?: UpdateDialog.GITHUB_RELEASE_URL
            require(url?.startsWith("https://") == true) { "Missing APK download URL" }
            val previousId = pending.getLong("id", -1)
            val staleRetry = retry && intent?.getLongExtra(AppUpdateDownload.EXTRA_DOWNLOAD_ID, -1) != previousId
            if (staleRetry) {
                downloadId = previousId
            } else {
                val sameRelease = pending.getString("url", null) == url
                downloadId = if (sameRelease && !retry) previousId else -1
                if (downloadId == -1L || !isReusable(downloadId)) {
                    if (previousId != -1L) downloads.remove(previousId)
                    // Preserve retry information even if the system refuses to enqueue the transfer.
                    pending.edit().clear().putString("url", url).putString("release", release)
                        .putBoolean("autoInstall", true).apply()
                    downloadId = downloads.enqueue(DownloadManager.Request(url!!.toUri())
                        .setTitle(getString(R.string.app_update_download_title))
                        .setMimeType("application/vnd.android.package-archive")
                        .addRequestHeader("User-Agent", "EhViewerNz/${BuildConfig.VERSION_NAME}")
                        // The foreground service shows the app's progress notification instead.
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                        .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                            "updates/${UUID.randomUUID()}.apk"))
                    pending.edit().putLong("id", downloadId).apply()
                } else if (intent != null) {
                    pending.edit().putBoolean("autoInstall", true).apply()
                }
            }
            handler.post(checkProgress)
        } catch (e: Exception) {
            Analytics.recordException(e)
            finishNotification(false)
        }
        return START_STICKY
    }

    private fun isReusable(id: Long): Boolean = downloads.query(
        DownloadManager.Query().setFilterById(id)).use { cursor ->
        cursor != null && cursor.moveToFirst() && cursor.getInt(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_FAILED
    }

    private val checkProgress = object : Runnable {
        override fun run() {
            try {
                downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (cursor == null || !cursor.moveToFirst()) {
                        finishNotification(false)
                        return
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            pending.edit().putBoolean("ready", true).apply()
                            finishNotification(true)
                            AppUpdateDownload.installIfResumed(this@AppUpdateDownloadService)
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            finishNotification(false)
                            return
                        }
                    }
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val percent = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0
                    val text = when {
                        status == DownloadManager.STATUS_PAUSED -> getString(R.string.app_update_waiting_network)
                        total > 0 -> getString(R.string.app_update_progress, percent)
                        else -> getString(R.string.app_update_downloading)
                    }
                    notifications.notify(NOTIFICATION_ID, progressNotification(text, percent, total <= 0))
                }
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
                Analytics.recordException(e)
                finishNotification(false)
            }
        }
    }

    private fun builder() = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle(getString(R.string.app_update_download_title))
        .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)

    private fun progressNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val builder = builder().setOngoing(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentText(text).setProgress(100, percent, indeterminate)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PENDING_FLAGS))
        if (downloadId != -1L) builder.addAction(R.drawable.ic_pause_x24, getString(R.string.cancel),
            serviceAction(ACTION_CANCEL, false))
        return builder.build()
    }

    private fun finishNotification(success: Boolean) {
        handler.removeCallbacks(checkProgress)
        if (!success) pending.edit().putBoolean("ready", false).putBoolean("autoInstall", false).apply()
        val text = getString(if (success) R.string.app_update_ready else R.string.app_update_download_failed)
        val builder = builder().setSmallIcon(R.drawable.ic_stat_download_done)
            .setCategory(if (success) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false).setAutoCancel(false)
        if (success) {
            val install = PendingIntent.getActivity(this, 1,
                AppUpdateDownload.installIntent(this, downloadId), PENDING_FLAGS)
            builder.setContentIntent(install).addAction(R.drawable.ic_stat_download_done,
                getString(R.string.app_update_install), install)
        } else {
            val retry = serviceAction(ACTION_RETRY, true)
            builder.setContentIntent(retry).addAction(R.drawable.ic_stat_download,
                getString(R.string.app_update_retry), retry)
        }
        val release = pending.getString("release", null) ?: UpdateDialog.GITHUB_RELEASE_URL
        builder.addAction(0, getString(R.string.app_update_open_release), PendingIntent.getActivity(this, 2,
            Intent(Intent.ACTION_VIEW, release.toUri()), PENDING_FLAGS))
        // Detach before replacing it, so stopping the service retains the terminal notification.
        stopForeground(STOP_FOREGROUND_DETACH)
        notifications.notify(NOTIFICATION_ID, builder.build())
        stopSelf()
    }

    private fun serviceAction(action: String, foreground: Boolean): PendingIntent {
        val intent = Intent(this, AppUpdateDownloadService::class.java).setAction(action)
            .putExtra(AppUpdateDownload.EXTRA_DOWNLOAD_ID, downloadId)
        return if (foreground) PendingIntent.getForegroundService(this, 3, intent, PENDING_FLAGS)
        else PendingIntent.getService(this, 4, intent, PENDING_FLAGS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkProgress)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 0x41505550
        const val ACTION_CANCEL = "app_update_cancel"
        const val ACTION_RETRY = "app_update_retry"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
