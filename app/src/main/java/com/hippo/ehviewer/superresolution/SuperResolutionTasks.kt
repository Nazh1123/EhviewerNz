package com.hippo.ehviewer.superresolution

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.translation.TranslationService

/** Main-thread registry shared by detachable reader controls and the foreground service. */
internal object SuperResolutionTasks {
    private val sessions = linkedMapOf<String, GallerySuperResolutionSession>()
    var listener: (() -> Unit)? = null
    fun acquire(context: Context, provider: GalleryProvider2, info: GalleryInfo?): GallerySuperResolutionSession {
        val identity = info?.let { "gallery:${it.gid}" } ?: provider.translationIdentity
            ?: "reader:${System.identityHashCode(provider)}"
        return sessions.getOrPut(identity) { GallerySuperResolutionSession(context.applicationContext, provider, identity, info) }
    }
    fun active() = sessions.values.filter { it.enabled && it.working }
    fun wake(context: Context) { ContextCompat.startForegroundService(context, Intent(context, TranslationService::class.java)) }
    fun changed() { listener?.invoke() }
    fun stop(identity: String) { sessions[identity]?.disable() }
    fun stopAll() { sessions.values.toList().forEach { it.disable() } }
    suspend fun stopForResultDeletion(galleryIds: Set<Long>, sourceIdentities: Set<String> = emptySet()) {
        sessions.values.toList().filter { it.galleryInfo?.gid in galleryIds || it.identity in sourceIdentities }
            .forEach { it.stopForResultDeletion() }
    }
    fun leaveReaders() {
        sessions.values.toList().filter { !it.fullGallery }.forEach(::release)
    }
    fun release(session: GallerySuperResolutionSession) {
        if (sessions[session.identity] === session) sessions.remove(session.identity)
        session.close()
    }
}
