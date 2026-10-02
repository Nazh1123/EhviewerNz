package com.hippo.ehviewer.translation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2

/** Main-thread registry shared by readers and the foreground service. */
internal object TranslationTasks {
    private val sessions = linkedMapOf<String, GalleryTranslationSession>()
    private val notificationIds = mutableMapOf<String, Int>()
    private var nextNotificationId = 0x7301
    val scheduler = TranslationScheduler()
    var listener: (() -> Unit)? = null
    var completionListener: ((GalleryTranslationSession, Int, Int, Int) -> Unit)? = null
    private val pendingCompletions = mutableListOf<Completion>()
    private var queuesWereActive = false
    private var queuesIdleSince = SystemClock.elapsedRealtime()
    private data class Completion(val session: GalleryTranslationSession, val total: Int, val failed: Int, val partial: Int)

    fun acquire(context: Context, readerProvider: GalleryProvider2, info: GalleryInfo?): GalleryTranslationSession {
        val identity = info?.let { "gallery:${it.gid}" } ?: readerProvider.translationIdentity
            ?: "reader:${System.identityHashCode(readerProvider)}"
        return sessions.getOrPut(identity) {
            val source = readerProvider.createTranslationProvider(context.applicationContext)
            GalleryTranslationSession(context.applicationContext, source ?: readerProvider,
                source != null, identity, info, readerProvider.size())
        }
    }

    fun active() = sessions.values.filter { it.enabled && it.working }
    fun fullGalleryTasks() = active().filter { it.fullGallery }
    fun notificationId(session: GalleryTranslationSession) =
        notificationIds.getOrPut(session.identity) { nextNotificationId++ }

    fun wake(context: Context, session: GalleryTranslationSession) {
        if (sessions[session.identity] !== session || !session.enabled) return
        // A fresh start also covers the interval between stopSelf() and onDestroy().
        ContextCompat.startForegroundService(context, Intent(context, TranslationService::class.java))
    }

    fun stop(identity: String) {
        sessions[identity]?.let {
            it.disable()
            if (it.reader == null) release(it)
        }
    }

    fun stopAll() = sessions.keys.toList().forEach(::stop)
    fun stopActive() = active().map { it.identity }.forEach(::stop)
    fun releaseIdleModels() = sessions.values.forEach { it.releaseIdleModels() }
    internal fun takeIdleModels(except: GalleryTranslationSession?) = sessions.values
        .filter { it !== except }.mapNotNull { it.takeIdleModels() }
    fun readerTaskRemoved() = sessions.values.toList().forEach { session ->
        session.leaveReader()
        if (!session.enabled) release(session)
    }
    fun changed() {
        val queued = sessions.values.any { it.enabled && it.working }
        if (queued || queuesWereActive) queuesIdleSince = SystemClock.elapsedRealtime()
        queuesWereActive = queued
        listener?.invoke()
    }

    // Main owns all task admission. Any gallery queue postpones model expiry.
    fun modelIdleRemainingMillis(): Long = if (sessions.values.any { it.enabled && it.working }) 15_000
        else (15_000 - (SystemClock.elapsedRealtime() - queuesIdleSince)).coerceAtLeast(0)

    fun completed(session: GalleryTranslationSession, total: Int, failed: Int) {
        val partial = (0 until total).count { session.states[it] == com.hippo.ehviewer.R.string.translation_partial }
        val notify = completionListener
        if (notify == null) pendingCompletions.add(Completion(session, total, failed, partial))
        else notify(session, total, failed, partial)
    }

    fun deliverCompletions() {
        val notify = completionListener ?: return
        val pending = pendingCompletions.toList()
        pendingCompletions.clear()
        pending.forEach { notify(it.session, it.total, it.failed, it.partial) }
    }

    fun release(session: GalleryTranslationSession) {
        if (sessions[session.identity] === session) sessions.remove(session.identity)
        session.close()
    }
}
