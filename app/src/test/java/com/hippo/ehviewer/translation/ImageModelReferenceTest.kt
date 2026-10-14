package com.hippo.ehviewer.translation

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONArray
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLinux

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE, shadows = [ImageModelReferenceTest.ReferenceOs::class])
class ImageModelReferenceTest {
    /** Simulate file-backed SAF descriptors; actual native loads/mapping need device verification. */
    @Implements(Os::class)
    class ReferenceOs {
        companion object {
            val sources = mutableMapOf<FileDescriptor, File>()
            val streams = mutableMapOf<FileDescriptor, FileInputStream>()
            var nonSeekable = false
            var mappingSupported = true
            @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat = ShadowLinux().stat(sources.getValue(fd).path)
            @JvmStatic @Implementation fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long {
                if (nonSeekable) throw ErrnoException("lseek", OsConstants.ESPIPE)
                require(whence == OsConstants.SEEK_SET)
                streams.getValue(fd).channel.position(offset)
                return offset
            }
            @JvmStatic @Implementation fun read(fd: FileDescriptor, data: ByteArray, offset: Int, count: Int): Int =
                streams.getValue(fd).read(data, offset, count).coerceAtLeast(0)
            @JvmStatic @Implementation fun mmap(address: Long, count: Long, prot: Int, flags: Int, fd: FileDescriptor, offset: Long): Long {
                if (!mappingSupported) throw ErrnoException("mmap", OsConstants.ENODEV)
                return 1L
            }
            @JvmStatic @Implementation fun munmap(address: Long, count: Long) { }
        }
    }

    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private val payloads = linkedMapOf(
        "dbnet_detect.ncnn.param" to "detector graph", "dbnet_detect.ncnn.bin" to "detector weights",
        "ocr_48px_ctc.ncnn.param" to "ocr graph", "ocr_48px_ctc_mixed.ncnn.param" to "mixed graph",
        "ocr_48px_ctc.ncnn.bin" to "ocr weights", "mit_aot_fixed512.ncnn.param" to "inpaint graph",
        "mit_aot_fixed512.ncnn.bin" to "inpaint weights")
    private lateinit var sourceFiles: Map<String, File>
    private fun uri(name: String) = Uri.parse("content://image.models/$name")
    private val uris get() = payloads.keys.map(::uri)
    private fun manifest(payloads: Map<String, String> = this.payloads) = JSONObject().put("models", JSONArray().apply {
        payloads.forEach { (name, content) -> put(JSONObject().put("name", name).put("size", content.toByteArray().size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) })) }
    }).toString()
    private fun store(directory: String = "reference-models", readOnly: Boolean = false) =
        TranslationModels(context, manifest(), directory, readOnly)

    @Before fun setUp() {
        ReferenceOs.streams.values.forEach { runCatching { it.close() } }
        ReferenceOs.streams.clear(); ReferenceOs.sources.clear()
        ReferenceOs.nonSeekable = false; ReferenceOs.mappingSupported = true
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = File(temp.root, "private")
        }
        sourceFiles = (payloads + ("arbitrary.onnx" to "official recognition weights")).mapValues { (name, data) ->
            temp.newFile(name).apply { writeText(data) }
        }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(sourceFiles.getValue(uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY).also {
                ReferenceOs.sources[it.fileDescriptor] = sourceFiles.getValue(uri.lastPathSegment!!)
                ReferenceOs.streams[it.fileDescriptor] = FileInputStream(it.fileDescriptor)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "image.models"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("image.models", provider)
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Invalid external model accepted", failed)
    }
    private fun assertSourcesUnchanged() = payloads.forEach { (name, data) -> assertEquals(data, sourceFiles.getValue(name).readText()) }
    private fun hasGrant(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    @Test fun wholeSetPersistsReferencesAndExplicitPairsWithoutPrivateCopies() = runBlocking<Unit> {
        val store = store()
        store.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        assertTrue(store.ready()); assertTrue(store.hasReferences()); assertEquals(0L, store.storedBytes())
        assertTrue(uris.all(::hasGrant)); assertSourcesUnchanged()
        val restored = store()
        assertTrue(restored.ready())
        val set = restored.verified()
        val ocr = set.imageFiles(set.ocr)
        assertNotNull(ocr.mixedParam)
        for ((name, model) in set.sources) model.read { path, descriptor ->
            assertNull(path)
            val data = ByteArray(payloads.getValue(name).length)
            assertEquals(data.size, Os.read(descriptor!!.fileDescriptor, data, 0, data.size))
            assertEquals(payloads.getValue(name), data.toString(Charsets.UTF_8))
        }
    }

    @Test fun copyReferenceCopyTransitionsPreserveSourcesAndReleaseGrants() = runBlocking<Unit> {
        val store = store()
        store.import(uris) { _, _, _ -> }
        assertEquals(store.totalBytes, store.storedBytes())
        store.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        assertEquals(0L, store.storedBytes())
        store.import(uris) { _, _, _ -> }
        assertFalse(store.hasReferences()); assertEquals(store.totalBytes, store.storedBytes())
        assertTrue(uris.none(::hasGrant)); assertTrue(store.ready()); assertSourcesUnchanged()
        store.verified()
    }

    @Test fun removalPreservesSharedGrantUntilLastStoreIsRemoved() = runBlocking<Unit> {
        val first = store(); val second = store("second-models")
        first.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        second.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        first.delete()
        assertFalse(first.ready()); assertTrue(second.ready()); assertTrue(uris.all(::hasGrant))
        second.delete()
        assertTrue(uris.none(::hasGrant)); assertSourcesUnchanged()
    }

    @Test fun corruptAndIncompleteReferencesDoNotReplaceExistingCopy() = runBlocking<Unit> {
        val store = store()
        store.import(uris) { _, _, _ -> }
        rejected { store.import(uris.dropLast(1), TranslationModels.ImportMode.REFERENCE) { _, _, _ -> } }
        sourceFiles.getValue(payloads.keys.last()).writeText("x".repeat(payloads.values.last().length))
        rejected { store.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> } }
        assertFalse(store.hasReferences()); assertEquals(store.totalBytes, store.storedBytes())
        assertTrue(uris.none(::hasGrant)); store.verified()
    }

    @Test fun cancelledReferenceImportKeepsPrivateCopyAndReleasesNewGrants() = runBlocking<Unit> {
        val store = store()
        store.import(uris) { _, _, _ -> }
        val job = launch { store.import(uris, TranslationModels.ImportMode.REFERENCE) { name, copied, _ ->
            if (name == payloads.keys.elementAt(2) && copied > 0) throw CancellationException("cancel")
        } }
        job.join()
        assertTrue(job.isCancelled); assertFalse(store.hasReferences())
        assertEquals(store.totalBytes, store.storedBytes()); assertTrue(uris.none(::hasGrant)); assertSourcesUnchanged()
    }

    @Test fun missingOrModifiedSourcesRemainRemovableAndCannotBeLoaded() = runBlocking<Unit> {
        val store = store()
        store.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        val file = sourceFiles.values.first()
        val modified = file.lastModified()
        file.writeText("x".repeat(file.length().toInt())); file.setLastModified(modified + 3000)
        assertFalse(store.ready()); rejected { store.verified() }
        assertTrue(store.hasReferences())
        file.delete()
        assertFalse(store.ready()); store.delete(); assertFalse(store.hasReferences())
    }

    @Test fun sameSizeAndTimestampTamperingStillFailsFullChecksumOnSessionCreation() = runBlocking<Unit> {
        val store = store()
        store.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        val file = sourceFiles.values.first(); val modified = file.lastModified()
        file.writeText("x".repeat(file.length().toInt())); file.setLastModified(modified)
        rejected { store.verified() }
    }

    @Test fun unsupportedDescriptorAndExecutableComponentsCannotPublishReferences() = runBlocking<Unit> {
        val store = store()
        ReferenceOs.nonSeekable = true
        rejected { store.import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> } }
        assertFalse(store.hasReferences()); assertTrue(uris.none(::hasGrant))
        ReferenceOs.nonSeekable = false
        rejected { store(readOnly = true).import(uris, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> } }
        assertFalse(store.hasReferences()); assertTrue(uris.none(::hasGrant))
    }

    @Test fun singleOnnxReferenceIgnoresProviderFilenameAndChecksMapping() = runBlocking<Unit> {
        val model = TranslationModels(context, manifest(mapOf("ppocr-en.onnx" to "official recognition weights")), "reference-pp-en")
        val uri = uri("arbitrary.onnx")
        ReferenceOs.mappingSupported = false
        rejected { model.importSingle(uri, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> } }
        assertFalse(model.hasReferences()); assertFalse(hasGrant(uri))
        ReferenceOs.mappingSupported = true
        model.importSingle(uri, TranslationModels.ImportMode.REFERENCE) { _, _, _ -> }
        assertTrue(model.ready()); assertEquals(0L, model.storedBytes())
        model.verifiedSources().getValue("ppocr-en.onnx").read { path, descriptor -> assertNull(path); assertNotNull(descriptor) }
        model.delete(); assertFalse(hasGrant(uri)); assertTrue(sourceFiles.getValue("arbitrary.onnx").exists())
    }
}
