package com.hippo.ehviewer.translation

import android.app.Notification
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
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.StageActivity

/** Foreground execution for every running translation queue, including a hidden reader window. */
class TranslationService : Service() {
    private lateinit var notifications: NotificationManager
    private lateinit var channel: String
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { refreshNotifications() }
    private val taskIds = mutableMapOf<String, Int>()
    private var foreground = false
    private var refreshQueued = false
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (foreground && TranslationTasks.active().isNotEmpty()) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                handler.postDelayed(this, 60_000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        channel = "$packageName.translation"
        notifications.createNotificationChannel(NotificationChannel(channel,
            getString(R.string.translation_service_label), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        TranslationTasks.listener = {
            if (!refreshQueued) {
                refreshQueued = true
                handler.postDelayed(refresh, 250)
            }
        }
        TranslationTasks.completionListener = { session, total, failed, partial ->
            notifications.notify(taskId(session), notification(session, completed = true,
                completedTotal = total, completedFailed = failed, completedPartial = partial))
        }
        TranslationTasks.deliverCompletions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService request must be promoted even if its queue has just finished.
        if (!foreground) {
            val initial = TranslationTasks.active().firstOrNull()?.let { notification(it) }
                ?: NotificationCompat.Builder(this, channel)
                    .setSmallIcon(R.drawable.v_translate_x24)
                    .setContentTitle(getString(R.string.translation_service_label))
                    .setOnlyAlertOnce(true).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                startForeground(FOREGROUND_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(FOREGROUND_ID, initial)
            foreground = true
        }
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_IDENTITY)?.let(TranslationTasks::stop)
        }
        refreshNotifications()
        return START_NOT_STICKY
    }

    private fun refreshNotifications() {
        refreshQueued = false
        val active = TranslationTasks.active()
        if (active.isEmpty()) {
            releaseWakeLock()
            cancelOngoingNotifications()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:translation").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            handler.postDelayed(renewWakeLock, 60_000)
        }
        notifications.notify(FOREGROUND_ID, notification(active.first()))
        active.drop(1).forEach { notifications.notify(taskId(it), notification(it)) }
        taskIds.forEach { (identity, id) ->
            if (identity == active.first().identity) {
                // Rerunning this gallery replaces its earlier completion notice.
                notifications.cancel(id)
            } else if (active.drop(1).none { it.identity == identity }) {
                val entry = notifications.activeNotifications.firstOrNull { it.id == id }
                if (entry != null && entry.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                    notifications.cancel(id)
            }
        }
    }

    private fun taskId(session: GalleryTranslationSession) = taskIds.getOrPut(session.identity) {
        TranslationTasks.notificationId(session)
    }

    private fun notification(session: GalleryTranslationSession, completed: Boolean = false,
                             completedTotal: Int = session.total, completedFailed: Int = session.failed,
                             completedPartial: Int = session.partial): Notification {
        val baseText = if (completed) getString(R.string.translation_gallery_completed, completedTotal, completedFailed)
        else if (session.activePage != null) getString(R.string.translation_notification_active_progress,
            session.completed, session.total, checkNotNull(session.activePage) + 1,
            (session.pageProgress.value + 5) / 10, session.failed)
        else getString(R.string.translation_notification_progress, session.completed, session.total, session.failed)
        val partial = if (completed) completedPartial else session.partial
        val text = baseText + if (partial > 0) getString(R.string.translation_partial_count, partial) else ""
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.v_translate_x24)
            .setContentTitle(session.title)
            .setContentText(text)
            .setSubText(getString(if (session.fullGallery || completed)
                R.string.translation_full_gallery else R.string.translation_service_label))
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(!completed)
            .setAutoCancel(completed).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val open = session.galleryInfo?.takeIf { it.gid >= 0 }?.let { info ->
            val args = Bundle().apply {
                putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN)
                putLong(GalleryDetailScene.KEY_GID, info.gid)
                putString(GalleryDetailScene.KEY_TOKEN, info.token)
            }
            Intent(this, MainActivity::class.java).setAction(StageActivity.ACTION_START_SCENE)
                .putExtra(StageActivity.KEY_SCENE_NAME, GalleryDetailScene::class.java.name)
                .putExtra(StageActivity.KEY_SCENE_ARGS, args)
        } ?: Intent(this, MainActivity::class.java)
        open.data = Uri.parse("ehviewer-translation://open/${Uri.encode(session.identity)}")
        builder.setContentIntent(PendingIntent.getActivity(this, taskId(session), open, PENDING_FLAGS))
        if (!completed) {
            builder.setProgress(session.total.coerceAtLeast(1), session.completed, session.total <= 0)
            val stop = Intent(this, TranslationService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_IDENTITY, session.identity)
                .setData(Uri.parse("ehviewer-translation://stop/${Uri.encode(session.identity)}"))
            builder.addAction(R.drawable.ic_pause_x24, getString(R.string.translation_notification_stop),
                PendingIntent.getService(this, taskId(session), stop, PENDING_FLAGS))
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        TranslationTasks.stopActive()
        releaseWakeLock()
        cancelOngoingNotifications()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        TranslationTasks.listener = null
        TranslationTasks.completionListener = null
        handler.removeCallbacksAndMessages(null)
        // An unexpected service shutdown must not leave unprotected native work running.
        if (foreground) TranslationTasks.stopActive()
        cancelOngoingNotifications()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the reader task is an exit. Explicit full-gallery jobs belong to the service.
        TranslationTasks.readerTaskRemoved()
        refreshNotifications()
        super.onTaskRemoved(rootIntent)
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun cancelOngoingNotifications() {
        notifications.activeNotifications.filter {
            it.id in taskIds.values && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        }.forEach { notifications.cancel(it.id) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_STOP = "translation.stop"
        internal const val EXTRA_IDENTITY = "translation.identity"
        private const val FOREGROUND_ID = 0x7300
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
