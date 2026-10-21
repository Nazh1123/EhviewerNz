package com.hippo.ehviewer.gallery

import android.app.Application
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryResultIndexTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var gallery: File
    private val source = GalleryResultIndex.Source("chapter/001.jpg", "123:456", "content://document/one")
    private val tl = GalleryResultIndex.Kind.TL
    private val sr = GalleryResultIndex.Kind.SR
    @Before fun setUp() {
        GalleryResultIndex.root(app).deleteRecursively()
        GalleryResultIndex.dropMemory()
        gallery = File(app.filesDir, "test-gallery").apply { deleteRecursively(); mkdirs() }
    }
    private fun index(persist: Boolean = false, id: String = "test") = GalleryResultIndex.open(
        app, id, null, checkNotNull(UniFile.fromFile(gallery)), persist)
    private fun png(width: Int = 16, height: Int = 24): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    @Test fun missesDoNotCreateAnyFilesEvenAcrossManyPages() {
        val index = index()
        repeat(100) { assertNull(index.find(GalleryResultIndex.Source("$it"), tl)) }
        assertFalse(GalleryResultIndex.root(app).exists())
        assertTrue(gallery.listFiles()!!.isEmpty())
    }
    @Test fun removingTheLastLocalEntryDeletesTheManifestAndItsTemporaryFiles() {
        val index = index(true)
        index.write(source, tl, "png") { it.write(png()) }
        index.write(source, sr, "png", 2) { it.write(png(32, 48)) }
        index.invalidate(source, tl)
        assertTrue(File(gallery, GalleryResultIndex.MANIFEST).isFile)
        index.invalidate(source, sr)
        assertFalse(index.hasEntries())
        for (suffix in listOf("", ".bak", ".part"))
            assertFalse(File(gallery, GalleryResultIndex.MANIFEST + suffix).exists())
    }
    @Test fun localFinalUsesDoneAndSurvivesDirectoryMoveWithoutAbsoluteUris() {
        val image = index(true).write(source, sr, "png", 2) { it.write(png(32, 48)) }
        assertTrue(image.isFile)
        val text = File(gallery, GalleryResultIndex.MANIFEST).readText()
        val row = JSONObject(text).getJSONObject("pages").getJSONObject(source.name).getJSONObject("sr")
        assertEquals("done", row.getString("uri"))
        assertFalse(text.contains(gallery.absolutePath))
        val moved = File(app.filesDir, "moved-gallery").apply { deleteRecursively() }
        assertTrue(gallery.renameTo(moved))
        GalleryResultIndex.dropMemory()
        val reopened = GalleryResultIndex.open(app, "test", null, UniFile.fromFile(moved), false)
        val hit = checkNotNull(reopened.find(source, sr))
        assertEquals(32, hit.width); assertEquals(48, hit.height); assertEquals(2, hit.scale)
    }
    @Test fun pageNamesWithDifferentExtensionsAndDirectoriesNeverCollide() {
        val index = index(true)
        val names = listOf("a/001.jpg", "a/001.png", "b/001.jpg")
        val files = names.map { name -> index.write(GalleryResultIndex.Source(name), tl, "png") { it.write(png()) }.uri }
        assertEquals(3, files.toSet().size)
        assertTrue(names.all { index.find(GalleryResultIndex.Source(it), tl)?.file?.isFile == true })
    }
    @Test fun cachePromotionUpdatesLocalManifestAndRetiresCachedCopy() {
        val cached = index().write(source, tl, "png") { it.write(png()) }
        val hit = checkNotNull(index(true).find(source, tl))
        assertTrue(hit.persistent); assertFalse(cached.exists())
        GalleryResultIndex.clearCache(app, tl)
        assertTrue(hit.file!!.isFile)
    }
    @Test fun skippedAndPartialResultsPromoteWithTheirCheckpointsAndCompletionRetiresCheckpoint() {
        val cached = index()
        cached.write(source, tl, "skip") { it.write("skipped".toByteArray()) }
        assertTrue(checkNotNull(index(true).find(source, tl)).persistent)
        index(true).invalidate(source, tl)
        cached.write(source, tl, "regions") { it.write("checkpoint".toByteArray()) }
        cached.write(source, tl, "partial") { it.write(png()) }
        val partial = checkNotNull(index(true).find(source, tl)).file!!
        val checkpoint = partial.parentFile!!.findFile(partial.name!!.substringBeforeLast('.') + ".regions")!!
        assertTrue(checkpoint.isFile)
        index(true).write(source, tl, "png") { it.write(png()) }
        assertFalse(checkpoint.exists()); assertFalse(partial.exists())
    }
    @Test fun clearingCacheRemovesEmptyManifestAndOrphanBytesButPreservesTheOtherKind() {
        val index = index()
        val file = index.write(source, tl, "png") { it.write(png()) }
        val enhanced = index.write(source, sr, "png", 2) { it.write(png()) }
        file.parentFile!!.createFile("tl.regions")!!.openOutputStream().use { it.write(byteArrayOf(1)) }
        GalleryResultIndex.clearCache(app, tl)
        assertFalse(file.exists()); assertTrue(enhanced.isFile)
        GalleryResultIndex.clearCache(app, sr)
        assertTrue(GalleryResultIndex.root(app).listFiles().orEmpty().isEmpty())
    }
    @Test fun cacheBudgetsAndDeletionAreIndependentForEachKindAndNeverDeleteOriginals() {
        val original = File(gallery, "001.jpg").apply { writeText("original") }
        val index = index()
        val a = index.write(source, tl, "png") { it.write(png()) }
        val b = index.write(source, sr, "png", 2) { it.write(png(32, 48)) }
        GalleryResultIndex.prune(app, tl, 0)
        assertFalse(a.exists()); assertTrue(b.isFile); assertNull(index.find(source, tl))
        assertEquals("original", original.readText())
        GalleryResultIndex.clearCache(app, sr)
        assertFalse(b.exists()); assertNull(index.find(source, sr))
    }
    @Test fun deletedResultsExpireTheirEntriesAndChangedSourcesCannotReuseOldPixels() {
        val index = index(true)
        index.write(source, tl, "png") { it.write(png()) }.delete()
        assertNull(index.find(source, tl))
        index.write(source, tl, "png") { it.write(png()) }
        assertNull(index.find(source.copy(stamp = "changed"), tl))
    }
    @Test fun localRenameWithStableDocumentIdentityRenamesResultsAndDeletesRemovedPages() {
        val index = index(true)
        val old = index.write(source, tl, "png") { it.write(png()) }
        val removed = GalleryResultIndex.Source("removed.jpg", "old", "content://document/removed")
        val discarded = index.write(removed, sr, "png", 2) { it.write(png(32, 48)) }
        val renamed = source.copy(name = "new/002.jpg")
        index.reconcile(listOf(renamed))
        assertFalse(old.exists()); assertFalse(discarded.exists())
        assertNull(index.find(source, tl)); assertNotNull(index.find(renamed, tl))
        assertNull(index.find(removed, sr))
    }
    @Test fun verifiedUpdateCopiesBothKindsToNewPageNamesBeforeParentRemoval() {
        val parent = GalleryResultIndex.open(app, "gallery:71", 71, UniFile.fromFile(gallery), true)
        val old = GalleryResultIndex.Source("00000002")
        parent.write(old, tl, "png") { it.write(png()) }
        parent.write(old, sr, "png", 2) { it.write(png(32, 48)) }
        val target = File(app.filesDir, "target").apply { mkdirs() }
        GalleryResultIndex.copyPage(app, 71, 72, old.name, "00000005", checkNotNull(UniFile.fromFile(gallery)), checkNotNull(UniFile.fromFile(target)))
        parent.write(GalleryResultIndex.Source("00000003"), tl, "skip") { it.write("skipped".toByteArray()) }
        GalleryResultIndex.copyPage(app, 71, 72, "00000003", "00000007", checkNotNull(UniFile.fromFile(gallery)), checkNotNull(UniFile.fromFile(target)))
        gallery.deleteRecursively(); GalleryResultIndex.dropMemory()
        val index = GalleryResultIndex.open(app, "gallery:72", 72, UniFile.fromFile(target), false)
        assertNotNull(index.find(GalleryResultIndex.Source("00000005"), tl))
        assertNotNull(index.find(GalleryResultIndex.Source("00000005"), sr))
        assertNull(index.find(old, tl))
        assertEquals("skip", index.find(GalleryResultIndex.Source("00000007"), tl)?.state)
    }
    @Test fun concurrentTranslationAndSuperResolutionPublishDoNotLoseEntries() {
        val index = index()
        val bytes = png()
        val jobs = listOf(tl, sr).map { kind -> Thread { index.write(source, kind, "png", if (kind == sr) 2 else 1) { it.write(bytes) } }.apply { start() } }
        jobs.forEach { it.join() }
        assertNotNull(index.find(source, tl)); assertNotNull(index.find(source, sr))
    }
    @Test fun failedImageEncodingDoesNotReplaceAnExistingGoodResult() {
        val index = index(true)
        val file = index.write(source, tl, "png") { it.write(png()) }
        try { index.write(source, tl, "png") { it.write("invalid".toByteArray()) }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(file.isFile); assertNotNull(index.find(source, tl))
    }
    @Test fun enhancedNeighborWarmsOnlyAScreenScaleRegionAndReleasesItsPixelCache() {
        val image = index(true).write(source, sr, "png", 2) { it.write(png(1200, 1800)) }
        val page = com.hippo.ehviewer.superresolution.EnhancedTilePage(File(app.cacheDir, "warm-test"), 600, 900, 2)
        page.useFinalImage(image)
        try {
            page.warmFirstRegion(300, 450)
            // Robolectric's region decoder ignores inSampleSize; verify the requested cache key
            // and a single region budget rather than asserting its simulated sampled dimensions.
            val cached = org.robolectric.util.ReflectionHelpers.getField<Map<String, com.hippo.ehviewer.superresolution.EnhancedTilePage.Pixels>>(page, "cache")
            assertEquals(setOf("0:4"), cached.keys)
            assertTrue(page.cachedBytes() in 1..(512L * 512 * 4))
            val warmed = cached.getValue("0:4").bitmap
            val first = checkNotNull(page.acquire(0, 4))
            val second = checkNotNull(page.acquire(0, 4))
            try { assertSame(warmed, first.bitmap); assertSame(first.bitmap, second.bitmap) } finally { first.close(); second.close() }
        } finally { page.trim() }
        assertEquals(0L, page.cachedBytes())
    }
}
