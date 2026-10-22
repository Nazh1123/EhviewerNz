package com.hippo.ehviewer.translation

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.superresolution.GallerySuperResolutionSession
import com.hippo.ehviewer.superresolution.SuperResolutionTasks
import com.hippo.unifile.UniFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34, 36])
class GalleryProcessingNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val translations = mutableListOf<GalleryTranslationSession>()
    private val enhancements = mutableListOf<GallerySuperResolutionSession>()
    private fun info(id: Long) = GalleryInfo().apply { gid = id; title = "Gallery $id"; token = "token" }
    private fun translation(id: Long) = TranslationTasks.acquire(context, Provider(), info(id)).also(translations::add)
    private fun enhancement(id: Long) = SuperResolutionTasks.acquire(context, Provider(), info(id)).also(enhancements::add)
    private fun submit(session: Any, identity: String) {
        for (field in listOf("enabled", "working", "fullGallery")) ReflectionHelpers.setField(session, field, true)
        ReflectionHelpers.setField(session, "current", 0)
        GalleryPageWorkRegistry.acquire(identity).changed()
    }
    private fun ongoing(): Notification = notifications.activeNotifications.filter {
        it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }.single().notification
    private fun assertQueued(count: Int) {
        assertTrue(ongoing().extras.getString(Notification.EXTRA_TEXT).orEmpty()
            .contains(context.getString(R.string.gallery_processing_queued, count)))
    }
    private fun refresh() { shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS) }

    @After fun cleanup() {
        translations.forEach(TranslationTasks::release)
        enhancements.forEach(SuperResolutionTasks::release)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun multipleGalleriesUseOneProgressNotificationInSubmissionOrderAndUpdateQueueCount() {
        val third = translation(103)
        val first = translation(101)
        val second = translation(102)
        for (session in listOf(first, second, third)) submit(session, session.identity)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            assertEquals("Gallery 101", ongoing().extras.getString(Notification.EXTRA_TITLE))
            assertQueued(2)
            TranslationTasks.stop(second.identity)
            refresh()
            assertQueued(1)
            val stop = shadowOf(ongoing().actions.single().actionIntent).savedIntent
            controller.get().onStartCommand(stop, 0, 2)
            assertEquals("Gallery 103", ongoing().extras.getString(Notification.EXTRA_TITLE))
            assertQueued(0)
            assertTrue(third.enabled)
        } finally { controller.destroy() }
    }

    @Test fun translationAndSrShareOneBarCountGalleriesOnceAndStopBothStagesOfOnlyTheCurrentGallery() {
        val firstTranslation = translation(201)
        val firstSr = enhancement(201)
        val secondTranslation = translation(202)
        val secondSr = enhancement(202)
        submit(firstTranslation, firstTranslation.identity); submit(firstSr, firstSr.identity)
        submit(secondTranslation, secondTranslation.identity); submit(secondSr, secondSr.identity)
        ReflectionHelpers.setField(firstSr, "activePage", 2)
        ReflectionHelpers.setField(firstSr, "progress", 42)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            assertQueued(1)
            assertEquals(context.getString(R.string.sr_title), ongoing().extras.getString(Notification.EXTRA_SUB_TEXT))
            assertTrue(ongoing().extras.getString(Notification.EXTRA_TEXT).orEmpty()
                .contains(context.getString(R.string.sr_page_progress, 3, 42)))
            val stop = shadowOf(ongoing().actions.single().actionIntent).savedIntent
            controller.get().onStartCommand(stop, 0, 2)
            assertFalse(firstTranslation.enabled); assertFalse(firstSr.enabled)
            assertTrue(secondTranslation.enabled); assertTrue(secondSr.enabled)
            assertEquals("Gallery 202", ongoing().extras.getString(Notification.EXTRA_TITLE))
            assertQueued(0)
        } finally { controller.destroy() }
    }

    @Test fun translationFinishingKeepsTheSharedServiceAndWakeLockUntilSrAlsoFinishes() {
        val translation = translation(301)
        val sr = enhancement(301)
        submit(translation, translation.identity); submit(sr, sr.identity)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val wakeLock = ReflectionHelpers.getField<PowerManager.WakeLock>(service, "wakeLock")
            ReflectionHelpers.setField(translation, "working", false)
            ReflectionHelpers.setField(translation, "fullGallery", false)
            GalleryPageWorkRegistry.acquire(translation.identity).changed()
            TranslationTasks.changed()
            refresh()
            assertTrue(wakeLock.isHeld)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(context.getString(R.string.sr_title), ongoing().extras.getString(Notification.EXTRA_SUB_TEXT))
            SuperResolutionTasks.stop(sr.identity)
            refresh()
            assertFalse(wakeLock.isHeld)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(notifications.activeNotifications.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun srOnlyUsesTheSharedServiceAndTimeoutStopsBothKindsOfWork() {
        val sr = enhancement(401)
        submit(sr, sr.identity)
        SuperResolutionTasks.wake(context)
        assertEquals(TranslationService::class.java.name, shadowOf(context).nextStartedService.component?.className)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            assertEquals("Gallery 401", ongoing().extras.getString(Notification.EXTRA_TITLE))
            assertQueued(0)
            val translation = translation(402)
            submit(translation, translation.identity)
            val wakeLock = ReflectionHelpers.getField<PowerManager.WakeLock>(service, "wakeLock")
            service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertFalse(sr.enabled); assertFalse(translation.enabled)
            assertFalse(wakeLock.isHeld)
            assertTrue(notifications.activeNotifications.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun oldPerGalleryAndSrProgressNotificationsAreRemovedButCompletionNoticesRemain() {
        val session = translation(501)
        submit(session, session.identity)
        val channel = "${context.packageName}.translation"
        val srChannel = "${context.packageName}.super-resolution"
        for (id in listOf(channel, srChannel)) notifications.createNotificationChannel(
            NotificationChannel(id, id, NotificationManager.IMPORTANCE_LOW))
        notifications.notify(0x7399, Notification.Builder(context, channel).setSmallIcon(R.drawable.v_translate_x24)
            .setOngoing(true).build())
        notifications.notify(0x7400, Notification.Builder(context, srChannel).setSmallIcon(R.drawable.v_super_resolution_x24)
            .setOngoing(true).build())
        notifications.notify(0x7398, Notification.Builder(context, channel).setSmallIcon(R.drawable.v_translate_x24).build())
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            assertQueued(0)
            assertEquals(setOf(0x7300, 0x7398), notifications.activeNotifications.map { it.id }.toSet())
        } finally { controller.destroy() }
    }

    private class Provider : GalleryProvider2() {
        override fun size() = 8
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index"
        override fun save(index: Int, file: UniFile) = false
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
