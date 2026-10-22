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
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.StageActivity

/** One foreground notification protects translation and SR across the shared gallery queue. */
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
            if (foreground && hasActiveWork()) {
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
            getString(R.string.gallery_processing_service), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        TranslationTasks.listener = {
            if (!refreshQueued) {
                refreshQueued = true
                handler.postDelayed(refresh, 250)
            }
        }
        SuperResolutionTasks.listener = TranslationTasks.listener
        TranslationTasks.completionListener = { session, total, failed, partial ->
            notifications.notify(taskId(session), notification(session, completed = true,
                completedTotal = total, completedFailed = failed, completedPartial = partial))
        }
        TranslationTasks.deliverCompletions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService request must be promoted even if its queue has just finished.
        if (!foreground) {
            val initial = progressNotification()
                ?: NotificationCompat.Builder(this, channel)
                    .setSmallIcon(R.drawable.v_translate_x24)
                    .setContentTitle(getString(R.string.gallery_processing_service))
                    .setOnlyAlertOnce(true).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                startForeground(FOREGROUND_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(FOREGROUND_ID, initial)
            foreground = true
        }
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_IDENTITY)?.let { identity ->
                TranslationTasks.stop(identity)
                SuperResolutionTasks.stop(identity)
            }
        }
        refreshNotifications()
        return START_NOT_STICKY
    }

    private fun refreshNotifications() {
        refreshQueued = false
        val active = TranslationTasks.active()
        val enhancements = SuperResolutionTasks.active()
        if (active.isEmpty() && enhancements.isEmpty()) {
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
        progressNotification()?.let { notifications.notify(FOREGROUND_ID, it) }
        val running = (active.map { it.identity } + enhancements.map { it.identity }).toSet()
        taskIds.forEach { (identity, id) ->
            if (identity in running) {
                // Rerunning this gallery replaces its earlier completion notice.
                notifications.cancel(id)
            }
        }
        cancelOngoingNotifications()
    }

    private fun hasActiveWork() = TranslationTasks.active().isNotEmpty() || SuperResolutionTasks.active().isNotEmpty()

    private fun stopActiveWork() {
        TranslationTasks.stopActive()
        SuperResolutionTasks.stopAll()
    }

    private fun taskId(session: GalleryTranslationSession) = taskId(session.identity)
    private fun taskId(identity: String) = taskIds.getOrPut(identity) { TranslationTasks.notificationId(identity) }

    private fun progressNotification(): Notification? {
        val translations = TranslationTasks.active()
        val enhancements = SuperResolutionTasks.active()
        val identities = (translations.map { it.identity } + enhancements.map { it.identity }).toSet()
        if (identities.isEmpty()) return null
        val order = (GalleryPageWorkRegistry.fullGalleryOrder() +
            translations.filter { it.fullGallery }.map { it.identity } +
            enhancements.filter { it.fullGallery }.map { it.identity }).distinct()
        val identity = translations.firstOrNull { it.activePage != null && it.browsing && it.reader != null }?.identity
            ?: order.firstOrNull { it in identities } ?: identities.first()
        val queued = if (order.isEmpty()) null else (order.size - 1).coerceAtLeast(0)
        val translation = translations.firstOrNull { it.identity == identity }
        val enhancement = enhancements.firstOrNull { it.identity == identity }
        if (translation != null && (translation.activePage != null || enhancement?.activePage == null))
            return notification(translation, queuedGalleries = queued)
        val session = checkNotNull(enhancement)
        val progress = getString(R.string.sr_queue_progress, session.completed, session.total)
        val status = session.activePage?.let { getString(R.string.sr_page_progress, it + 1, session.progress) }
            ?: session.message
        val text = queueText(progress + (status?.let { " · $it" } ?: ""), queued)
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.v_super_resolution_x24).setContentTitle(session.title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(getString(R.string.sr_title)).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openGallery(session.identity, session.galleryInfo))
            .setProgress(session.total.coerceAtLeast(1), session.completed, session.total <= 0)
            .addAction(stopAction(session.identity)).build()
    }

    private fun queueText(text: String, queued: Int?) = text +
        (queued?.let { " · " + getString(R.string.gallery_processing_queued, it) } ?: "")

    private fun notification(session: GalleryTranslationSession, completed: Boolean = false,
                             completedTotal: Int = session.total, completedFailed: Int = session.failed,
                             completedPartial: Int = session.partial, queuedGalleries: Int? = null): Notification {
        val baseText = if (completed) getString(R.string.translation_gallery_completed, completedTotal, completedFailed)
        else if (session.activePage != null) getString(R.string.translation_notification_active_progress,
            session.completed, session.total, checkNotNull(session.activePage) + 1,
            (session.pageProgress.value + 5) / 10, session.failed)
        else getString(R.string.translation_notification_progress, session.completed, session.total, session.failed)
        val partial = if (completed) completedPartial else session.partial
        val text = queueText(baseText + (if (partial > 0) getString(R.string.translation_partial_count, partial) else ""), queuedGalleries)
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.v_translate_x24)
            .setContentTitle(session.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(getString(if (session.fullGallery || completed)
                R.string.translation_full_gallery else R.string.translation_service_label))
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(!completed)
            .setAutoCancel(completed).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        builder.setContentIntent(openGallery(session.identity, session.galleryInfo))
        if (!completed) {
            builder.setProgress(session.total.coerceAtLeast(1), session.completed, session.total <= 0)
            builder.addAction(stopAction(session.identity))
        }
        return builder.build()
    }

    private fun openGallery(identity: String, galleryInfo: GalleryInfo?): PendingIntent {
        val open = galleryInfo?.takeIf { it.gid >= 0 }?.let { info ->
            val args = Bundle().apply {
                putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN)
                putLong(GalleryDetailScene.KEY_GID, info.gid)
                putString(GalleryDetailScene.KEY_TOKEN, info.token)
            }
            Intent(this, MainActivity::class.java).setAction(StageActivity.ACTION_START_SCENE)
                .putExtra(StageActivity.KEY_SCENE_NAME, GalleryDetailScene::class.java.name)
                .putExtra(StageActivity.KEY_SCENE_ARGS, args)
        } ?: Intent(this, MainActivity::class.java)
        open.data = Uri.parse("ehviewer-translation://open/${Uri.encode(identity)}")
        return PendingIntent.getActivity(this, taskId(identity), open, PENDING_FLAGS)
    }

    private fun stopAction(identity: String): NotificationCompat.Action {
        val stop = Intent(this, TranslationService::class.java).setAction(ACTION_STOP)
            .putExtra(EXTRA_IDENTITY, identity)
            .setData(Uri.parse("ehviewer-translation://stop/${Uri.encode(identity)}"))
        return NotificationCompat.Action(R.drawable.ic_pause_x24, getString(R.string.gallery_processing_stop),
            PendingIntent.getService(this, taskId(identity), stop, PENDING_FLAGS))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopActiveWork()
        releaseWakeLock()
        cancelOngoingNotifications()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        TranslationTasks.listener = null
        SuperResolutionTasks.listener = null
        TranslationTasks.completionListener = null
        handler.removeCallbacksAndMessages(null)
        // An unexpected service shutdown must not leave unprotected native work running.
        if (foreground) stopActiveWork()
        cancelOngoingNotifications()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the reader task is an exit. Explicit full-gallery jobs belong to the service.
        TranslationTasks.readerTaskRemoved()
        SuperResolutionTasks.leaveReaders()
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
            it.id != FOREGROUND_ID && it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
                (it.notification.channelId == channel || it.notification.channelId == "$packageName.super-resolution")
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
