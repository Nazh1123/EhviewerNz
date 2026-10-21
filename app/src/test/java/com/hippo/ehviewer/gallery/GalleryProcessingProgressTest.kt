package com.hippo.ehviewer.gallery

import android.app.Application
import android.net.Uri
import com.hippo.ehviewer.gallery.GalleryProcessingProgress.Kind
import com.hippo.ehviewer.translation.TranslationCache
import com.hippo.unifile.UniFile
import com.hippo.unifile.UriHandler
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class GalleryProcessingProgressTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val files = mutableMapOf<Uri, UniFile>()
    // Android file URI paths do not round-trip Windows drive letters under Robolectric.
    private val handler = UriHandler { _, uri -> files[uri] }
    @Before fun registerFiles() { UniFile.addUriHandler(handler) }
    @After fun unregisterFiles() { UniFile.removeUriHandler(handler) }

    private fun file(name: String): UniFile {
        val file = File(temp.root, name)
        file.parentFile!!.mkdirs()
        file.writeText("image")
        return checkNotNull(UniFile.fromFile(file)).also { files[it.uri] = it }
    }
    @Test fun queryingAbsentImportHistoryDoesNotCreateADatabase() = runBlocking {
        val database = context.getDatabasePath("gallery_processing_progress.db")
        assertFalse(database.exists())
        assertTrue(GalleryProcessingProgress.savedResults(context, 99).isEmpty())
        assertFalse(database.exists())
    }

    @Test fun oldResultsAreCountedOnlyWhenAccessedAndReadingCountsDoesNotProbeFiles() = runBlocking {
        val old = file("gallery/_translated/001_tl.png")
        file("gallery/_translated/002_tl.png")
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, -1))
        GalleryProcessingProgress.record(context, -1, Kind.TRANSLATION, "001", old).join()
        assertEquals(GalleryProcessingProgress.Counts(1, 0), GalleryProcessingProgress.get(context, -1))
        old.delete()
        // A metadata read never scans the folder or verifies files. An explicit access/delete event corrects it.
        assertEquals(GalleryProcessingProgress.Counts(1, 0), GalleryProcessingProgress.get(context, -1))
        GalleryProcessingProgress.record(context, -1, Kind.TRANSLATION, "001", null).join()
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, -1))
    }

    @Test fun deletingSharedCacheFileUpdatesAllReferencesAndKeepsUnrelatedSavedPages() = runBlocking {
        val cached = file("cache/result.png")
        val saved = file("gallery/002_tl.png")
        GalleryProcessingProgress.record(context, -2, Kind.TRANSLATION, "001", cached).join()
        GalleryProcessingProgress.record(context, -3, Kind.TRANSLATION, "001", cached).join()
        GalleryProcessingProgress.record(context, -2, Kind.TRANSLATION, "002", saved).join()
        GalleryProcessingProgress.record(context, -2, Kind.SUPER_RESOLUTION, "002", saved).join()
        GalleryProcessingProgress.forget(context, listOf(cached.uri)).join()
        assertEquals(GalleryProcessingProgress.Counts(1, 1), GalleryProcessingProgress.get(context, -2))
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, -3))
    }

    @Test fun clearingTileSubtreePreservesExportedPagesAndSimilarlyNamedSiblings() = runBlocking {
        // Processing a successful clear event uses only URI strings, without file probing.
        fun reference(uri: String) = checkNotNull(UniFile.fromSingleUri(context, Uri.parse(uri)))
        GalleryProcessingProgress.record(context, -4, Kind.SUPER_RESOLUTION, "001",
            reference("file:///cache/sr/gallery/page.tiles/page.json")).join()
        GalleryProcessingProgress.record(context, -4, Kind.SUPER_RESOLUTION, "002",
            reference("file:///saved/002_sr.png")).join()
        GalleryProcessingProgress.record(context, -5, Kind.SUPER_RESOLUTION, "001",
            reference("file:///cache/sr-other/gallery/page.tiles/page.json")).join()
        GalleryProcessingProgress.forgetUnder(context, Uri.parse("file:///cache/sr")).join()
        assertEquals(GalleryProcessingProgress.Counts(0, 1), GalleryProcessingProgress.get(context, -4))
        assertEquals(GalleryProcessingProgress.Counts(0, 1), GalleryProcessingProgress.get(context, -5))
    }

    @Test fun cacheEvictionAndClearSendOnlySuccessfullyDeletedFiles() = runBlocking {
        val dir = temp.newFolder("cache")
        val events = mutableListOf<Job>()
        val cache = TranslationCache(dir, 12) { removed ->
            events += GalleryProcessingProgress.forget(context, removed.map { Uri.fromFile(it) })
        }
        val old = cache.image("old").apply { writeText("12345678"); setLastModified(1000) }
        val recent = cache.image("recent").apply { writeText("abcdefgh"); setLastModified(2000) }
        GalleryProcessingProgress.record(context, -6, Kind.TRANSLATION, "001", UniFile.fromFile(old)).join()
        GalleryProcessingProgress.record(context, -6, Kind.TRANSLATION, "002", UniFile.fromFile(recent)).join()
        cache.prune()
        events.forEach { it.join() }
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertEquals(GalleryProcessingProgress.Counts(1, 0), GalleryProcessingProgress.get(context, -6))
        cache.clear()
        events.forEach { it.join() }
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, -6))
    }

    @Test fun partialBulkDeletionCorrectsOnlyAffectedKnownEntries() = runBlocking {
        val existing = file("gallery/existing.png")
        val removed = file("gallery/removed.png")
        GalleryProcessingProgress.record(context, -7, Kind.TRANSLATION, "001", existing).join()
        GalleryProcessingProgress.record(context, -7, Kind.TRANSLATION, "002", removed).join()
        GalleryProcessingProgress.record(context, -7, Kind.SUPER_RESOLUTION, "002", removed).join()
        GalleryProcessingProgress.record(context, -8, Kind.TRANSLATION, "002", removed).join()
        removed.delete()
        GalleryProcessingProgress.removeMissing(context, setOf(-7L), Kind.TRANSLATION).join()
        assertEquals(GalleryProcessingProgress.Counts(1, 1), GalleryProcessingProgress.get(context, -7))
        assertEquals(GalleryProcessingProgress.Counts(1, 0), GalleryProcessingProgress.get(context, -8))
    }

    @Test fun deletingPreviousCacheAfterAdoptionDoesNotRemoveTheNewSavedReference() = runBlocking {
        val cached = file("cache/001.png")
        val saved = file("gallery/001_tl.png")
        GalleryProcessingProgress.record(context, -9, Kind.TRANSLATION, "001", cached).join()
        GalleryProcessingProgress.record(context, -9, Kind.TRANSLATION, "001", saved).join()
        GalleryProcessingProgress.forget(context, listOf(cached.uri)).join()
        assertEquals(GalleryProcessingProgress.Counts(1, 0), GalleryProcessingProgress.get(context, -9))
        GalleryProcessingProgress.forget(context, listOf(saved.uri)).join()
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, -9))
    }

    @Test fun publishedPagesAreDurableDeduplicatedAndCanBeRemovedIndependently() = runBlocking {
        val result = file("cache/result.png")
        GalleryProcessingProgress.record(context, -71, Kind.TRANSLATION, "chapter/001", result).join()
        GalleryProcessingProgress.record(context, -71, Kind.TRANSLATION, "chapter/001", result).join()
        GalleryProcessingProgress.record(context, -71, Kind.SUPER_RESOLUTION, "chapter/001", result).join()
        assertEquals("1/100  -  TL 1/100", GalleryProcessingProgress.get(context, -71).format(1, 100))
        assertEquals("SR 1/100", GalleryProcessingProgress.get(context, -71).secondary(100))
        GalleryProcessingProgress.record(context, -71, Kind.TRANSLATION, "chapter/001", null).join()
        assertEquals(GalleryProcessingProgress.Counts(0, 1), GalleryProcessingProgress.get(context, -71))
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, -72))
    }

    @Test fun progressFitsGalleryBoundsAndSourceKeysMatchSavedFilenameSanitization() {
        assertEquals("100/100  -  TL 100/100", GalleryProcessingProgress.Counts(101, -1).format(200, 100))
        assertEquals("1/100", GalleryProcessingProgress.Counts(0, 0).format(1, 100))
        assertEquals("1/100  -  TL 25/100", GalleryProcessingProgress.Counts(25, 0).format(1, 100))
        assertEquals("1/100  -  SR 28/100", GalleryProcessingProgress.Counts(0, 28).format(1, 100))
        assertEquals("1/100  -  TL 25/100", GalleryProcessingProgress.Counts(25, 28).format(1, 100))
        assertEquals("SR 28/100", GalleryProcessingProgress.Counts(25, 28).secondary(100))
        assertNull(GalleryProcessingProgress.Counts(0, 28).secondary(100))
        assertNull(GalleryProcessingProgress.Counts(25, 0).secondary(100))
        assertEquals("chapter/001", GalleryProcessingProgress.sourceKey("chapter\\001"))
        assertEquals("page", GalleryProcessingProgress.sourceKey("/"))
        try {
            GalleryProcessingProgress.sourceKey("chapter/../001")
            fail("Traversal must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
