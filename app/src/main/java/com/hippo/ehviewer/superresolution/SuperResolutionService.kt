package com.hippo.ehviewer.superresolution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.MainActivity

/** All running queues have foreground protection; full-gallery jobs outlive their reader. */
class SuperResolutionService : Service() {
    private lateinit var notifications: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var refreshQueued = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val channel get() = "$packageName.super-resolution"
    private val refresh = Runnable { update() }
    private val renew = object : Runnable {
        override fun run() {
            if (foreground) { wakeLock?.acquire(10 * 60_000L); handler.postDelayed(this, 60_000) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channel, getString(R.string.sr_title), NotificationManager.IMPORTANCE_LOW))
        SuperResolutionTasks.listener = {
            if (!refreshQueued) {
                refreshQueued = true
                handler.postDelayed(refresh, 200)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
            foreground = true
        }
        if (intent?.action == STOP) SuperResolutionTasks.stopAll()
        update()
        return START_NOT_STICKY
    }
    private fun buildNotification(): android.app.Notification {
        val tasks = SuperResolutionTasks.active()
        val first = tasks.firstOrNull()
        val title = first?.title ?: getString(R.string.sr_title)
        val text = tasks.joinToString("\n") {
            it.title + " · " + getString(R.string.sr_queue_progress, it.completed, it.total) + " · " +
                (it.message ?: getString(R.string.sr_title))
        }.ifEmpty { getString(R.string.sr_title) }
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, ID, Intent(this, SuperResolutionService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.v_super_resolution_x24)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(getString(R.string.sr_title)).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(open).setProgress(first?.total?.coerceAtLeast(1) ?: 1, first?.completed ?: 0, first == null)
            .addAction(R.drawable.ic_pause_x24, getString(R.string.sr_stop_all), stop).build()
    }
    private fun update() {
        refreshQueued = false
        if (SuperResolutionTasks.active().isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); releaseWakeLock()
        } else {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:super-resolution").apply {
                    setReferenceCounted(false); acquire(10 * 60_000L)
                }
                handler.postDelayed(renew, 60_000)
            }
            notifications.notify(ID, buildNotification())
        }
    }
    private fun releaseWakeLock() { handler.removeCallbacks(renew); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onTimeout(startId: Int, fgsType: Int) { SuperResolutionTasks.stopAll(); update() }
    override fun onTaskRemoved(rootIntent: Intent?) { SuperResolutionTasks.leaveReaders(); update(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        SuperResolutionTasks.listener = null
        handler.removeCallbacksAndMessages(null)
        if (foreground) SuperResolutionTasks.stopAll()
        releaseWakeLock(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val ID = 0x7400; private const val STOP = "super-resolution.stop" }
}
