package com.hippo.ehviewer.superresolution

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.unifile.UniFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34])
class GallerySuperResolutionSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var sources: List<File>
    private val identity = "sr-session-test"
    @Before fun prepare() = runBlocking {
        SuperResolutionSettings(context).preferences.edit().clear().putString(SuperResolutionSettings.AHEAD, "1").commit()
        SuperResolutionStore.clear(context); SuperResolutionStore.clear(context, true)
        sources = (0..3).map { index -> File(context.cacheDir, "original-$index").apply { writeText("original source $index") } }
        val store = SuperResolutionStore(context, identity, SuperResolutionOptions(ahead = 1))
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try { for (source in sources) store.save(store.key(source), bitmap) } finally { bitmap.recycle() }
    }

    @Test fun cachedWindowAdvancesWithoutModelsAndOriginalsStayUntouched() {
        val session = GallerySuperResolutionSession(context, Provider(sources), identity, null)
        try {
            session.pageChanged(0); session.enable()
            await { !session.working && session.states.keys.containsAll(listOf(0, 1)) }
            assertEquals(setOf(0, 1), session.states.keys)
            session.pageChanged(2)
            await { !session.working && session.states.size == 4 }
            assertEquals(listOf(2, 3), session.pages())
            assertEquals(2, session.completed)
            assertTrue(session.states.values.all { it == R.string.sr_done })
            assertEquals((0..3).map { "original source $it" }, sources.map { it.readText() })
        } finally { close(session) }
    }

    @Test fun fullGalleryWrapsAndReopenedSessionRestoresAfterTemporaryCleanup() {
        val first = GallerySuperResolutionSession(context, Provider(sources), identity, null)
        try {
            first.pageChanged(2); first.full()
            await { !first.working && first.states.size == 4 }
            assertFalse(first.fullGallery)
        } finally { close(first) }
        SuperResolutionStore.clear(context)
        val reopened = GallerySuperResolutionSession(context, Provider(sources), identity, null)
        try {
            reopened.pageChanged(0); reopened.enable()
            await { !reopened.working && reopened.states.size == 2 }
            assertTrue(reopened.states.values.all { it == R.string.sr_done })
        } finally { close(reopened) }
    }

    @Test fun disablingBeforeDispatchDoesNotLeaveWorkerOrServiceWorkRegistered() {
        val session = GallerySuperResolutionSession(context, Provider(sources), identity, null)
        try {
            session.pageChanged(0); session.enable(); session.disable()
            await { !session.working }
            assertFalse(session.enabled)
            assertNull(session.activePage)
            session.enable()
            await { !session.working && session.completed == 2 }
        } finally { close(session) }
    }

    @Test fun persistentCachedPagesAreExportedWithoutModelsWhenTheGalleryIsReopened() {
        val gallery = File(context.cacheDir, "sr-export-gallery").apply { deleteRecursively(); mkdirs() }
        val provider = Provider(sources, checkNotNull(UniFile.fromFile(gallery)))
        val session = GallerySuperResolutionSession(context, provider, identity, null)
        try {
            session.pageChanged(0); session.enable()
            await { !session.working && session.states.size == 2 }
            assertTrue(session.states.values.all { it == R.string.sr_done })
            assertTrue(File(gallery, "_suepr_resolution/0_sr.png").isFile)
            assertTrue(File(gallery, "_suepr_resolution/1_sr.png").isFile)
            assertEquals((0..3).map { "original source $it" }, sources.map { it.readText() })
        } finally { close(session) }
    }

    @Test fun temporaryPagesDoNotExportIntoTheGallery() {
        SuperResolutionSettings(context).preferences.edit().putBoolean(SuperResolutionSettings.PERSIST, false).commit()
        val gallery = File(context.cacheDir, "sr-temporary-gallery").apply { deleteRecursively(); mkdirs() }
        val session = GallerySuperResolutionSession(context, Provider(sources, checkNotNull(UniFile.fromFile(gallery))), identity, null)
        try {
            session.pageChanged(0); session.enable()
            await { !session.working && session.states.size == 2 }
            assertTrue(session.states.values.all { it == R.string.sr_done })
            assertFalse(File(gallery, "_suepr_resolution").exists())
        } finally { close(session) }
    }

    private fun close(session: GallerySuperResolutionSession) { session.close(); await { !session.working } }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate()) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            assertTrue("Timed out waiting for SR queue", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }
    private class Provider(private val files: List<File>, private val directory: UniFile? = null) : GalleryProvider2() {
        override fun createTranslationProvider(context: Context) = Provider(files, directory)
        override fun getTranslationDirectory() = directory
        override fun size() = files.size
        override fun getError(): String? = null
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun getImageFilename(index: Int) = "$index"
        override fun save(index: Int, file: UniFile): Boolean {
            file.openOutputStream().use { out -> files[index].inputStream().use { it.copyTo(out) } }
            return true
        }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
