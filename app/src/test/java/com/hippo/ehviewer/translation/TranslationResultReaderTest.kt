package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.lib.image.Image
import com.hippo.unifile.UniFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import com.hippo.ehviewer.translation.engine.TranslationStage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34])
class TranslationResultReaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rapidNavigationDropsQueuedRestoresAndDisplaysTheLastPageWithoutInference() = withSession { session, provider, reader ->
        for (page in 0 until 8) remember(session, page)
        val gate = ReflectionHelpers.getField<TranslationImageWork>(session, "imageReads")
        val blocked = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val decode = scope.launch { gate.run { blocked.complete(Unit); finish.await() } }
        try {
            runBlocking { blocked.await() }
            repeat(40) { session.onPageChanged((it * 3) % 8) }
            session.onPageChanged(7)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(reader.delivered.isEmpty())
            finish.complete(Unit)
            await { loader(session) == null && reader.hasTranslatedPage(7) }
            assertEquals(listOf(7), reader.delivered)
            assertEquals(0, provider.saves)
            assertTrue(session.enabled)
        } finally {
            finish.complete(Unit)
            runBlocking { decode.join() }
            scope.cancel()
        }
    }

    @Test fun completedPageDisplaysWhileInferenceLockIsHeldWithoutResettingActiveProgress() = withSession { session, provider, reader ->
        session.onPageChanged(2)
        val active = next(session)!!
        session.pageProgress.update(TranslationStage.DETECT, 0.5f)
        val progress = session.pageProgress.value
        remember(session, 1)
        ReflectionHelpers.setField(session, "activated", true)
        assertTrue(TranslationRuntime.lock.tryLock())
        try {
            session.onPageChanged(1)
            await { reader.hasTranslatedPage(1) }
            assertTrue(TranslationRuntime.lock.isLocked)
            assertEquals(0, provider.saves)
            assertEquals(2, session.activePage)
            assertFalse(active.isObsolete)
            assertEquals(progress, session.pageProgress.value)
            assertNull(ReflectionHelpers.getField<Job?>(session, "worker"))
        } finally { TranslationRuntime.lock.unlock() }
    }

    @Test fun preloadsOnlyOneCompletedNeighborInTheReadingDirection() = withSession { session, provider, reader ->
        for (page in 0 until 8) remember(session, page)
        session.onPageChanged(3)
        await { loader(session) == null }
        assertEquals(listOf(3, 4), reader.delivered)
        session.onPageChanged(4)
        assertTrue(reader.hasTranslatedPage(4))
        await { loader(session) == null }
        assertEquals(listOf(3, 4, 5), reader.delivered)
        session.onPageChanged(3)
        await { loader(session) == null }
        assertEquals(listOf(3, 4, 5, 2), reader.delivered)
        assertEquals(0, provider.saves)
    }

    @Test fun readerRecreationRestoresCompletedFilesWithoutRestartingTranslation() = withSession { session, provider, reader ->
        remember(session, 3)
        session.onPageChanged(3)
        await { reader.hasTranslatedPage(3) }
        session.detach(reader, false) // Recreate the observer while keeping the logical reader open.
        reader.clearTranslations()
        val reopened = Reader(provider)
        session.reader = reopened
        session.setBrowsing(true)
        await { reopened.hasTranslatedPage(3) }
        assertTrue(session.enabled)
        assertEquals(0, provider.saves)
    }

    @Test fun missingOrCorruptResultFallsBackToSourceInsteadOfBeingCountedAsRestored() = withSession { session, _, reader ->
        remember(session, 3).delete()
        remember(session, 4).writeText("invalid PNG")
        session.onPageChanged(3)
        await { loader(session) == null }
        assertFalse(reader.hasTranslatedPage(3))
        assertFalse(session.isPageSettled(3))
        assertEquals(3, next(session)!!.page)
        session.onPageChanged(4)
        assertFalse(reader.hasTranslatedPage(4))
        assertFalse(session.isPageSettled(4))
    }

    @Test fun disablingOrForcingRetryBeforeDeliveryNeverDisplaysTheOldBitmap() = withSession { session, _, reader ->
        remember(session, 3)
        session.onPageChanged(3)
        session.disable()
        drain()
        assertFalse(reader.hasTranslatedPage(3))
        assertFalse(session.enabled)
        ReflectionHelpers.setField(session, "enabled", true)
        session.onPageChanged(3)
        session.enqueue(3, true)
        drain()
        assertFalse(reader.hasTranslatedPage(3))
        val request = next(session)!!
        assertEquals(3, request.page)
        assertTrue(request.force)
    }

    @Test fun refreshingOrClearingResultsInvalidatesTheFastPath() = withSession { session, _, reader ->
        remember(session, 3)
        session.invalidateResult(3)
        session.states.remove(3)
        session.onPageChanged(3)
        drain()
        assertFalse(reader.hasTranslatedPage(3))
        assertEquals(3, next(session)!!.page)
        session.disable()
        remember(session, 4)
        session.clearRememberedResults()
        session.states.clear()
        session.onPageChanged(4)
        ReflectionHelpers.setField(session, "enabled", true)
        session.onPageChanged(4)
        drain()
        assertFalse(reader.hasTranslatedPage(4))
        assertEquals(4, next(session)!!.page)
    }

    @Test fun firstDiskCacheHitDoesNotVerifyOrLoadComicModels() = withSession { session, provider, reader ->
        val context = RuntimeEnvironment.getApplication()
        val source = png()
        provider.source = source
        val store = TranslationResultStore(TranslationStorage.cacheDir(context),
            null, session.activeOptions, null, false)
        val output = store.writeImage(store.key(source)) { out -> source.inputStream().use { it.copyTo(out) } }
        try {
            assertFalse(TranslationModels(context).ready())
            ReflectionHelpers.setField(session, "activated", true)
            session.onPageChanged(3)
            await { reader.hasTranslatedPage(3) && !session.working }
            assertTrue(session.enabled)
            assertEquals(1, provider.saves)
            assertEquals(R.string.translation_done, session.states[3])
        } finally { output.delete() }
    }

    @Test fun reopenedAutomaticReaderUsesResolvedCacheWithoutModelsOrLanguageIdentification() = withSession { session, provider, reader ->
        val context = RuntimeEnvironment.getApplication()
        session.activeOptions = session.activeOptions.copy(source = "auto")
        val source = png()
        provider.source = source
        val results = TranslationResultStore(TranslationStorage.cacheDir(context), null,
            session.activeOptions.copy(source = "en"), null, false)
        val output = results.writeImage(results.key(source)) { out -> source.inputStream().use { it.copyTo(out) } }
        try {
            assertFalse(TranslationModels(context).ready())
            ReflectionHelpers.setField(session, "activated", true)
            session.onPageChanged(3)
            await { reader.hasTranslatedPage(3) && !session.working }
            assertEquals(R.string.translation_done, session.states[3])
            assertEquals(1, provider.saves)
            assertNull(ReflectionHelpers.getField<GallerySourceLanguage>(session, "sourceLanguage").language)
        } finally { output.delete() }
    }

    @Test fun namedPersistentResultLoadsBeforeRequestingAnUnavailableOriginal() = withSession { session, provider, reader ->
        val directory = temp.newFolder("gallery")
        provider.directory = requireNotNull(UniFile.fromFile(directory))
        val saved = File(directory, "_translated").apply { mkdirs() }
        png().copyTo(File(saved, "3_tl.png"))
        assertNull(provider.source)
        assertFalse(TranslationModels(RuntimeEnvironment.getApplication()).ready())
        ReflectionHelpers.setField(session, "activated", true)
        session.onPageChanged(3)
        await { reader.hasTranslatedPage(3) && !session.working }
        assertEquals(R.string.translation_done, session.states[3])
        assertEquals(0, provider.saves)
    }

    @Test fun oldFullPageCacheIsConvertedToOneSparseOverlayWithoutTranslationModels() = withSession { session, provider, reader ->
        val context = RuntimeEnvironment.getApplication()
        val original = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val rendered = original.copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(1, 1, android.graphics.Color.BLUE) }
        val source = temp.newFile()
        source.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        provider.source = source
        val cache = TranslationCache(TranslationStorage.cacheDir(context))
        val identity = "ehnz-offline-v1\n981ae85617bb3323949d57b7d6e3e10181435325\n" +
            "llama-jni-v11-japanese-source-strict-regions-prefix-kv\n\nja\nzh-CN\ntrue\noriginal-size"
        val legacy = File(TranslationStorage.cacheDir(context), "${cache.key(source, identity)}.png")
        legacy.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val output = cache.image(cache.key(source, session.activeOptions))
        try {
            assertFalse(TranslationModels(context).ready())
            ReflectionHelpers.setField(session, "activated", true)
            session.onPageChanged(3)
            await { reader.hasTranslatedPage(3) && !session.working }
            assertFalse(legacy.exists())
            assertTrue(output.isFile)
            val overlay = android.graphics.BitmapFactory.decodeFile(output.absolutePath)!!
            try {
                assertEquals(android.graphics.Color.TRANSPARENT, overlay.getPixel(0, 0))
                assertEquals(android.graphics.Color.BLUE, overlay.getPixel(1, 1))
            } finally { overlay.recycle() }
        } finally { original.recycle(); rendered.recycle(); legacy.delete(); output.delete() }
    }

    @Test fun namedPersistentNoTextMarkerLoadsWithoutRequestingOriginalOrModels() = withSession { session, provider, _ ->
        val directory = temp.newFolder("gallery")
        provider.directory = requireNotNull(UniFile.fromFile(directory))
        File(File(directory, "_translated").apply { mkdirs() }, "3_tl.skip").writeText("skipped")
        ReflectionHelpers.setField(session, "activated", true)
        session.onPageChanged(3)
        await { session.states[3] == R.string.translation_no_text && !session.working }
        assertEquals(0, provider.saves)
    }

    @Test fun changingTheSelectedModelKeepsTheRememberedResultUntilExplicitRetranslation() = withSession { session, provider, reader ->
        remember(session, 3)
        session.onPageChanged(3)
        await { reader.hasTranslatedPage(3) }
        session.disable()
        session.settings.save(session.activeOptions.copy(nativeModelId = "b".repeat(64), source = "en", target = "fr"))
        assertTrue(session.enable())
        await { !session.working }
        assertTrue(reader.hasTranslatedPage(3))
        assertEquals(R.string.translation_done, session.states[3])
        assertEquals(0, provider.saves)
        assertEquals("b".repeat(64), session.activeOptions.nativeModelId)
        session.enqueue(3, true)
        assertFalse(session.isPageSettled(3))
    }

    private fun remember(session: GalleryTranslationSession, page: Int): File = png().also {
        ReflectionHelpers.getField<MutableMap<Int, UniFile>>(session, "completedFiles")[page] = requireNotNull(UniFile.fromFile(it))
        session.states[page] = R.string.translation_done
    }

    private fun png(): File = temp.newFile().also { file ->
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun next(session: GalleryTranslationSession) =
        ReflectionHelpers.callInstanceMethod<TranslationPageRequest?>(session, "nextRequest")

    private fun loader(session: GalleryTranslationSession) = ReflectionHelpers.getField<Job?>(session, "resultLoader")

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Timed out waiting for cached reader image", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private fun drain() {
        // Main delivery stays queued until idle(), making cancellation before delivery deterministic.
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    }

    private fun withSession(test: (GalleryTranslationSession, Provider, Reader) -> Unit) {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val provider = Provider()
        val session = TranslationTasks.acquire(RuntimeEnvironment.getApplication(), provider, null)
        val reader = Reader(provider)
        session.reader = reader
        session.activeOptions = TranslationOptions(ahead = 0)
        ReflectionHelpers.setField(session, "enabled", true)
        provider.start()
        try { test(session, provider, reader) } finally {
            TranslationTasks.release(session)
            drain()
            provider.stop()
        }
    }

    private class Reader(private val provider: Provider) : GalleryTranslationSession.Reader {
        val delivered = mutableListOf<Int>()
        override fun changed() = Unit
        override fun hasTranslatedPage(page: Int) = provider.hasTranslatedPage(page)
        override fun display(page: Int, image: Image) {
            delivered.add(page)
            provider.setTranslationOverlay(page, image)
        }
        override fun clearTranslations() = provider.clearTranslatedPages()
    }

    private class Provider : GalleryProvider2() {
        @Volatile var saves = 0
        var source: File? = null
        var directory: UniFile? = null
        override fun getTranslationDirectory() = directory
        override fun getTranslationIdentity() = "result-reader-test"
        override fun size() = 8
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index.png"
        override fun getTranslationFilename(index: Int) = "$index"
        override fun save(index: Int, file: UniFile): Boolean {
            saves++
            val input = source ?: return false
            file.openOutputStream().use { output -> input.inputStream().use { it.copyTo(output) } }
            return true
        }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
