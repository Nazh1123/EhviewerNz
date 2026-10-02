package com.hippo.ehviewer.translation

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dao.DaoMaster
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.gallery.LocalFolderGalleryScanner
import com.hippo.ehviewer.gallery.LocalFolderGallerySource
import com.hippo.unifile.UniFile
import com.hippo.unifile.UriHandler
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import org.greenrobot.greendao.database.StandardDatabase

/** Exercises real TreeDocumentFile streams/renames with opaque document IDs, not filesystem paths. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = Application::class, sdk = [28], manifest = org.robolectric.annotation.Config.NONE)
class TranslationGallerySafTest {
    @Test fun partialCheckpointSurvivesOpaqueSafRenameAndCacheMigration() {
        val key = "g${source.stableGalleryId()}_partial"
        val checkpoint = li.joye.yakuyomi.engine.TranslationResume(listOf("first", "second"), mapOf(0 to "译文"))
        store(false).writePartial(key, checkpoint) { it.write("preview".toByteArray()) }
        val retained = store().retain(key, store(false).existingImage(key)!!)
        assertEquals("content", retained.uri.scheme)
        TranslationCache(cacheDir).clear()
        assertTrue(TranslationResultStore.isPartial(store().existingImage(key)!!))
        assertEquals(checkpoint, store().readResume(key))
        store().writeImage(key) { it.write("complete".toByteArray()) }
        assertEquals(setOf("$key.png"), book.findFile("_translated")!!.listFiles()!!.map { it.name }.toSet())
    }

    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: GalleryDocumentsProvider
    private lateinit var tree: Uri
    private lateinit var book: UniFile
    private lateinit var source: LocalFolderGallerySource
    private val cacheDir get() = File(temp.root, "cache")
    private val legacyDir get() = File(temp.root, "legacy")

    @Before fun setUp() {
        provider = GalleryDocumentsProvider(temp.newFolder("documents"))
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        val root = requireNotNull(UniFile.fromTreeUri(context, tree))
        book = requireNotNull(root.createDirectory("Book A"))
        source = LocalFolderGallerySource.create(tree, "Book A")
    }

    private fun store(enabled: Boolean = true) = TranslationResultStore(cacheDir, book,
        TranslationOptions(persistDownloaded = enabled), source.stableGalleryId(), true, legacyDir)

    @Test fun resolvesImportedGalleryAndWritesReusableFullPngInItsTranslatedDirectory() {
        val info = DownloadInfo(source.stableGalleryId()).apply { archiveUri = source.encode() }
        assertEquals(book.uri, TranslationStorage.galleryDir(context, info)?.uri)
        val key = "g${info.gid}_page"
        val bitmap = Bitmap.createBitmap(9, 13, Bitmap.Config.ARGB_8888)
        val output = try {
            store().writeImage(key) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        assertEquals("content", output.uri.scheme)
        val translated = requireNotNull(book.findFile("_translated"))
        assertEquals(listOf("$key.png"), translated.listFiles()!!.map { it.name })
        assertEquals(output.uri, store(false).existingImage(key)?.uri)
        val decoded = output.openInputStream().use { BitmapFactory.decodeStream(it) }
        assertNotNull(decoded)
        assertEquals(9, decoded!!.width)
        assertEquals(13, decoded.height)
        decoded.recycle()
        TranslationCache(cacheDir).clear()
        assertNotNull(store(false).existingImage(key))
    }

    @Test fun cachedAndLegacyResultsAreMigratedToSafAndSelectedDeletionPreservesOriginals() {
        val gid = source.stableGalleryId()
        val key = "g${gid}_page"
        val original = requireNotNull(book.createFile("01.png"))
        original.openOutputStream().use { it.write("original".toByteArray()) }
        store(false).writeImage(key) { it.write("cached translation".toByteArray()) }
        legacyDir.mkdirs()
        File(legacyDir, "g${gid}_empty.skip").writeText("skipped")
        val persistent = store()
        persistent.retain(key, requireNotNull(persistent.existingImage(key)))
        assertTrue(persistent.isSkipped("g${gid}_empty"))
        assertFalse(File(legacyDir, "g${gid}_empty.skip").exists())
        val translated = requireNotNull(book.findFile("_translated"))
        requireNotNull(translated.createFile("notes.txt"))
        requireNotNull(translated.createFile("g10_page.png"))
        assertTrue(TranslationResultStore.deleteGalleries(cacheDir, legacyDir, setOf(gid), mapOf(gid to book)))
        assertEquals(setOf("notes.txt", "g10_page.png"), translated.listFiles()!!.map { it.name }.toSet())
        assertEquals("original", original.openInputStream().bufferedReader().use { it.readText() })
    }

    @Test fun rescanningAnImportedLibrarySkipsTranslatedSubdirectoriesAtEveryDepth() {
        requireNotNull(book.createFile("01.png"))
        val chapter = requireNotNull(book.createDirectory("Chapter"))
        requireNotNull(chapter.createFile("02.jpg"))
        for (directory in listOf(book, chapter)) {
            val translated = requireNotNull(directory.createDirectory("_translated"))
            requireNotNull(translated.createFile("g1_result.png"))
        }
        val scan = LocalFolderGalleryScanner.scan(context, LocalFolderGallerySource.create(tree, ""))
        assertEquals(listOf("Book A/01.png", "Book A/Chapter/02.jpg"), scan.images.map { it.relativePath })
        assertEquals(3, scan.directoryCount)
    }

    @Test fun failedSafWriteLeavesNoPublishedOrPartialImage() {
        val key = "g${source.stableGalleryId()}_page"
        assertThrows(IllegalStateException::class.java) {
            store().writeImage(key) { it.write(1); error("cancelled") }
        }
        assertNull(store(false).existingImage(key))
        assertTrue(requireNotNull(book.findFile("_translated")).listFiles()!!.isEmpty())
    }

    @Test fun downloadedGalleryUsesRecordedFolderRatherThanCurrentTitle() {
        val root = temp.newFolder("downloads")
        val gallery = File(root, "Recorded folder").apply { mkdirs() }
        withDownloadRoot(requireNotNull(UniFile.fromFile(root))) {
            val info = DownloadInfo(71).apply { title = "Changed title"; state = DownloadInfo.STATE_FINISH }
            EhDB.putDownloadDirname(info.gid, gallery.name)
            val directory = requireNotNull(TranslationStorage.galleryDir(context, info))
            val results = TranslationResultStore(cacheDir, directory,
                TranslationOptions(persistDownloaded = true), info.gid, true)
            val output = results.writeImage("g71_page") { it.write(1) }
            assertEquals(File(gallery, "_translated/g71_page.png").absolutePath, output.uri.path)
            assertEquals(listOf("Recorded folder"), root.list()!!.toList())
        }
    }

    @Test fun downloadedSafGalleryResolvesOpaqueIdFromItsRecordedFolderName() {
        val root = requireNotNull(UniFile.fromTreeUri(context, tree))
        withDownloadRoot(root) {
            val info = DownloadInfo(71).apply { state = DownloadInfo.STATE_FINISH }
            EhDB.putDownloadDirname(info.gid, "Book A")
            assertEquals(book.uri, TranslationStorage.galleryDir(context, info)?.uri)
        }
    }

    private fun withDownloadRoot(root: UniFile, test: () -> Unit) {
        val database = SQLiteDatabase.create(null)
        val oldContext = ReflectionHelpers.getStaticField<Context?>(Settings::class.java, "sContext")
        val oldPrefs = ReflectionHelpers.getStaticField<android.content.SharedPreferences?>(Settings::class.java, "sSettingsPre")
        val oldDao = ReflectionHelpers.getStaticField<com.hippo.ehviewer.dao.DaoSession?>(EhDB::class.java, "sDaoSession")
        val handler = UriHandler { ctx, uri ->
            if (uri.authority == AUTHORITY && UniFile.isTreeUri(uri)) UniFile.fromTreeUri(ctx, uri) else null
        }
        UniFile.addUriHandler(handler)
        try {
            DaoMaster.createAllTables(StandardDatabase(database), false)
            ReflectionHelpers.setStaticField(EhDB::class.java, "sDaoSession", DaoMaster(database).newSession())
            val prefs = context.getSharedPreferences("gallery-storage-test", Context.MODE_PRIVATE)
            prefs.edit().clear()
                .putString(Settings.KEY_DOWNLOAD_SAVE_SCHEME, root.uri.scheme)
                .putString(Settings.KEY_DOWNLOAD_SAVE_AUTHORITY, root.uri.encodedAuthority)
                .putString(Settings.KEY_DOWNLOAD_SAVE_PATH, root.uri.encodedPath).commit()
            ReflectionHelpers.setStaticField(Settings::class.java, "sContext", context)
            ReflectionHelpers.setStaticField(Settings::class.java, "sSettingsPre", prefs)
            test()
        } finally {
            ReflectionHelpers.setStaticField(EhDB::class.java, "sDaoSession", oldDao)
            ReflectionHelpers.setStaticField(Settings::class.java, "sContext", oldContext)
            ReflectionHelpers.setStaticField(Settings::class.java, "sSettingsPre", oldPrefs)
            UniFile.removeUriHandler(handler)
            database.close()
        }
    }

    companion object { private const val AUTHORITY = "com.hippo.translation.test.documents" }

    // ContentProvider implements the DocumentsContract protocol directly because Robolectric's
    // legacy query dispatch cannot call DocumentsProvider's Android O query overload.
    private class GalleryDocumentsProvider(root: File) : ContentProvider() {
        private data class Entry(val file: File, val parent: String?, val type: String)
        private val entries = linkedMapOf("root" to Entry(root, null, Document.MIME_TYPE_DIR))
        private var nextId = 0
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            return if (uri.lastPathSegment == "children") queryChildDocuments(id, projection)
            else queryDocument(id, projection)
        }

        override fun getType(uri: Uri) = entries[DocumentsContract.getDocumentId(uri)]?.type
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
            throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
            throw UnsupportedOperationException()

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val input = requireNotNull(extras)
            val uri = requireNotNull(input.getParcelable<Uri>("uri"))
            val id = DocumentsContract.getDocumentId(uri)
            val result = when (method) {
                "android:createDocument" -> createDocument(id,
                    requireNotNull(input.getString(Document.COLUMN_MIME_TYPE)),
                    requireNotNull(input.getString(Document.COLUMN_DISPLAY_NAME)))
                "android:renameDocument" -> renameDocument(id, requireNotNull(input.getString(Document.COLUMN_DISPLAY_NAME)))
                "android:deleteDocument" -> { deleteDocument(id); null }
                else -> throw UnsupportedOperationException(method)
            }
            return Bundle().apply {
                if (result != null) putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, result))
            }
        }

        private fun rows(ids: Collection<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            return MatrixCursor(columns).apply {
                for (id in ids) {
                    val entry = entries[id] ?: continue
                    addRow(columns.map<String, Any?> { column ->
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> id
                            Document.COLUMN_DISPLAY_NAME -> entry.file.name
                            Document.COLUMN_MIME_TYPE -> entry.type
                            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                                Document.FLAG_SUPPORTS_RENAME or Document.FLAG_DIR_SUPPORTS_CREATE
                            Document.COLUMN_SIZE -> entry.file.length()
                            Document.COLUMN_LAST_MODIFIED -> entry.file.lastModified()
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
        }

        private fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            rows(listOf(documentId), projection)

        private fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?): Cursor =
            rows(entries.filterValues { it.parent == parentDocumentId }.keys, projection)

        private fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val name = when {
                mimeType == "image/png" && !displayName.endsWith(".png") -> "$displayName.png"
                mimeType == "image/jpeg" && !displayName.endsWith(".jpg") -> "$displayName.jpg"
                mimeType == "text/plain" && !displayName.endsWith(".txt") -> "$displayName.txt"
                else -> displayName
            }
            val file = File(entries.getValue(parentDocumentId).file, name)
            check(if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
            val id = "opaque-${nextId++}"
            entries[id] = Entry(file, parentDocumentId, mimeType)
            return id
        }

        private fun renameDocument(documentId: String, displayName: String): String {
            val entry = entries.getValue(documentId)
            val target = File(entry.file.parentFile, displayName)
            check(entry.file.renameTo(target))
            // A rename may change the document ID; readers must use the returned URI.
            val id = "renamed-${nextId++}"
            entries.remove(documentId)
            entries[id] = entry.copy(file = target)
            return id
        }

        private fun deleteDocument(documentId: String) {
            check(entries.getValue(documentId).file.delete())
            entries.remove(documentId)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(entries.getValue(DocumentsContract.getDocumentId(uri)).file,
                ParcelFileDescriptor.parseMode(mode))
    }
}
