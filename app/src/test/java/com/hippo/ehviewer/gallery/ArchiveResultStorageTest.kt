package com.hippo.ehviewer.gallery

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.hippo.ehviewer.translation.TranslationOptions
import com.hippo.ehviewer.translation.TranslationResultStore
import com.hippo.ehviewer.translation.TranslationStorage
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveResultStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        GalleryResultIndex.dropMemory()
        app.getSharedPreferences("archive-result-parents", 0).edit().clear().commit()
    }
    private fun provider(archive: File) = ArchiveGalleryProvider(app, Uri.fromFile(archive)) {
        object : ArchivePageSource() {
            override fun names() = arrayOf("Chapter 1/001.jpg", "Chapter 2/001.jpg")
            override fun extract(page: Int, output: OutputStream) { output.write(byteArrayOf(1, 2, 3)) }
            override fun close() {}
        }
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    @Test fun readingNeverCreatesAnEmptyResultFolderAndDifferentExtensionsAreIsolated() {
        val folder = temp.newFolder("books")
        val zip = File(folder, "target.zip").apply { writeText("archive") }
        val rar = File(folder, "target.rar").apply { writeText("archive") }
        assertNull(ArchiveResultStorage.directory(app, Uri.fromFile(zip), false))
        assertEquals(listOf("target.rar", "target.zip"), folder.list()!!.sorted())
        val zipResults = checkNotNull(TranslationStorage.resultDirectory(app, null, provider(zip), true))
        val rarResults = checkNotNull(TranslationStorage.resultDirectory(app, null, provider(rar), true))
        assertEquals("target.zip.results", zipResults.name)
        assertEquals("target.rar.results", rarResults.name)
        assertNotEquals(zipResults.uri, rarResults.uri)
    }
    @Test fun translationAndSrResultsSurviveMovingThePairAndChangingArchiveMetadata() {
        val original = temp.newFolder("original")
        val archive = File(original, "target.cbz").apply { writeText("original archive") }
        val provider = provider(archive)
        assertTrue(provider.save(0, checkNotNull(UniFile.fromFile(temp.newFile("source.img")))))
        val directory = checkNotNull(TranslationStorage.resultDirectory(app, null, provider, true))
        val identity = GalleryResultIndex.identity(provider, null)
        val source = GalleryResultIndex.source(provider, 0, null)
        val index = GalleryResultIndex.open(app, identity, null, directory, true)
        val store = TranslationResultStore(File(temp.root, "cache"), directory,
            TranslationOptions(persistDownloaded = true), null, true, source.name,
            resultIndex = index, indexedSource = source)
        assertTrue(store.persists) // External archives need neither a download record nor a gallery ID.
        store.writeImage("page", "png") { it.write(png()) }
        index.write(source, GalleryResultIndex.Kind.SR, "png", 2) { it.write(png()) }
        val manifest = File(directory.uri.path!!, GalleryResultIndex.MANIFEST).readText()
        assertFalse(manifest.contains(original.absolutePath))
        assertFalse(manifest.contains("file://"))
        assertFalse(manifest.contains("content://"))
        val json = JSONObject(manifest)
        assertEquals("archive", json.getString("gallery"))
        assertEquals(listOf("Chapter 1/001.jpg"), json.getJSONObject("pages").keys().asSequence().toList())
        assertEquals("", source.stamp)
        provider.stop()
        val moved = File(temp.root, "moved")
        assertTrue(original.renameTo(moved))
        val movedArchive = File(moved, "target.cbz").apply { appendText("different size and time") }
        val reopened = provider(movedArchive)
        assertTrue(reopened.save(0, checkNotNull(UniFile.fromFile(temp.newFile("new-source.img")))))
        GalleryResultIndex.dropMemory()
        val newDirectory = checkNotNull(TranslationStorage.resultDirectory(app, null, reopened, false))
        val newIndex = GalleryResultIndex.open(app, GalleryResultIndex.identity(reopened, null), null, newDirectory, false)
        val newSource = GalleryResultIndex.source(reopened, 0, null)
        assertEquals(source, newSource)
        assertNotNull(newIndex.find(newSource, GalleryResultIndex.Kind.TL)?.file)
        assertEquals(2, newIndex.find(newSource, GalleryResultIndex.Kind.SR)?.scale)
        assertNull(newIndex.find(GalleryResultIndex.Source("Chapter 2/001.jpg"), GalleryResultIndex.Kind.TL))
        reopened.stop()
    }
    @Test fun missingParentAccessDoesNotSilentlyDowngradeAPersistentWrite() {
        val provider = ArchiveGalleryProvider(app, Uri.parse("content://missing/document/archive"))
        assertNull(TranslationStorage.resultDirectory(app, null, provider, false))
        try {
            TranslationStorage.resultDirectory(app, null, provider, true)
            fail("Persistent writes must require the archive parent directory")
        } catch (_: IllegalStateException) { }
    }
    @Test fun validatesOpaqueDocumentIdsAndRejectsSameNameInWrongParent() {
        val authority = "com.hippo.archive.test.documents"
        val documents = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val parent = DocumentsContract.getDocumentId(uri)
                val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
                return MatrixCursor(columns).apply {
                    val id = if (uri.lastPathSegment != "children") parent
                        else if (parent == "right") "opaque-archive" else "same-name-different-document"
                    addRow(columns.map<String, Any?> {
                        when (it) {
                            Document.COLUMN_DOCUMENT_ID -> id
                            Document.COLUMN_DISPLAY_NAME -> if (id.endsWith("archive") || id.startsWith("same-name")) "target.zip" else id
                            Document.COLUMN_MIME_TYPE -> if (id in listOf("right", "wrong")) Document.MIME_TYPE_DIR else "application/zip"
                            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE
                            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED -> 0L
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
            override fun getType(uri: Uri) = Document.MIME_TYPE_DIR
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        documents.attachInfo(app, ProviderInfo().apply { this.authority = authority; grantUriPermissions = true; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, documents)
        val archive = DocumentsContract.buildDocumentUri(authority, "opaque-archive")
        val right = UniFile.fromTreeUri(app, DocumentsContract.buildTreeDocumentUri(authority, "right"))!!
        val wrong = UniFile.fromTreeUri(app, DocumentsContract.buildTreeDocumentUri(authority, "wrong"))!!
        assertTrue(ArchiveResultStorage.contains(app, right, archive))
        assertFalse(ArchiveResultStorage.contains(app, wrong, archive))
        assertFalse(ArchiveResultStorage.bind(app, archive, wrong.uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertFalse(ArchiveResultStorage.bind(app, archive, right.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertTrue(ArchiveResultStorage.bind(app, archive, right.uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertTrue(ArchiveResultStorage.canWrite(app, archive))
        assertEquals(right.uri, ArchiveResultStorage.parent(app, archive)?.uri)
    }
}
