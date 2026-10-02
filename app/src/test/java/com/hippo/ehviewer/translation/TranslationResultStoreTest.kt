package com.hippo.ehviewer.translation

import android.app.Application
import com.hippo.unifile.UniFile
import java.io.File
import li.joye.yakuyomi.engine.TranslationResume
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TranslationResultStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val cacheDir get() = File(temp.root, "cache")
    private val galleryDir get() = File(temp.root, "gallery").apply { mkdirs() }
    private val savedDir get() = File(galleryDir, "_translated")
    private val legacyDir get() = File(temp.root, "legacy")
    private fun store(options: TranslationOptions = TranslationOptions(), gid: Long? = 1,
                      downloaded: Boolean = true) =
        TranslationResultStore(cacheDir, UniFile.fromFile(galleryDir), options, gid, downloaded, legacyDir)

    private fun TranslationResultStore.write(key: String, text: String) =
        writeImage(key) { it.write(text.toByteArray()) }

    private fun UniFile.readText() = openInputStream().bufferedReader().use { it.readText() }

    @Test fun partialPreviewAndCheckpointSurviveReopeningMigrationAndCacheClearing() {
        val key = "g1_partial"
        val checkpoint = TranslationResume(listOf("region-a", "region-b"), mapOf(0 to "你好"))
        val cached = store()
        cached.writePartial(key, checkpoint) { it.write("preview".toByteArray()) }
        assertNull(File(cacheDir, "$key.png").takeIf { it.exists() })
        assertTrue(TranslationResultStore.isPartial(requireNotNull(store().existingImage(key))))
        assertEquals(checkpoint, store().readResume(key))
        val persistent = store(TranslationOptions(persistDownloaded = true))
        persistent.retain(key, requireNotNull(persistent.existingImage(key)))
        TranslationCache(cacheDir).clear()
        assertEquals("preview", store().existingImage(key)!!.readText())
        assertEquals(checkpoint, store().readResume(key))
        persistent.writeImage(key) { it.write("complete".toByteArray()) }
        assertFalse(TranslationResultStore.isPartial(store().existingImage(key)!!))
        assertEquals("complete", store().existingImage(key)!!.readText())
        assertNull(store().readResume(key))
        assertEquals(setOf("$key.png"), savedDir.list()!!.toSet())
    }

    @Test fun brokenCheckpointKeepsPreviewPartialAndCannotSupplyTranslations() {
        val key = "g1_partial"
        val results = store()
        results.writePartial(key, TranslationResume(listOf("a", "b"), mapOf(0 to "你好"))) { it.write(1) }
        val checkpoint = File(cacheDir, "$key.regions")
        for (json in listOf("broken", """{"version":1,"key":"wrong","regions":["a"],"translations":{"0":"wrong"}}""",
                """{"version":1,"key":"$key","regions":["a"],"translations":{"4":"wrong"}}""")) {
            checkpoint.writeText(json)
            assertNull(results.readResume(key))
            assertTrue(TranslationResultStore.isPartial(results.existingImage(key)!!))
        }
        checkpoint.delete()
        assertNull(results.readResume(key))
        assertNotNull(results.existingImage(key))
        results.invalidate(key)
        assertNull(results.existingImage(key))
    }

    @Test fun partialPreviewAndTextAreEvictedAndDeletedTogether() {
        val key = "g1_partial"
        val checkpoint = TranslationResume(listOf("a", "b"), mapOf(0 to "你好"))
        store().writePartial(key, checkpoint) { it.write(ByteArray(200)) }
        TranslationCache(cacheDir, 1).prune()
        assertTrue(cacheDir.list()!!.isEmpty())
        val persistent = store(TranslationOptions(persistDownloaded = true))
        persistent.writePartial(key, checkpoint) { it.write(1) }
        assertTrue(TranslationResultStore.deleteGalleries(cacheDir, legacyDir, setOf(1L), mapOf(1L to UniFile.fromFile(galleryDir)!!)))
        assertTrue(savedDir.list()!!.isEmpty())
    }

    @Test fun persistenceRequiresBothOptInAndDownloadedGalleryIdentity() {
        val source = temp.newFile().apply { writeText("source") }
        val enabled = TranslationOptions(persistDownloaded = true)
        for (results in listOf(store(), store(enabled, downloaded = false), store(enabled, gid = null))) {
            assertEquals(File(cacheDir, "${results.key(source)}.png").absolutePath,
                results.write(results.key(source), "translated").uri.path)
        }
        val results = store(enabled)
        assertEquals(File(savedDir, "${results.key(source)}.png").absolutePath,
            results.write(results.key(source), "translated").uri.path)
    }

    @Test fun savedResultsSurviveCacheEvictionClearingAndDisablingPersistence() {
        val source = temp.newFile().apply { writeText("source") }
        val options = TranslationOptions(persistDownloaded = true)
        val results = store(options)
        val key = results.key(source)
        results.write(key, "translated")
        results.recordSkipped("g1_empty")
        TranslationCache(cacheDir, 0).prune()
        TranslationCache(cacheDir).clear()
        val reopened = store()
        assertEquals("translated", reopened.existingImage(key)?.readText())
        assertTrue(reopened.isSkipped("g1_empty"))
        assertEquals("source", source.readText())
        assertNull(store(options.copy(target = "en")).existingImage(store(options.copy(target = "en")).key(source)))
        source.writeText("changed source")
        assertNull(reopened.existingImage(reopened.key(source)))
    }

    @Test fun enablingPersistenceAdoptsCachedImagesAndNoTextMarkers() {
        val source = temp.newFile().apply { writeText("source") }
        val cached = store()
        val key = cached.key(source)
        cached.write(key, "translated")
        cached.recordSkipped("g1_empty")
        val persistent = store(TranslationOptions(persistDownloaded = true))
        persistent.retain(key, requireNotNull(persistent.existingImage(key)))
        assertTrue(persistent.isSkipped("g1_empty"))
        TranslationCache(cacheDir).clear()
        assertEquals("translated", persistent.existingImage(key)?.readText())
        assertTrue(persistent.isSkipped("g1_empty"))
        assertFalse(savedDir.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun deletingSelectedGalleriesCoversBothStoresAndAllSettingsWithoutTouchingOthers() {
        val original = temp.newFile("original.png").apply { writeText("source") }
        cacheDir.mkdirs(); savedDir.mkdirs(); legacyDir.mkdirs()
        for (dir in listOf(cacheDir, savedDir, legacyDir)) {
            for (name in listOf("g1_zh.png", "g1_en.png", "g1_empty.skip", "g1_page.png.part",
                "g-2_zh.png", "g10_zh.png", "anonymous.png", "g1_notes.txt")) File(dir, name).writeText("data")
        }
        val directories = mapOf(1L to requireNotNull(UniFile.fromFile(galleryDir)),
            -2L to requireNotNull(UniFile.fromFile(galleryDir)))
        assertTrue(TranslationResultStore.deleteGalleries(cacheDir, legacyDir, setOf(1, -2), directories))
        for (dir in listOf(cacheDir, savedDir, legacyDir)) {
            assertEquals(setOf("g10_zh.png", "anonymous.png", "g1_notes.txt"), dir.list()!!.toSet())
        }
        assertEquals("source", original.readText())
        assertTrue(TranslationResultStore.deleteGalleries(cacheDir, legacyDir, setOf(1, -2), directories))
    }

    @Test fun retryInvalidatesBothCopiesAndSkippedMarkersOnlyForRequestedPage() {
        cacheDir.mkdirs(); savedDir.mkdirs(); legacyDir.mkdirs()
        for (dir in listOf(cacheDir, savedDir, legacyDir)) {
            for (name in listOf("g1_one.png", "g1_one.skip", "g1_two.png")) File(dir, name).writeText("data")
        }
        store().invalidate("g1_one")
        for (dir in listOf(cacheDir, savedDir, legacyDir)) assertEquals(listOf("g1_two.png"), dir.list()!!.toList())
    }

    @Test fun legacyImagesAndMarkersMoveOnlyAfterSuccessfulPersistence() {
        legacyDir.mkdirs()
        val image = File(legacyDir, "g1_page.png").apply { writeText("old translated page") }
        val marker = File(legacyDir, "g1_empty.skip").apply { writeText("skipped") }
        val disabled = store()
        assertEquals("old translated page", disabled.existingImage("g1_page")?.readText())
        assertTrue(disabled.isSkipped("g1_empty"))
        assertFalse(savedDir.exists())
        assertTrue(image.exists())
        assertTrue(marker.exists())

        val enabled = store(TranslationOptions(persistDownloaded = true))
        enabled.retain("g1_page", requireNotNull(enabled.existingImage("g1_page")))
        assertTrue(enabled.isSkipped("g1_empty"))
        assertFalse(image.exists())
        assertFalse(marker.exists())
        assertEquals("old translated page", File(savedDir, "g1_page.png").readText())
        assertEquals("skipped", File(savedDir, "g1_empty.skip").readText())
    }

    @Test fun failedMigrationKeepsLegacyResultAndDoesNotPublishPartialFiles() {
        legacyDir.mkdirs()
        val image = File(legacyDir, "g1_page.png").apply { writeText("translated") }
        // A file blocks creation of the required directory.
        savedDir.writeText("unrelated file")
        val enabled = store(TranslationOptions(persistDownloaded = true))
        assertThrows(IllegalStateException::class.java) {
            enabled.retain("g1_page", requireNotNull(enabled.existingImage("g1_page")))
        }
        assertEquals("translated", image.readText())
        assertEquals("unrelated file", savedDir.readText())
    }

    @Test fun interruptedWritePreservesPreviousResultAndRemovesPartialFile() {
        val enabled = store(TranslationOptions(persistDownloaded = true))
        enabled.write("g1_page", "completed")
        assertThrows(IllegalStateException::class.java) {
            enabled.writeImage("g1_page") {
                it.write("partial".toByteArray())
                error("cancelled")
            }
        }
        assertEquals("completed", enabled.existingImage("g1_page")?.readText())
        assertEquals(listOf("g1_page.png"), savedDir.list()!!.toList())
    }

    @Test fun missingGalleryDirectoryUsesCacheWithoutCreatingAnInternalPersistentStore() {
        val results = TranslationResultStore(cacheDir, null,
            TranslationOptions(persistDownloaded = true), 1, true, legacyDir)
        val output = results.write("g1_page", "translated")
        assertEquals(File(cacheDir, "g1_page.png").absolutePath, output.uri.path)
        assertFalse(legacyDir.exists())
    }
}
