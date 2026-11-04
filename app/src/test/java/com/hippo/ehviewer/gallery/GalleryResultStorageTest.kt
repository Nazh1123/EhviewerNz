package com.hippo.ehviewer.gallery

import android.app.Application
import android.os.Looper
import android.text.format.Formatter
import android.widget.ImageButton
import android.widget.TextView
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
        assertEquals(GalleryResultStorage.Usage(0, tl.length()), summary.translation)
        assertEquals(GalleryResultStorage.Usage(0, sr.length()), summary.superResolution)
        assertTrue(GalleryResultStorage.delete(context, selection, Kind.TRANSLATION))
        assertFalse(tl.exists()); assertTrue(sr.exists()); assertTrue(other.exists())
        assertEquals(GalleryProcessingProgress.Counts(0, 1), legacyCounts(context, 12))
        assertTrue(GalleryResultStorage.delete(context, selection, Kind.SUPER_RESOLUTION))
        assertFalse(sr.exists()); assertTrue(other.exists())
        assertEquals(GalleryProcessingProgress.Counts(0, 0), legacyCounts(context, 12))
    }

    @Test fun dialogWaitsForStatisticsAndCancelDoesNotDelete() {
        val tl = write(context.cacheDir, "translated-pages/g13_one_tl.png")
        val sr = write(SuperResolutionStore.galleryRoots(context, 13)[0], "one.png")
        val themed = ContextThemeWrapper(context, R.style.AppTheme)
        var confirmed = false
        GalleryResultStorage.showDeleteDialog(themed, listOf(info(13)), Runnable { confirmed = true })
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val translation = requireNotNull(dialog.findViewById<ImageButton>(R.id.delete_translation_results))
            val enhancement = requireNotNull(dialog.findViewById<ImageButton>(R.id.delete_super_resolution_results))
            val original = requireNotNull(dialog.findViewById<ImageButton>(R.id.delete_original_results))
            assertFalse(translation.isEnabled)
            assertFalse(enhancement.isEnabled)
            assertFalse(original.isEnabled)
            await { translation.isEnabled && enhancement.isEnabled }
            assertEquals(themed.getString(R.string.translation_delete_results), translation.contentDescription)
            assertEquals(themed.getString(R.string.gallery_results_delete_sr), enhancement.contentDescription)
            assertTrue(original.isShown)
            assertFalse(original.isEnabled)
            assertEquals(themed.getString(R.string.gallery_results_delete_original), original.contentDescription)
            assertEquals("0", dialog.findViewById<TextView>(R.id.gallery_results_translation_count)!!.text.toString())
            assertEquals(Formatter.formatFileSize(themed, tl.length()),
                dialog.findViewById<TextView>(R.id.gallery_results_translation_size)!!.text.toString())
            assertEquals(themed.getString(R.string.gallery_results_originals_unavailable),
                dialog.findViewById<TextView>(R.id.gallery_results_summary)!!.text.toString())
            assertEquals(android.view.View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            assertEquals(android.view.View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirmed); assertTrue(tl.exists()); assertTrue(sr.exists())
        } finally { dialog.dismiss() }
    }

    @Test fun rowDeleteButtonsRequireConfirmationAndDeleteOnlyTheirOwnResultType() {
        for ((index, buttonId) in listOf(R.id.delete_translation_results,
            R.id.delete_super_resolution_results, R.id.delete_original_results).withIndex()) {
            val gallery = temp.newFolder()
            val source = write(gallery, "00000001.jpg")
            val results = listOf(write(gallery, "_translated/00000001_tl.png"),
                write(gallery, "_super_resolution/00000001_sr.png"),
                write(gallery, "_original/00000001_orn.jpg"))
            val uri = Uri.fromFile(gallery)
            val handler = UriHandler { _, candidate -> if (candidate == uri) UniFile.fromFile(gallery) else null }
            UniFile.addUriHandler(handler)
            val selected = DownloadInfo(400L + index).apply {
                archiveUri = LocalFolderGallerySource.create(uri, "").encode()
            }
            var callbacks = 0
            GalleryResultStorage.showDeleteDialog(ContextThemeWrapper(context, R.style.AppTheme),
                listOf(selected), Runnable { callbacks++ })
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            try {
                val button = requireNotNull(dialog.findViewById<ImageButton>(buttonId))
                await { button.isEnabled }
                assertEquals("1", dialog.findViewById<TextView>(R.id.gallery_results_initial_count)!!.text.toString())
                button.performClick()
                val cancelled = ShadowDialog.getLatestDialog() as AlertDialog
                assertNotSame(dialog, cancelled)
                assertTrue(dialog.isShowing)
                assertEquals(0, callbacks)
                assertTrue(results.all { it.exists() })
                assertEquals(button.contentDescription,
                    cancelled.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text)
                assertEquals(dialog.context.getString(R.string.gallery_results_delete_confirm, 1),
                    cancelled.findViewById<TextView>(android.R.id.message)!!.text)
                cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(dialog.isShowing)
                assertEquals(0, callbacks)
                assertTrue(results.all { it.exists() })

                button.performClick()
                val dismissed = ShadowDialog.getLatestDialog() as AlertDialog
                dismissed.cancel()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(dialog.isShowing)
                assertEquals(0, callbacks)
                assertTrue(results.all { it.exists() })

                button.performClick()
                val confirmed = ShadowDialog.getLatestDialog() as AlertDialog
                button.performClick()
                assertSame(confirmed, ShadowDialog.getLatestDialog())
                confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
                assertEquals(1, callbacks)
                await { !results[index].exists() }
                assertTrue(source.exists())
                assertTrue(results.filterIndexed { i, _ -> i != index }.all { it.exists() })
            } finally {
                dialog.dismiss()
                UniFile.removeUriHandler(handler)
            }
        }
    }

    @Test fun dismissingTableClosesPendingConfirmationWithoutDeleting() {
        val translated = write(context.cacheDir, "translated-pages/g415_one_tl.png")
        var callbacks = 0
        GalleryResultStorage.showDeleteDialog(ContextThemeWrapper(context, R.style.AppTheme),
            listOf(info(415)), Runnable { callbacks++ })
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val button = requireNotNull(dialog.findViewById<ImageButton>(R.id.delete_translation_results))
            await { button.isEnabled }
            button.performClick()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(confirmation.isShowing)
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(confirmation.isShowing)
            assertEquals(0, callbacks)
            assertTrue(translated.exists())
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
            assertEquals(GalleryProcessingProgress.Counts(1, 0), legacyCounts(context, 14))
        } finally { UniFile.removeUriHandler(handler) }
    }

    @Test fun matchedFileDeletionPrunesEmptyNestedDirectoriesButRetainsUnrecognizedFiles() = runBlocking {
        val gallery = temp.newFolder()
        val original = write(gallery, "001.jpg", "original")
        val matched = write(gallery, "_translated/chapter/old/001_tl.png")
        val keep = write(gallery, "_translated/notes/keep.txt", "keep")
        val enhanced = write(gallery, "_super_resolution/chapter/001_sr.png")
        val uri = Uri.fromFile(gallery)
        val handler = UriHandler { _, candidate -> if (candidate == uri) UniFile.fromFile(gallery) else null }
        UniFile.addUriHandler(handler)
        try {
            val selected = DownloadInfo(91).apply { archiveUri = LocalFolderGallerySource.create(uri, "").encode() }
            assertTrue(GalleryResultStorage.delete(context, listOf(selected), Kind.TRANSLATION))
            assertFalse(matched.exists()); assertFalse(File(gallery, "_translated/chapter").exists())
            assertTrue(keep.exists()); assertTrue(enhanced.exists()); assertTrue(original.exists())
            keep.delete()
            assertTrue(GalleryResultStorage.delete(context, listOf(selected), Kind.TRANSLATION))
            assertFalse(File(gallery, "_translated").exists())
            val tileRoot = SuperResolutionStore.galleryRoots(context, 91)[0]
            write(tileRoot, "page.tiles/0.png")
            assertTrue(GalleryResultStorage.delete(context, listOf(selected), Kind.SUPER_RESOLUTION))
            assertFalse(File(gallery, "_super_resolution").exists()); assertFalse(tileRoot.exists())
            assertEquals("original", original.readText())
        } finally { UniFile.removeUriHandler(handler) }
    }

    @Test fun originalInventoryExcludesResultsAndCountsOverlappingFoldersOnlyOnce() = runBlocking {
        val gallery = temp.newFolder()
        val first = write(gallery, "001.JPG", "original one")
        val second = write(gallery, "chapter/002.webp", "original two")
        write(gallery, "_translated/001_tl.png")
        write(gallery, "_SUPER_RESOLUTION/001_sr.png")
        write(gallery, "chapter/_translated/002_tl.png")
        write(gallery, "_original/001_orn.jpg")
        write(gallery, "chapter/003.jpg.part")
        write(gallery, "metadata.json")
        val usage = GalleryResultStorage.collectOriginals(listOf(checkNotNull(UniFile.fromFile(gallery)),
            checkNotNull(UniFile.fromFile(File(gallery, "chapter")))))
        assertEquals(GalleryResultStorage.Usage(2, first.length() + second.length()), usage)
    }

    @Test fun deletingDownloadedOriginalsKeepsInitialImagesAndOtherResults() = runBlocking {
        val gallery = temp.newFolder()
        val source = write(gallery, "00000001.jpg")
        val original = write(gallery, "_original/00000001_orn.jpg")
        val unindexed = write(gallery, "_original/00000002_orn.jpg")
        val unrelated = write(gallery, "_original/notes.txt")
        val tl = write(gallery, "_translated/00000001_tl.png")
        val sr = write(gallery, "_super_resolution/00000001_sr.png")
        val uri = Uri.fromFile(gallery)
        val handler = UriHandler { _, candidate -> if (candidate == uri) UniFile.fromFile(gallery) else null }
        UniFile.addUriHandler(handler)
        try {
            val gid = 311L
            val selected = DownloadInfo(gid).apply { archiveUri = LocalFolderGallerySource.create(uri, "").encode() }
            write(gallery, GalleryResultIndex.MANIFEST,
                """{"version":1,"gallery":"gallery:$gid","pages":{"00000001":{"orn":{"uri":"done","format":"jpg","state":"complete"}}}}""")
            GalleryProcessingProgress.record(context, gid, Kind.ORIGINAL, "00000001", UniFile.fromFile(original)).join()
            val summary = GalleryResultStorage.inspect(context, listOf(selected))
            assertEquals(GalleryResultStorage.Usage(1, source.length()), summary.originals)
            assertEquals(GalleryResultStorage.Usage(1, original.length() + unindexed.length()), summary.downloadedOriginals)
            assertTrue(GalleryResultStorage.delete(context, listOf(selected), Kind.ORIGINAL))
            assertEquals(0, legacyCounts(context, gid).originals)
            assertFalse(original.exists())
            assertFalse(unindexed.exists())
            assertTrue(listOf(source, tl, sr, unrelated).all { it.exists() })
        } finally { UniFile.removeUriHandler(handler) }
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
