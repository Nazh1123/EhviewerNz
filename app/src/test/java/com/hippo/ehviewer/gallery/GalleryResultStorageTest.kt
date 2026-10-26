package com.hippo.ehviewer.gallery

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.GalleryProcessingProgress.Kind
import com.hippo.ehviewer.superresolution.SuperResolutionStore
import com.hippo.unifile.UniFile
import com.hippo.unifile.UriHandler
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GalleryResultStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun info(gid: Long) = DownloadInfo(gid).apply { archiveUri = "content://archive/$gid" }
    private fun write(root: File, path: String, text: String = "pixels") = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(text)
    }

    @Test fun inventoryIncludesWebpResultsAndTemporaryWebpExports() = runBlocking {
        val gallery = temp.newFolder()
        val overlay = write(gallery, "_translated/001_tl.webp")
        val image = write(gallery, "_super_resolution/001_sr.webp")
        val partial = write(gallery, "_super_resolution/002_sr.webp.part")
        write(gallery, "_super_resolution/keep.webp")
        val directories = listOf(checkNotNull(UniFile.fromFile(gallery)))
        assertEquals(setOf(overlay.name), GalleryResultStorage.collect(context, setOf(9876), directories,
            Kind.TRANSLATION).map { it.name }.toSet())
        assertEquals(setOf(image.name, partial.name), GalleryResultStorage.collect(context, setOf(9876), directories,
            Kind.SUPER_RESOLUTION).map { it.name }.toSet())
    }

    @Test fun inventoryScopesByGalleryAndTypeAndIncludesPartialStorage() = runBlocking {
        val gallery = temp.newFolder()
        val translated = write(gallery, "_translated/chapter/001_tl.png")
        val partial = write(gallery, "_translated/chapter/002_tl.partial", "partial")
        write(gallery, "_translated/keep.txt")
        write(gallery, "original.png")
        val exported = write(gallery, "_super_resolution/chapter/001_sr.png", "exported")
        val exportPart = write(gallery, "_super_resolution/002_sr.png.part", "unfinished")
        write(gallery, "_super_resolution/keep.png")
        val cache = write(context.cacheDir, "translated-pages/g11_hash_tl.png")
        write(context.cacheDir, "translated-pages/g110_hash_tl.png")
        val legacy = write(context.noBackupFilesDir, "translated-galleries/g11_old.png")
        val roots = SuperResolutionStore.galleryRoots(context, 11)
        val tile = write(roots[0], "hash.tiles/0.png", "tile")
        val manifest = write(roots[0], "hash.tiles/page.json", "{\"complete\":true}")
        val saved = write(roots[1], "legacy.png", "saved")
        val directories = listOf(checkNotNull(UniFile.fromFile(gallery)))
        val tl = GalleryResultStorage.collect(context, setOf(11), directories, Kind.TRANSLATION)
        assertEquals(setOf(translated.name, partial.name, cache.name, legacy.name), tl.map { it.name }.toSet())
        val sr = GalleryResultStorage.collect(context, setOf(11), directories, Kind.SUPER_RESOLUTION)
        assertEquals(listOf(tile, manifest, saved, exported, exportPart).sumOf { it.length() }, sr.sumOf { it.length() })
        assertEquals(5, sr.size)
    }

    @Test fun selectedDeletionRemovesOneTypeAndUpdatesCountsWithoutTouchingOtherGalleries() = runBlocking {
        val tl = write(context.cacheDir, "translated-pages/g12_one_tl.png")
        val other = write(context.cacheDir, "translated-pages/g120_other_tl.png")
        val sr = write(SuperResolutionStore.galleryRoots(context, 12)[1], "one.png")
        GalleryProcessingProgress.record(context, 12, Kind.TRANSLATION, "one", UniFile.fromFile(tl)).join()
        GalleryProcessingProgress.record(context, 12, Kind.SUPER_RESOLUTION, "one", UniFile.fromFile(sr)).join()
        val selection = listOf(info(12))
        val summary = GalleryResultStorage.inspect(context, selection)
        assertEquals(GalleryResultStorage.Usage(1, tl.length()), summary.translation)
        assertEquals(GalleryResultStorage.Usage(1, sr.length()), summary.superResolution)
        assertTrue(GalleryResultStorage.delete(context, selection, Kind.TRANSLATION))
        assertFalse(tl.exists()); assertTrue(sr.exists()); assertTrue(other.exists())
        assertEquals(GalleryProcessingProgress.Counts(0, 1), GalleryProcessingProgress.get(context, 12))
        assertTrue(GalleryResultStorage.delete(context, selection, Kind.SUPER_RESOLUTION))
        assertFalse(sr.exists()); assertTrue(other.exists())
        assertEquals(GalleryProcessingProgress.Counts(0, 0), GalleryProcessingProgress.get(context, 12))
    }

    @Test fun dialogWaitsForStatisticsAndCancelDoesNotDelete() {
        val tl = write(context.cacheDir, "translated-pages/g13_one_tl.png")
        val sr = write(SuperResolutionStore.galleryRoots(context, 13)[0], "one.png")
        val themed = ContextThemeWrapper(context, R.style.AppTheme)
        var confirmed = false
        GalleryResultStorage.showDeleteDialog(themed, listOf(info(13)), Runnable { confirmed = true })
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            await { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled }
            assertEquals(themed.getString(R.string.translation_delete_results), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
            assertEquals(themed.getString(R.string.gallery_results_delete_sr), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirmed); assertTrue(tl.exists()); assertTrue(sr.exists())
        } finally { dialog.dismiss() }
    }

    @Test fun deletionIncludesNamedExportsAndPrivateTilesWhilePreservingOriginalsAndTranslations() = runBlocking {
        val gallery = temp.newFolder()
        val original = write(gallery, "001.png")
        val translated = write(gallery, "_translated/001_tl.png")
        val exported = write(gallery, "_super_resolution/001_sr.png")
        val unrelated = write(gallery, "_super_resolution/keep.png")
        val privateTile = write(SuperResolutionStore.galleryRoots(context, 14)[1], "one.tiles/0.png")
        val uri = Uri.fromFile(gallery)
        val handler = UriHandler { _, candidate -> if (candidate == uri) UniFile.fromFile(gallery) else null }
        UniFile.addUriHandler(handler)
        try {
            val selected = DownloadInfo(14).apply { archiveUri = LocalFolderGallerySource.create(uri, "").encode() }
            GalleryProcessingProgress.record(context, 14, Kind.TRANSLATION, "001", UniFile.fromFile(translated)).join()
            GalleryProcessingProgress.record(context, 14, Kind.SUPER_RESOLUTION, "001", UniFile.fromFile(exported)).join()
            val summary = GalleryResultStorage.inspect(context, listOf(selected))
            assertEquals(GalleryResultStorage.Usage(1, original.length()), summary.originals)
            assertTrue(GalleryResultStorage.delete(context, listOf(selected), Kind.SUPER_RESOLUTION))
            assertFalse(exported.exists()); assertFalse(privateTile.exists())
            assertTrue(original.exists()); assertTrue(translated.exists()); assertTrue(unrelated.exists())
            assertEquals(GalleryProcessingProgress.Counts(1, 0), GalleryProcessingProgress.get(context, 14))
        } finally { UniFile.removeUriHandler(handler) }
    }

    @Test fun originalInventoryExcludesResultsAndCountsOverlappingFoldersOnlyOnce() = runBlocking {
        val gallery = temp.newFolder()
        val first = write(gallery, "001.JPG", "original one")
        val second = write(gallery, "chapter/002.webp", "original two")
        write(gallery, "_translated/001_tl.png")
        write(gallery, "_SUEPR_RESOLUTION/001_sr.png")
        write(gallery, "chapter/_translated/002_tl.png")
        write(gallery, "chapter/003.jpg.part")
        write(gallery, "metadata.json")
        val usage = GalleryResultStorage.collectOriginals(listOf(checkNotNull(UniFile.fromFile(gallery)),
            checkNotNull(UniFile.fromFile(File(gallery, "chapter")))))
        assertEquals(GalleryResultStorage.Usage(2, first.length() + second.length()), usage)
    }

    @Test fun archiveOriginalsUseKnownPageCountAndContainerBytesWithoutExtracting() = runBlocking {
        val archive = temp.newFile("source.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("001.jpg", "002.png", "metadata.txt")) {
                zip.putNextEntry(ZipEntry(name)); zip.write("source bytes".toByteArray()); zip.closeEntry()
            }
        }
        val uri = Uri.fromFile(archive)
        val handler = UriHandler { _, candidate -> if (candidate == uri) UniFile.fromFile(archive) else null }
        UniFile.addUriHandler(handler)
        try {
            val selected = DownloadInfo(21).apply { archiveUri = uri.toString(); pages = 2 }
            val summary = GalleryResultStorage.inspect(context, listOf(selected, selected))
            assertEquals(GalleryResultStorage.Usage(2, archive.length()), summary.originals)
            assertEquals(listOf("source.zip"), temp.root.list()!!.toList())
        } finally { UniFile.removeUriHandler(handler) }
    }

    @Test fun unavailableOriginalDoesNotPreventInspectingOrDeletingProcessedResults() = runBlocking {
        val translated = write(context.cacheDir, "translated-pages/g22_one_tl.png")
        val summary = GalleryResultStorage.inspect(context, listOf(info(22)))
        assertNull(summary.originals)
        assertEquals(translated.length(), summary.translation.bytes)
        assertTrue(GalleryResultStorage.delete(context, listOf(info(22)), Kind.TRANSLATION))
        assertFalse(translated.exists())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
