package com.hippo.ehviewer.gallery

import android.app.Application
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import java.io.File
import kotlinx.coroutines.runBlocking
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
class GalleryResultImporterTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var gallery: File
    private val tl = GalleryResultIndex.Kind.TL
    private val sr = GalleryResultIndex.Kind.SR
    private val identity = "gallery:71"
    @Before fun setup() {
        runBlocking {
            for (gid in listOf(71L, 72L)) if (GalleryProcessingProgress.savedResults(app, gid).isNotEmpty()) {
                GalleryProcessingProgress.remove(app, gid)
                GalleryProcessingProgress.savedResults(app, gid) // Wait for the serial deletion to finish.
            }
        }
        GalleryResultIndex.dropMemory()
        GalleryResultIndex.root(app).deleteRecursively()
        gallery = File(app.filesDir, "import-gallery").apply { deleteRecursively(); mkdirs() }
    }
    private fun png(file: File, width: Int = 8, height: Int = 6): UniFile {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return checkNotNull(UniFile.fromFile(file))
    }
    private fun folder() = checkNotNull(UniFile.fromFile(gallery))
    private fun index(local: Boolean = true) = GalleryResultIndex.open(app, identity, 71, if (local) folder() else null, false)
    private suspend fun import(provider: Provider, records: List<GalleryProcessingProgress.SavedResult> = emptyList(), local: Boolean = true) =
        GalleryResultImporter.import(app, provider, 71, identity, if (local) folder() else null, records)
    private fun record(kind: GalleryResultIndex.Kind, name: String, file: UniFile) = GalleryProcessingProgress.SavedResult(
        if (kind == tl) GalleryProcessingProgress.Kind.TRANSLATION else GalleryProcessingProgress.Kind.SUPER_RESOLUTION, name, file.uri)

    @Test fun emptyImportCreatesNoIndexAndNeverExaminesOriginals() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        val report = import(provider)
        assertEquals(0, report.translated + report.enhanced + report.failed)
        assertEquals(0, provider.metadataReads)
        assertFalse(File(gallery, GalleryResultIndex.MANIFEST).exists())
        assertFalse(GalleryResultIndex.root(app).exists())
    }
    @Test fun localNamedImagesImportBothKindsAsDoneWithoutChangingImageBytes() = runBlocking {
        val original = png(File(gallery, "00000001.png"))
        val provider = Provider(listOf("00000001"), mapOf("00000001" to original))
        val overlay = png(File(gallery, "_translated/00000001_tl.png"))
        val enhanced = png(File(gallery, "_suepr_resolution/00000001_sr.png"), 32, 24)
        val bytes = overlay.openInputStream().use { it.readBytes() }
        val report = import(provider)
        assertEquals(1, report.translated); assertEquals(1, report.enhanced); assertEquals(0, report.failed)
        val source = GalleryResultIndex.source(provider, 0, 71)
        assertEquals(overlay.uri, index().find(source, tl)!!.file!!.uri)
        assertEquals(enhanced.uri, index().find(source, sr)!!.file!!.uri)
        assertEquals(4, index().find(source, sr)!!.scale)
        assertArrayEquals(bytes, overlay.openInputStream().use { it.readBytes() })
        val page = JSONObject(File(gallery, GalleryResultIndex.MANIFEST).readText()).getJSONObject("pages").getJSONObject(source.name)
        assertEquals("done", page.getJSONObject("tl").getString("uri"))
        assertEquals("done", page.getJSONObject("sr").getString("uri"))
        assertEquals(0, provider.sourceReads)
    }
    @Test fun databaseMappingAndPageMetadataRestoreHashedCachesWithoutAnOriginal() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        val overlay = png(File(app.cacheDir, "translated-pages/g71_${"a".repeat(64)}_tl.png"))
        val tiles = File(app.cacheDir, "super-resolution-pages/${GalleryResultIndex.digest(identity)}/old.tiles")
        val enhanced = png(File(tiles, "result.png"), 32, 24)
        File(tiles, "page.json").writeText(JSONObject().put("version", 3).put("complete", true)
            .put("width", 8).put("height", 6).put("scale", 4).put("result", enhanced.uri.toString())
            .put("resultSize", enhanced.length()).toString())
        GalleryProcessingProgress.record(app, 71, GalleryProcessingProgress.Kind.TRANSLATION, "00000001", overlay).join()
        GalleryProcessingProgress.record(app, 71, GalleryProcessingProgress.Kind.SUPER_RESOLUTION, "00000001", enhanced).join()
        val records = GalleryProcessingProgress.savedResults(app, 71)
        assertEquals(2, records.size)
        val report = import(provider, records, local = false)
        assertEquals(1, report.translated); assertEquals(1, report.enhanced); assertEquals(0, report.failed)
        assertFalse(overlay.exists()); assertFalse(enhanced.exists())
        val hit = index(false).find(GalleryResultIndex.Source("00000001"), sr)!!
        assertEquals(4, hit.scale); assertFalse(hit.persistent)
        assertFalse(File(gallery, GalleryResultIndex.MANIFEST).exists())
        assertEquals(0, provider.sourceReads)
        val updated = GalleryProcessingProgress.savedResults(app, 71)
        assertEquals(2, updated.size)
        assertTrue(updated.all { it.uri != overlay.uri && it.uri != enhanced.uri })
    }
    @Test fun localRelativeNamesMoveToDeterministicNamesAndRepeatedImportIsIdempotent() = runBlocking {
        val provider = Provider(listOf("chapter/p.jpg"), emptyMap())
        val old = png(File(gallery, "_translated/chapter/p_tl.png"))
        val bytes = old.openInputStream().use { it.readBytes() }
        assertEquals(1, import(provider).translated)
        assertFalse(old.exists())
        val result = index().find(GalleryResultIndex.Source("chapter/p.jpg"), tl)!!.file!!
        assertArrayEquals(bytes, result.openInputStream().use { it.readBytes() })
        val again = import(provider)
        assertEquals(0, again.translated); assertEquals(0, again.failed)
        assertEquals(result.uri, index().find(GalleryResultIndex.Source("chapter/p.jpg"), tl)!!.file!!.uri)
    }
    @Test fun ambiguousBasenamesAreNotAssignedToEitherOriginal() = runBlocking {
        val provider = Provider(listOf("chapter/p.jpg", "chapter/p.png"), emptyMap())
        val image = png(File(gallery, "_translated/chapter/p_tl.png"))
        val report = import(provider)
        assertEquals(0, report.translated); assertEquals(1, report.ignored)
        assertTrue(image.exists()); assertFalse(File(gallery, GalleryResultIndex.MANIFEST).exists())
    }
    @Test fun validCurrentResultsAreNotOverwrittenByOldCacheRecords() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        val current = png(File(gallery, "_translated/00000001_tl.png"), 16, 12)
        index().importComplete(GalleryResultIndex.Source("00000001"), tl, current, 1, true)
        val old = png(File(app.cacheDir, "old/g71_hash_tl.png"))
        val report = import(provider, listOf(record(tl, "00000001", old)))
        assertEquals(0, report.translated)
        assertTrue(old.exists()); assertEquals(16, index().find(GalleryResultIndex.Source("00000001"), tl)!!.width)
    }
    @Test fun importPreservesOldFilesReferencedByAnotherGallery() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        val old = png(File(app.cacheDir, "old/shared_tl.png"))
        GalleryProcessingProgress.record(app, 71, GalleryProcessingProgress.Kind.TRANSLATION, "00000001", old).join()
        GalleryProcessingProgress.record(app, 72, GalleryProcessingProgress.Kind.TRANSLATION, "00000002", old).join()
        assertEquals(1, import(provider, GalleryProcessingProgress.savedResults(app, 71), local = false).translated)
        assertTrue(old.isFile)
        assertEquals(old.uri, GalleryProcessingProgress.savedResults(app, 72).single().uri)
        assertNotEquals(old.uri, GalleryProcessingProgress.savedResults(app, 71).single().uri)
    }
    @Test fun unavailableDatabaseUriDoesNotBlockImportingValidLocalResults() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        png(File(gallery, "_translated/00000001_tl.png"))
        val unavailable = android.net.Uri.parse("content://expired/document/result")
        val handler = com.hippo.unifile.UriHandler { _, uri ->
            if (uri == unavailable) throw SecurityException("Expired permission") else null
        }
        UniFile.addUriHandler(handler)
        try {
            val report = import(provider, listOf(GalleryProcessingProgress.SavedResult(
                GalleryProcessingProgress.Kind.TRANSLATION, "00000001", unavailable)))
            assertEquals(1, report.translated); assertEquals(1, report.failed)
        } finally { UniFile.removeUriHandler(handler) }
    }
    @Test fun partialSkippedOldFullPageAndUnmappedHashFilesAreNotImported() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        png(File(gallery, "_translated/00000001_tl.partial"))
        File(gallery, "_translated/00000001_tl.skip").writeText("skipped")
        val full = png(File(app.cacheDir, "old/fullhash.png"))
        val unmapped = png(File(app.cacheDir, "translated-pages/unknown_tl.png"))
        val report = import(provider, listOf(record(tl, "00000001", full)))
        assertEquals(0, report.translated + report.enhanced + report.failed)
        assertTrue(full.exists()); assertTrue(unmapped.exists())
        assertFalse(File(gallery, GalleryResultIndex.MANIFEST).exists()); assertFalse(GalleryResultIndex.root(app).exists())
    }
    @Test fun truncatedImagesAndUnknownSuperResolutionScalesLeaveNoManifest() = runBlocking {
        val provider = Provider(listOf("00000001"), emptyMap())
        val truncated = File(gallery, "_translated/00000001_tl.png")
        png(truncated)
        truncated.writeBytes(truncated.readBytes().dropLast(12).toByteArray())
        val enhanced = png(File(gallery, "_suepr_resolution/00000001_sr.png"), 16, 12)
        val report = import(provider)
        assertEquals(1, report.failed); assertEquals(1, report.ignored)
        assertTrue(truncated.isFile); assertTrue(enhanced.isFile)
        assertFalse(File(gallery, GalleryResultIndex.MANIFEST).exists()); assertFalse(GalleryResultIndex.root(app).exists())
    }

    private class Provider(private val names: List<String>, private val originals: Map<String, UniFile>) : GalleryProvider2() {
        var sourceReads = 0
        var metadataReads = 0
        override fun size() = names.size
        override fun getError(): String? = null
        override fun getImageFilename(index: Int) = names[index].substringBeforeLast('.')
        override fun getResultPageName(index: Int) = names[index]
        override fun getPreviewFile(index: Int): UniFile? { metadataReads++; return originals[names[index]] }
        override fun onRequest(index: Int) = Unit
        override fun onForceRequest(index: Int) = Unit
        override fun onCancelRequest(index: Int) = Unit
        override fun save(index: Int, file: UniFile): Boolean { sourceReads++; error("Import must never acquire/hash original pixels") }
        override fun save(index: Int, dir: UniFile, filename: String): UniFile? = null
        override fun saveWithResult(index: Int, dir: UniFile, filename: String): SaveResult? = null
    }
}
