package com.hippo.ehviewer.translation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import com.hippo.ehviewer.translation.engine.TranslationStage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34, 36])
class TranslationBackgroundTest {
    @Test fun backgroundNotificationAdvancesWithinAPageBeforeItsCompletionCountChanges() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        ReflectionHelpers.setField(session, "fullGallery", true)
        session.onPageChanged(3)
        next(session)
        session.setBrowsing(false)
        for (stage in listOf(TranslationStage.DETECT, TranslationStage.OCR, TranslationStage.INPAINT))
            session.pageProgress.update(stage, 1f)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertEquals(context.getString(R.string.translation_notification_active_progress, 0, 8, 4, 50, 0),
                notifications.activeNotifications.first().notification.extras.getString(Notification.EXTRA_TEXT))
            session.pageProgress.update(TranslationStage.TRANSLATE, 0.5f)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshNotifications")
            assertEquals(context.getString(R.string.translation_notification_active_progress, 0, 8, 4, 73, 0),
                notifications.activeNotifications.first().notification.extras.getString(Notification.EXTRA_TEXT))
        } finally { controller.destroy() }
    }

    @Test fun leavingReaderCancelsTheCurrentPageAndOrdinaryLookahead() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        session.onPageChanged(3)
        val active = next(session)!!
        session.detach(session.reader!!)
        assertTrue(active.isObsolete)
        assertFalse(session.readerOpen)
        assertFalse(session.enabled)
        assertFalse(session.working)
        assertTrue(session.pendingPages().isEmpty())
        assertNull(next(session))
    }

    @Test fun leavingReaderPreservesOnlyAnEnabledFullGalleryTask() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        ReflectionHelpers.setField(session, "fullGallery", true)
        session.onPageChanged(3)
        val active = next(session)!!
        session.detach(session.reader!!)
        assertFalse(active.isObsolete)
        assertFalse(session.readerOpen)
        assertTrue(session.enabled)
        assertTrue(session.fullGallery)
        assertEquals(listOf(3, 4, 5, 6, 7, 0, 1, 2), session.pendingPages())
        session.disable()
        assertFalse(session.enabled)
        assertTrue(session.pendingPages().isEmpty())
    }

    @Test fun temporaryPauseKeepsOrdinaryWorkProtectedByTheServiceAndWakeLock() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        session.onPageChanged(3)
        val active = next(session)!!
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            session.setBrowsing(false)
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "refreshNotifications")
            assertFalse(active.isObsolete)
            assertTrue(session.enabled)
            assertTrue(session.readerOpen)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(ReflectionHelpers.getField<PowerManager.WakeLock>(controller.get(), "wakeLock").isHeld)
            val notice = shadowOf(controller.get()).lastForegroundNotification
            assertEquals(3, notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            session.states[3] = R.string.translation_animation
            assertEquals(4, next(session)!!.page)
        } finally { controller.destroy() }
    }

    @Test fun ordinaryServiceFinishesWithoutACompletionNotification() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        session.onPageChanged(3)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val lock = ReflectionHelpers.getField<PowerManager.WakeLock>(controller.get(), "wakeLock")
            ReflectionHelpers.setField(session, "working", false)
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "refreshNotifications")
            assertFalse(lock.isHeld)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue((context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.isEmpty())
            assertTrue(session.enabled)
        } finally { controller.destroy() }
    }

    @Test fun removingTheAppTaskStopsReaderWindowsAndKeepsExplicitFullWork() = withSession { ordinary ->
        val context = RuntimeEnvironment.getApplication()
        val full = TranslationTasks.acquire(context, Provider("removed-task-full"), null)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            for (session in listOf(ordinary, full)) {
                session.reader = Reader()
                ReflectionHelpers.setField(session, "enabled", true)
                ReflectionHelpers.setField(session, "working", true)
                session.onPageChanged(3)
            }
            ReflectionHelpers.setField(full, "fullGallery", true)
            controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            controller.get().onTaskRemoved(Intent())
            assertFalse(ordinary.enabled)
            assertFalse(ordinary.readerOpen)
            assertTrue(full.enabled)
            assertTrue(full.fullGallery)
            assertFalse(full.readerOpen)
            assertNull(full.reader)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        } finally {
            controller.destroy()
            TranslationTasks.release(full)
        }
    }

    @Test fun readerPriorityDependsOnUnfinishedPagesRatherThanReaderPresence() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "fullGallery", true)
        session.onPageChanged(3)
        assertEquals(0, session.schedulingPriority())
        session.states[3] = R.string.translation_no_text
        assertEquals(1, session.schedulingPriority())
        session.states[4] = R.string.translation_no_text
        session.states[5] = R.string.translation_no_text
        assertEquals(2, session.schedulingPriority())
        (0 until 8).forEach { session.states[it] = R.string.translation_no_text }
        assertEquals(3, session.schedulingPriority())
    }

    @Test fun currentPagePriorityDoesNotInspectEveryPageOfALargeGallery() {
        val session = TranslationTasks.acquire(RuntimeEnvironment.getApplication(), Provider("large-gallery", 10000), null)
        var checked = 0
        try {
            session.reader = object : GalleryTranslationSession.Reader {
                override fun changed() = Unit
                override fun hasTranslatedPage(page: Int): Boolean { checked++; return false }
                override fun display(page: Int, image: Image) = image.recycle()
            }
            ReflectionHelpers.setField(session, "enabled", true)
            ReflectionHelpers.setField(session, "fullGallery", true)
            session.onPageChanged(5000)
            checked = 0
            assertEquals(0, session.schedulingPriority())
            assertTrue("Scheduler inspected distant pages on Main", checked <= 1)
            session.states[5000] = R.string.translation_no_text
            checked = 0
            assertEquals(1, session.schedulingPriority())
            assertTrue("Lookahead priority inspected distant pages on Main", checked <= 4)
        } finally { TranslationTasks.release(session) }
    }

    @Test fun ordinaryHiddenReaderUsesBackgroundPriorityWithoutCancellingItsWindow() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        session.onPageChanged(3)
        assertEquals(0, session.schedulingPriority())
        session.setBrowsing(false)
        assertEquals(2, session.schedulingPriority())
        assertTrue(session.readerOpen)
        assertTrue(session.enabled)
        assertEquals(listOf(3, 4, 5), session.pendingPages())
    }

    @Test fun yieldingDoesNotRequestAnotherForegroundServiceStart() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        assertTrue(session.translateFullGallery())
        assertNotNull(shadowOf(context).nextStartedService)
        assertTrue(ReflectionHelpers.callInstanceMethod(session, "ensureExecutionService"))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun ordinaryTranslationStartsTheForegroundServiceBeforeTheReaderIsHidden() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        TranslationTasks.wake(context, session)
        assertEquals(TranslationService::class.java.name, shadowOf(context).nextStartedService.component?.className)
        assertTrue(TranslationTasks.fullGalleryTasks().isEmpty())
        assertTrue(session.enabled)
        assertTrue(session.working)
    }

    @Test fun promotingAnOrdinaryQueueKeepsItsExistingForegroundService() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        assertTrue(ReflectionHelpers.callInstanceMethod(session, "ensureExecutionService"))
        assertNotNull(shadowOf(context).nextStartedService)
        assertTrue(session.translateFullGallery())
        assertNull(shadowOf(context).nextStartedService)
        assertEquals(listOf(session), TranslationTasks.fullGalleryTasks())
    }

    @Test fun ordinaryAndFullTasksHaveNotificationsAndStoppingOnePreservesTheOther() = withSession { ordinary ->
        val context = RuntimeEnvironment.getApplication()
        val full = TranslationTasks.acquire(context, Provider("full-notification"), null)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            for (session in listOf(ordinary, full)) {
                ReflectionHelpers.setField(session, "enabled", true)
                ReflectionHelpers.setField(session, "working", true)
                session.onPageChanged(3)
            }
            ReflectionHelpers.setField(full, "fullGallery", true)
            controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertEquals(2, notifications.activeNotifications.size)
            val notice = shadowOf(controller.get()).lastForegroundNotification
            assertEquals(3, notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            controller.get().onStartCommand(shadowOf(notice.actions[0].actionIntent).savedIntent, 0, 2)
            assertTrue(full.enabled)
            assertFalse(ordinary.enabled)
            assertTrue(full.working)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            assertEquals(1, notifications.activeNotifications.size)
        } finally {
            controller.destroy()
            TranslationTasks.release(full)
        }
    }

    @Test fun pauseAndReaderRecreationRetainEnabledStateAndDoNotCancelTheActivePage() {
        Robolectric.buildActivity(AppCompatActivity::class.java).use { host ->
            host.get().setTheme(R.style.AppTheme_Gallery)
            host.setup()
            val provider = Provider()
            val reader = ReaderTranslationController(host.get(), provider, ImageButton(host.get()))
            val session = reader.session
            try {
                ReflectionHelpers.setField(session, "enabled", true)
                session.onPageChanged(3)
                val active = next(session)!!
                reader.pause()
                assertTrue(session.enabled)
                assertFalse(active.isObsolete)
                assertEquals(listOf(3, 4, 5), session.pendingPages())
                reader.close(false) // Activity recreation or background destruction, without a reader exit.
                assertNull(session.reader)
                assertFalse(active.isObsolete)
                ReaderTranslationController(host.get(), Provider(), ImageButton(host.get())).use { reopened ->
                    assertSame(session, reopened.session)
                    reopened.resume()
                    assertTrue(session.enabled)
                    assertFalse(active.isObsolete)
                }
            } finally {
                reader.close()
                TranslationTasks.release(session)
            }
        }
    }

    @Test fun promotingAnActiveWindowKeepsItsPrefixAndForwardOrderWhenHiddenOrDetached() {
        for (leavingGallery in listOf(false, true)) withSession { session ->
            session.reader = Reader()
            ReflectionHelpers.setField(session, "enabled", true)
            session.onPageChanged(2)
            val active = next(session)!!
            session.onPageChanged(4) // Keep the already queued prefix within the two-page tolerance.
            assertEquals(listOf(2, 3, 4, 5, 6), session.pendingPages())
            assertTrue(session.translateFullGallery())
            if (leavingGallery) session.detach(session.reader!!) else session.setBrowsing(false)
            assertFalse(active.isObsolete)
            settle(session, active)
            val order = (0 until 7).map {
                val request = next(session)!!
                settle(session, request)
                request.page
            }
            assertEquals(listOf(3, 4, 5, 6, 7, 0, 1), order)
            assertNull(next(session))
            assertEquals(8, session.queueCompleted())
        }
    }

    @Test fun promotingACompletedWindowContinuesAfterItInsteadOfStartingAtPageOne() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        session.onPageChanged(3)
        repeat(3) { offset ->
            val request = next(session)!!
            assertEquals(3 + offset, request.page)
            settle(session, request)
        }
        assertNull(next(session))
        session.setBrowsing(false)
        assertTrue(session.translateFullGallery())
        val order = (0 until 5).map {
            val request = next(session)!!
            settle(session, request)
            request.page
        }
        assertEquals(listOf(6, 7, 0, 1, 2), order)
        assertNull(next(session))
        assertEquals(8, session.queueCompleted())
    }

    @Test fun fullTranslationContinuesForwardAfterDetachingAndResumesReaderPriority() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "fullGallery", true)
        session.onPageChanged(3)
        val initial = next(session)!!
        assertEquals(3, initial.page)
        val observer = session.reader!!
        session.detach(observer)
        assertFalse(initial.isObsolete)
        session.states[3] = R.string.translation_done
        val order = (0 until 7).map {
            val request = next(session)!!
            session.states[request.page] = R.string.translation_animation
            ReflectionHelpers.callInstanceMethod<Unit>(session, "finishRequest",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
                ReflectionHelpers.ClassParameter.from(TranslationPageRequest::class.java, request))
            request.page
        }
        assertEquals(listOf(4, 5, 6, 7, 0, 1, 2), order)
        assertNull(next(session))
        assertEquals(8, session.queueCompleted())
        // A completed current page evicted from memory must not restart the gallery sweep.
        session.reader = Reader()
        session.setBrowsing(true)
        assertEquals(3, next(session)!!.page)
        session.states[3] = R.string.translation_done
        assertNull(next(session))
    }

    @Test fun fullTranslationRetainsTwoPageToleranceAndLargeJumpsUseTheNewReaderPosition() = withSession { session ->
        session.reader = Reader()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "fullGallery", true)
        session.onPageChanged(0)
        val active = next(session)!!
        session.onPageChanged(2)
        assertFalse(active.isObsolete)
        session.onPageChanged(6)
        assertTrue(active.isObsolete)
        val order = (0 until 8).map {
            val request = next(session)!!
            session.states[request.page] = R.string.translation_animation
            request.page
        }
        assertEquals(listOf(6, 7, 0, 1, 2, 3, 4, 5), order)
    }

    @Test fun notificationStopCancelsOnlyItsGalleryAndReleasesTheWakeLockAfterTheLastTask() = withSession { first ->
        val context = RuntimeEnvironment.getApplication()
        val second = TranslationTasks.acquire(context, Provider("second"), null)
        val serviceController = Robolectric.buildService(TranslationService::class.java).create()
        val service = serviceController.get()
        try {
            for (session in listOf(first, second)) {
                ReflectionHelpers.setField(session, "enabled", true)
                ReflectionHelpers.setField(session, "working", true)
                ReflectionHelpers.setField(session, "fullGallery", true)
                session.onPageChanged(3)
                session.states[0] = R.string.translation_done
                session.states[1] = R.string.translation_failed
            }
            service.onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val notice = shadowOf(service).lastForegroundNotification
            assertEquals(context.getString(R.string.translation_notification_progress, 2, 8, 1),
                notice.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(8, notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(2, notice.extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(1, notice.actions.size)
            assertEquals(context.getString(R.string.translation_notification_stop), notice.actions[0].title)
            val wakeLock = ReflectionHelpers.getField<PowerManager.WakeLock>(service, "wakeLock")
            assertTrue(wakeLock.isHeld)
            val stop = shadowOf(notice.actions[0].actionIntent).savedIntent
            service.onStartCommand(stop, 0, 2)
            assertFalse(first.enabled)
            assertTrue(second.enabled)
            assertTrue(wakeLock.isHeld)
            service.onStartCommand(Intent(context, TranslationService::class.java)
                .setAction(TranslationService.ACTION_STOP)
                .putExtra(TranslationService.EXTRA_IDENTITY, second.identity), 0, 3)
            assertFalse(second.enabled)
            assertFalse(wakeLock.isHeld)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            serviceController.destroy()
            TranslationTasks.release(second)
        }
    }

    @Test fun galleryCompletionNotificationRemainsAfterForegroundServiceStops() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        val serviceController = Robolectric.buildService(TranslationService::class.java).create()
        val service = serviceController.get()
        try {
            ReflectionHelpers.setField(session, "enabled", true)
            ReflectionHelpers.setField(session, "working", true)
            ReflectionHelpers.setField(session, "fullGallery", true)
            session.onPageChanged(0)
            service.onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            TranslationTasks.completed(session, 8, 2)
            ReflectionHelpers.setField(session, "working", false)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshNotifications")
            val notices = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications
            assertEquals(1, notices.size)
            assertEquals(context.getString(R.string.translation_gallery_completed, 8, 2),
                notices[0].notification.extras.getString(Notification.EXTRA_TEXT))
            assertTrue(notices[0].notification.actions.isNullOrEmpty())
            assertEquals(0, notices[0].notification.flags and Notification.FLAG_ONGOING_EVENT)
        } finally { serviceController.destroy() }
    }

    @Test fun aQueueFinishingBeforeServiceCreationStillProducesItsCompletionNotification() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        TranslationTasks.completed(session, 8, 0)
        val serviceController = Robolectric.buildService(TranslationService::class.java).create()
        try {
            serviceController.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val notices = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications
            assertEquals(1, notices.size)
            assertEquals(context.getString(R.string.translation_gallery_completed, 8, 0),
                notices[0].notification.extras.getString(Notification.EXTRA_TEXT))
            assertTrue(shadowOf(serviceController.get()).isStoppedBySelf)
        } finally { serviceController.destroy() }
    }

    @Test fun delayedCompletionNotificationKeepsThePartialCountFromCompletion() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        session.states[0] = R.string.translation_partial
        TranslationTasks.completed(session, 8, 0)
        session.states.clear() // A new run can start before the service consumes the completion.
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val notices = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications
            assertEquals(1, notices.size)
            assertEquals(context.getString(R.string.translation_gallery_completed, 8, 0) +
                context.getString(R.string.translation_partial_count, 1),
                notices[0].notification.extras.getString(Notification.EXTRA_TEXT))
        } finally { controller.destroy() }
    }

    @Test fun completionNoticesForDifferentGalleriesSurviveServiceRecreationWithoutSharingIntents() = withSession { first ->
        val context = RuntimeEnvironment.getApplication()
        val second = TranslationTasks.acquire(context, Provider("second-completion"), null)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            for (session in listOf(first, second)) {
                val controller = Robolectric.buildService(TranslationService::class.java).create()
                try {
                    TranslationTasks.completed(session, 8, 0)
                    controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
                } finally { controller.destroy() }
            }
            val notices = notifications.activeNotifications
            assertEquals(2, notices.size)
            assertNotEquals(notices[0].id, notices[1].id)
            val destinations = notices.map { shadowOf(it.notification.contentIntent).savedIntent.data }
            assertEquals(2, destinations.toSet().size)
        } finally { TranslationTasks.release(second) }
    }

    @Test fun unexpectedServiceDestructionStopsActiveWorkAndReleasesItsWakeLock() = withSession { session ->
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        ReflectionHelpers.setField(session, "enabled", true)
        ReflectionHelpers.setField(session, "working", true)
        ReflectionHelpers.setField(session, "fullGallery", true)
        controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
        val lock = ReflectionHelpers.getField<PowerManager.WakeLock>(controller.get(), "wakeLock")
        assertTrue(lock.isHeld)
        controller.destroy()
        assertFalse(session.enabled)
        assertFalse(session.working)
        assertFalse(lock.isHeld)
    }

    @Test fun systemTimeoutStopsEveryQueueLosingForegroundProtection() = withSession { first ->
        val context = RuntimeEnvironment.getApplication()
        val second = TranslationTasks.acquire(context, Provider("timeout-second"), null)
        val ordinary = TranslationTasks.acquire(context, Provider("timeout-ordinary"), null)
        val controller = Robolectric.buildService(TranslationService::class.java).create()
        try {
            for (session in listOf(first, second)) {
                ReflectionHelpers.setField(session, "enabled", true)
                ReflectionHelpers.setField(session, "working", true)
                ReflectionHelpers.setField(session, "fullGallery", true)
            }
            ReflectionHelpers.setField(ordinary, "enabled", true)
            ReflectionHelpers.setField(ordinary, "working", true)
            controller.get().onStartCommand(Intent(context, TranslationService::class.java), 0, 1)
            val lock = ReflectionHelpers.getField<PowerManager.WakeLock>(controller.get(), "wakeLock")
            controller.get().onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertFalse(first.enabled)
            assertFalse(second.enabled)
            assertFalse(ordinary.enabled)
            assertFalse(ordinary.working)
            assertFalse(lock.isHeld)
            assertTrue((context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications.isEmpty())
        } finally {
            controller.destroy()
            TranslationTasks.release(second)
            TranslationTasks.release(ordinary)
        }
    }

    private fun next(session: GalleryTranslationSession) =
        ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(session, "nextRequest")

    private fun settle(session: GalleryTranslationSession, request: TranslationPageRequest) {
        session.states[request.page] = R.string.translation_animation
        ReflectionHelpers.callInstanceMethod<Unit>(session, "finishRequest",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(TranslationPageRequest::class.java, request))
    }

    private fun withSession(test: (GalleryTranslationSession) -> Unit) {
        val session = TranslationTasks.acquire(RuntimeEnvironment.getApplication(), Provider(), null)
        try { test(session) } finally { TranslationTasks.release(session) }
    }

    private class Reader : GalleryTranslationSession.Reader {
        override fun changed() = Unit
        override fun hasTranslatedPage(page: Int) = false
        override fun display(page: Int, image: Image) = image.recycle()
    }

    private class Provider(private val identity: String = "background-test", private val pages: Int = 8) : GalleryProvider2() {
        override fun getTranslationIdentity() = identity
        override fun size() = pages
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index.jpg"
        override fun save(index: Int, file: UniFile) = false
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
