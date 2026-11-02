package com.hippo.ehviewer.superresolution

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 34])
class SuperResolutionStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { SuperResolutionStore.clear(context); SuperResolutionStore.clear(context, true) }
    private fun source(name: String) = File(context.cacheDir, name).apply { writeText(name) }
    private fun bitmap() = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test fun resultSurvivesReopeningAndCacheCleanupWhileOriginalIsUntouched() = runBlocking {
        val source = source("original")
        val store = SuperResolutionStore(context, "gallery:1", SuperResolutionOptions())
        val key = store.key(source)
        val result = bitmap().let { try { store.save(key, it) } finally { it.recycle() } }
        SuperResolutionStore.clear(context)
        assertEquals(result, SuperResolutionStore(context, "gallery:1", SuperResolutionOptions()).find(key))
        assertEquals("original", source.readText())
        SuperResolutionStore.clear(context, true)
        assertNull(store.find(key))
        assertTrue(source.isFile)
    }

    @Test fun sourceBytesModeAndGalleryIdentityCannotReuseTheWrongImage() = runBlocking {
        val source = source("original")
        val store = SuperResolutionStore(context, "one", SuperResolutionOptions())
        val key = store.key(source)
        bitmap().let { try { store.save(key, it) } finally { it.recycle() } }
        assertNull(SuperResolutionStore(context, "two", SuperResolutionOptions()).find(key))
        assertNotEquals(key, SuperResolutionStore(context, "one", SuperResolutionOptions(mode = "auto")).key(source))
        source.appendText("changed")
        assertNotEquals(key, store.key(source))
    }

    @Test fun temporaryResultsCanBePromotedWithoutInferenceAndCorruptionIsRejected() = runBlocking {
        val cached = SuperResolutionStore(context, "one", SuperResolutionOptions(persist = false))
        val key = cached.key(source("original"))
        val file = bitmap().let { try { cached.save(key, it) } finally { it.recycle() } }
        val persistent = SuperResolutionStore(context, "one", SuperResolutionOptions())
        val retained = persistent.retain(file)
        assertNotEquals(file, retained)
        SuperResolutionStore.clear(context)
        assertEquals(retained, persistent.find(key))
        retained.writeText("incomplete PNG")
        assertNull(persistent.find(key))
        assertFalse(retained.exists())
    }

    @Test fun cancellationNeverPublishesAPartialResult() = runBlocking {
        val store = SuperResolutionStore(context, "one", SuperResolutionOptions())
        val key = store.key(source("original"))
        val canceled = launch(start = CoroutineStart.LAZY) {
            currentCoroutineContext().cancel()
            bitmap().let { try { store.save(key, it) } finally { it.recycle() } }
        }
        canceled.start(); canceled.join()
        assertTrue(canceled.isCancelled)
        assertNull(store.find(key))
        assertTrue(File(context.noBackupFilesDir, "super-resolution-pages").walkTopDown().none { it.isFile })
    }

    @Test fun scalesHaveSeparateCacheKeysAndTwoTimesKeepsItsExistingKey() = runBlocking {
        val source = source("original")
        val legacyHash = MessageDigest.getInstance("SHA-256").apply {
            update(SuperResolutionOptions().modelIdentity.toByteArray()); update(source.readBytes())
        }.digest().joinToString("") { "%02x".format(it) }
        val keys = SuperResolutionScale.entries.map { scale ->
            SuperResolutionStore(context, "one", SuperResolutionOptions(scale = scale)).key(source)
        }
        assertEquals(4, keys.toSet().size)
        assertEquals(legacyHash, keys[SuperResolutionScale.X2.ordinal])
        for (quality in SuperResolutionQuality.entries)
            assertEquals(legacyHash, SuperResolutionStore(context, "one", SuperResolutionOptions(quality = quality)).key(source))
    }

    @Test fun originalTileMetadataReopensAndScaleMismatchIsRejected() {
        val store = SuperResolutionStore(context, "one", SuperResolutionOptions())
        val page = store.checkpoint("legacy", 17, 13)
        val metadata = File(page.directory, "page.json")
        metadata.writeText(JSONObject().put("version", 1).put("width", 17).put("height", 13)
            .put("complete", true).toString())
        assertEquals(2, checkNotNull(store.findTiles("legacy")).scale)
        metadata.writeText(JSONObject().put("version", 2).put("width", 17).put("height", 13)
            .put("scale", 4).put("complete", true).toString())
        assertNull(store.findTiles("legacy"))
        assertEquals(2, store.checkpoint("legacy", 17, 13).scale)
    }
}
