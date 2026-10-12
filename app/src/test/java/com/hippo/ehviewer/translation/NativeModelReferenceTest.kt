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
import android.system.StructStat
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLinux
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE, shadows = [NativeModelReferenceTest.ReferenceOs::class])
class NativeModelReferenceTest {
    /** Robolectric returns an empty fstat and lseek does not reposition the underlying file.
     * Supply file-backed descriptor operations here; real OS/mmap behavior is covered on device. */
    @Implements(android.system.Os::class)
    class ReferenceOs {
        companion object {
            val sources = mutableMapOf<FileDescriptor, File>()
            val streams = mutableMapOf<FileDescriptor, FileInputStream>()
            @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat =
                ShadowLinux().stat(sources.getValue(fd).absolutePath)
            @JvmStatic @Implementation fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long {
                require(whence == android.system.OsConstants.SEEK_SET)
                streams.getValue(fd).channel.position(offset)
                return offset
            }
            @JvmStatic @Implementation fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, count: Int): Int =
                streams.getValue(fd).read(buffer, offset, count).coerceAtLeast(0)
        }
    }

    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var source: File
    private lateinit var store: NativeModelStore
    private val uri = Uri.parse("content://gguf.test/model")
    private val payload = "GGUF${"weights".repeat(20)}".toByteArray()
    private val id get() = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        ReferenceOs.sources.clear()
        ReferenceOs.streams.values.forEach { runCatching { it.close() } }
        ReferenceOs.streams.clear()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = File(temporary.root, "private")
        }
        source = temporary.newFile("source.gguf").apply { writeBytes(payload) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(source.name)) }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also {
                ReferenceOs.sources[it.fileDescriptor] = source
                ReferenceOs.streams[it.fileDescriptor] = FileInputStream(it.fileDescriptor)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "gguf.test"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("gguf.test", provider)
        store = NativeModelStore(context, validateExternalModel = { }) { }
    }

    @Test fun referencePersistsPermissionAndInventoryWithoutCreatingACopy() = runBlocking {
        store.import(uri, NativeModelStore.ImportMode.REFERENCE)
        val selected = TranslationSettings(context).read()
        assertEquals(id, selected.nativeModelId)
        assertTrue(store.ready(selected))
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        val installed = NativeModelStore(context).installed().single()
        assertTrue(installed.external)
        assertEquals(payload.size.toLong(), installed.size)
        assertEquals(0L, installed.privateBytes)
        assertFalse(store.file(id).exists())
        assertArrayEquals(payload, source.readBytes())
        store.select(id)
    }

    @Test fun switchingBetweenCopyAndReferenceFreesOnlyThePrivateCopy() = runBlocking {
        store.import(uri)
        assertTrue(store.file(id).exists())
        store.import(uri, NativeModelStore.ImportMode.REFERENCE)
        assertFalse(store.file(id).exists())
        assertTrue(store.installed().single().external)
        store.import(uri)
        assertTrue(store.file(id).exists())
        assertFalse(store.installed().single().external)
        assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        assertTrue(store.ready(TranslationSettings(context).read()))
        assertArrayEquals(payload, source.readBytes())
    }

    @Test fun removingReferenceReleasesPermissionAndPreservesSource() = runBlocking {
        store.import(uri, NativeModelStore.ImportMode.REFERENCE)
        store.delete(id)
        assertEquals("", TranslationSettings(context).read().nativeModelId)
        assertTrue(store.installed().isEmpty())
        assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        assertArrayEquals(payload, source.readBytes())
    }

    @Test fun changedOrMissingSourceCannotBeSelectedOrUsed() = runBlocking {
        store.import(uri, NativeModelStore.ImportMode.REFERENCE)
        val selected = TranslationSettings(context).read()
        source.appendText("changed")
        assertFalse(store.ready(selected))
        try { store.select(id); fail("Changed model accepted") } catch (_: IllegalStateException) { }
        assertEquals(selected, TranslationSettings(context).read())
        assertEquals(id, store.installed().single().id) // Keep the broken reference removable.
        source.delete()
        assertFalse(store.ready(selected))
        store.delete(id)
        assertTrue(store.installed().isEmpty())
    }

    @Test fun sameSizeWriteWithNewModificationTimeInvalidatesReference() = runBlocking {
        store.import(uri, NativeModelStore.ImportMode.REFERENCE)
        val selected = TranslationSettings(context).read()
        val before = source.lastModified()
        source.writeBytes(payload.copyOf().apply { this[lastIndex] = 0 })
        assertTrue(source.setLastModified(before + 3000))
        assertFalse(store.ready(selected))
        try { store.select(id); fail("Modified model accepted") } catch (_: IllegalStateException) { }
        assertEquals(selected, TranslationSettings(context).read())
    }

    @Test fun invalidHeaderNeverPersistsAReferenceOrChangesSelection() = runBlocking {
        source.writeBytes(payload.copyOf().apply { this[0] = 0 })
        try { store.import(uri, NativeModelStore.ImportMode.REFERENCE); fail("Invalid GGUF accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.installed().isEmpty())
        assertEquals("", TranslationSettings(context).read().nativeModelId)
        assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
        assertTrue(source.exists())
    }

    @Test fun failedReferenceValidationKeepsPrivateCopyAndReleasesNewGrant() = runBlocking {
        store.import(uri)
        val previous = TranslationSettings(context).read()
        val invalid = NativeModelStore(context, validateExternalModel = { error("Cannot mmap") }) { }
        try { invalid.import(uri, NativeModelStore.ImportMode.REFERENCE); fail("Invalid reference accepted") }
        catch (error: IllegalStateException) { assertEquals("Cannot mmap", error.message) }
        assertEquals(previous, TranslationSettings(context).read())
        assertTrue(store.file(id).exists())
        assertFalse(store.installed().single().external)
        assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
    }

    @Test fun cancelledReferenceValidationKeepsPreviousSelectionAndCopy() = runBlocking {
        store.import(uri)
        val previous = TranslationSettings(context).read()
        lateinit var importJob: Job
        val cancelling = NativeModelStore(context, validateExternalModel = { importJob.cancel() }) { }
        importJob = launch(start = CoroutineStart.LAZY) { cancelling.import(uri, NativeModelStore.ImportMode.REFERENCE) }
        importJob.start()
        importJob.join()
        assertTrue(importJob.isCancelled)
        assertEquals(previous, TranslationSettings(context).read())
        assertTrue(store.file(id).exists())
        assertFalse(store.installed().single().external)
        assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
    }
}
