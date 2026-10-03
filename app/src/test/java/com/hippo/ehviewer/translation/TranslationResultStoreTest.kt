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
    private fun store(enabled: Boolean = false, name: String = "001", gid: Long? = 1,
                      downloaded: Boolean = true) = TranslationResultStore(cacheDir, UniFile.fromFile(galleryDir),
        TranslationOptions(persistDownloaded = enabled), gid, downloaded, name)
    private fun TranslationResultStore.write(key: String, text: String) = writeImage(key) { it.write(text.toByteArray()) }
    private fun UniFile.readText() = openInputStream().bufferedReader().use { it.readText() }

    @Test fun savedOverlayIsFoundDirectlyByNameWithoutAnIndexOrMatchingHash() {
        val results = store(true)
        assertEquals("001_tl.png", results.write("g1_hash", "overlay").name)
        assertEquals("overlay", store().existingImage("g1_different_hash")?.readText())
        assertNull(store(name = "002").existingImage("g1_hash"))
        assertEquals(setOf("001_tl.png"), savedDir.list()!!.toSet())
    }

    @Test fun sameNamedPagesInDifferentChaptersKeepTheirDirectoryHierarchy() {
        store(true, "Chapter A/001").write("g1_a", "first")
        store(true, "Chapter B/001").write("g1_b", "second")
        assertEquals("first", store(name = "Chapter A/001").existingImage("unused")?.readText())
        assertEquals("second", store(name = "Chapter B/001").existingImage("unused")?.readText())
        assertTrue(File(savedDir, "Chapter A/001_tl.png").isFile)
        assertTrue(File(savedDir, "Chapter B/001_tl.png").isFile)
    }

    @Test fun translatingTheSameSourceReplacesItsNamedOverlayWithoutSuffixes() {
        store(true).write("g1_zh", "Chinese")
        store(true).write("g1_en", "English")
        assertEquals("English", store().existingImage("unused")?.readText())
        assertEquals(setOf("001_tl.png"), savedDir.list()!!.toSet())
    }

    @Test fun savedOverlayCanBeOpenedWithoutAnyMetadata() {
        savedDir.mkdirs()
        File(savedDir, "001_tl.png").writeText("external overlay")
        assertEquals("external overlay", store().existingImage("unused")?.readText())
    }

    @Test fun oldHashNamedImagesAreNotLoadedOrMigrated() {
        savedDir.mkdirs(); legacyDir.mkdirs(); cacheDir.mkdirs()
        File(savedDir, "g1_hash.png").writeText("old full page")
        File(legacyDir, "g1_hash.png").writeText("legacy full page")
        File(cacheDir, "g1_hash.png").writeText("old cache")
        assertNull(store(true).existingImage("g1_hash"))
        assertEquals(setOf("g1_hash.png"), savedDir.list()!!.toSet())
    }

    @Test fun partialPreviewAndCheckpointSurviveCacheAdoptionAndReopening() {
        val key = "g1_partial"
        val resume = TranslationResume(listOf("a", "b"), mapOf(0 to "译文"))
        store().writePartial(key, resume) { it.write("preview".toByteArray()) }
        val persistent = store(true)
        assertEquals("001_tl.partial", persistent.retain(key, persistent.existingImage(key)!!).name)
        TranslationCache(cacheDir).clear()
        assertEquals("preview", store().existingImage(key)?.readText())
        assertEquals(resume, store().readResume(key))
        assertNull(store().readResume("different-configuration"))
        persistent.write(key, "complete")
        assertEquals(setOf("001_tl.png"), savedDir.list()!!.toSet())
        assertNull(store().readResume(key))
    }

    @Test fun brokenOrMissingCheckpointKeepsThePreviewPartial() {
        val key = "g1_partial"
        store().writePartial(key, TranslationResume(listOf("a", "b"), mapOf(0 to "译文"))) { it.write(1) }
        val checkpoint = File(cacheDir, "${key}_tl.regions")
        for (json in listOf("broken", """{"version":1,"key":"wrong","regions":["a"],"translations":{"0":"wrong"}}""",
                """{"version":1,"key":"$key","regions":["a"],"translations":{"4":"wrong"}}""")) {
            checkpoint.writeText(json)
            assertNull(store().readResume(key))
            assertTrue(TranslationResultStore.isPartial(store().existingImage(key)!!))
        }
        checkpoint.delete()
        assertNull(store().readResume(key))
        assertNotNull(store().existingImage(key))
    }

    @Test fun cachePartialAndCheckpointAreEvictedTogether() {
        store().writePartial("g1_partial", TranslationResume(listOf("a", "b"), mapOf(0 to "译文"))) { it.write(ByteArray(200)) }
        TranslationCache(cacheDir, 1).prune()
        assertTrue(cacheDir.list()!!.isEmpty())
    }

    @Test fun persistenceRequiresOptInAndWritableGalleryIdentity() {
        for (results in listOf(store(), store(true, downloaded = false), store(true, gid = null))) {
            assertEquals(File(cacheDir, "g1_page_tl.png").absolutePath, results.write("g1_page", "overlay").uri.path)
        }
        assertEquals(File(savedDir, "001_tl.png").absolutePath, store(true).write("g1_page", "overlay").uri.path)
        val unavailable = TranslationResultStore(cacheDir, null, TranslationOptions(persistDownloaded = true), 1, true, "001")
        assertEquals(File(cacheDir, "g1_page_tl.png").absolutePath, unavailable.write("g1_page", "overlay").uri.path)
    }

    @Test fun savedResultsSurviveCacheClearingAndDisablingPersistence() {
        store(true).write("g1_page", "overlay")
        store(true, "002").recordSkipped("g1_empty")
        TranslationCache(cacheDir, 0).prune()
        TranslationCache(cacheDir).clear()
        assertEquals("overlay", store().existingImage("unused")?.readText())
        assertTrue(store(name = "002").isSkipped("unused"))
    }

    @Test fun enablingPersistenceAdoptsCurrentCacheWithoutEvictingOtherPendingPages() {
        store().write("g1_one", "first")
        store().write("g1_two", "second")
        store().recordSkipped("g1_empty")
        val first = store(true)
        first.retain("g1_one", first.existingImage("g1_one")!!)
        assertTrue(store(true, "003").isSkipped("g1_empty"))
        val second = store(true, "002")
        second.retain("g1_two", second.existingImage("g1_two")!!)
        TranslationCache(cacheDir).clear()
        assertEquals("first", store().existingImage("unused")?.readText())
        assertEquals("second", store(name = "002").existingImage("unused")?.readText())
    }

    @Test fun selectedDeletionRemovesNestedNamedOverlaysAndPreservesOriginalsAndUnrelatedFiles() {
        store(true, "Chapter/001").write("g1_page", "overlay")
        store().write("g1_page", "cache")
        store(gid = 10).write("g10_page", "other gallery cache")
        val original = File(galleryDir, "001.jpg").apply { writeText("original") }
        val unrelated = File(savedDir, "notes.txt").apply { writeText("notes") }
        assertTrue(TranslationResultStore.deleteGalleries(cacheDir, legacyDir, setOf(1), mapOf(1L to UniFile.fromFile(galleryDir)!!)))
        assertFalse(File(savedDir, "Chapter/001_tl.png").exists())
        assertEquals("other gallery cache", File(cacheDir, "g10_page_tl.png").readText())
        assertEquals("original", original.readText())
        assertEquals("notes", unrelated.readText())
    }

    @Test fun retryInvalidatesOnlyTheRequestedOriginalNameAndItsCache() {
        store(true).write("g1_one", "first")
        store(true, "002").write("g1_two", "second")
        store().write("g1_one", "cached")
        store().invalidate("g1_one")
        assertNull(store().existingImage("g1_one"))
        assertEquals("second", store(name = "002").existingImage("unused")?.readText())
    }

    @Test fun interruptedWritePreservesPreviousOverlayAndRemovesTemporaryFile() {
        val results = store(true)
        results.write("g1_page", "complete")
        assertThrows(IllegalStateException::class.java) {
            results.writeImage("g1_page") { it.write(1); error("interrupted") }
        }
        assertEquals("complete", store().existingImage("unused")?.readText())
        assertEquals(listOf("001_tl.png"), savedDir.list()!!.toList())
    }

    @Test fun sourceNamesCannotEscapeTheTranslatedDirectory() {
        assertThrows(IllegalArgumentException::class.java) { store(true, "../001").write("g1_page", "overlay") }
        assertFalse(File(galleryDir, "001_tl.png").exists())
    }
}
