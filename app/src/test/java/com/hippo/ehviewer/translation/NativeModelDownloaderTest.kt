package com.hippo.ehviewer.translation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NativeModelDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: NativeModelStore
    private var validationFailure = false
    private var validatedPath: File? = null
    private val payload = "GGUF${"new-model".repeat(20)}".toByteArray()
    private val oldPayload = "GGUF${"previous-model".repeat(20)}".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = temporary.root
        }
        store = NativeModelStore(context) { file ->
            validatedPath = file
            if (validationFailure) error("Model cannot load")
        }
        val old = store.file(hash(oldPayload)).apply { parentFile!!.mkdirs(); writeBytes(oldPayload) }
        TranslationSettings(context).selectNativeModel(old.nameWithoutExtension, "old.gguf")
    }

    private fun model(server: MockWebServer) = NativeDownloadModel("new.gguf", server.url("/resolve/model.gguf").toString(),
        payload.size.toLong(), hash(payload))

    private fun assertOldPreserved() {
        assertEquals(hash(oldPayload), TranslationSettings(context).read().nativeModelId)
        assertArrayEquals(oldPayload, store.file(hash(oldPayload)).readBytes())
        assertEquals(listOf("${hash(oldPayload)}.gguf"),
            File(temporary.root, "translation-llm").list()!!.toList())
    }

    @Test fun streamsIntoModelDirectoryThenSelectsAndReusesVerifiedFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            var finished = 0L
            NativeModelDownloader(store).use { downloader ->
                downloader.downloadAndImport(model(server), { bytes, _ -> finished = bytes })
                val options = TranslationSettings(context).read()
                assertEquals(hash(payload), options.nativeModelId)
                assertEquals("new.gguf", options.nativeModelName)
                assertArrayEquals(payload, store.file(options.nativeModelId).readBytes())
                assertTrue(store.file(hash(oldPayload)).exists())
                assertEquals(File(temporary.root, "translation-llm"), validatedPath!!.parentFile)
                assertEquals(payload.size.toLong(), finished)
                downloader.downloadAndImport(model(server), { _, _ -> })
                assertEquals(1, server.requestCount)
                assertEquals(setOf(hash(payload), hash(oldPayload)), store.installed().map { it.id }.toSet())
                assertEquals("new.gguf", store.installed().single { it.id == hash(payload) }.name)
                assertEquals("old.gguf", store.installed().single { it.id == hash(oldPayload) }.name)
            }
        }
    }

    @Test fun followsRedirectToArtifactAndUsesSameIntegrityChecks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/artifact")))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            NativeModelDownloader(store).use { it.downloadAndImport(model(server), { _, _ -> }) }
            assertEquals(hash(payload), TranslationSettings(context).read().nativeModelId)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rejectsHashMismatchWithoutReplacingOldModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            NativeModelDownloader(store).use { downloader ->
                try {
                    downloader.downloadAndImport(model(server).copy(sha256 = "a".repeat(64)), { _, _ -> })
                    fail("Bad checksum accepted")
                } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("checksum")) }
            }
            assertNull(validatedPath)
            assertOldPreserved()
        }
    }

    @Test fun rejectsIncompleteOrOversizedResponsesBeforeImport() = runBlocking {
        for (body in listOf(payload.copyOf(payload.size - 1), payload + byteArrayOf(1))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(Buffer().write(body)))
                NativeModelDownloader(store).use { downloader ->
                    try { downloader.downloadAndImport(model(server), { _, _ -> }); fail("Wrong size accepted") }
                    catch (_: IllegalStateException) { }
                }
                assertNull(validatedPath)
                assertOldPreserved()
            }
        }
    }

    @Test fun rejectsHttpErrorAndInvalidGgufWithoutReplacingOldModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val invalid = payload.copyOf().apply { this[0] = 0 }
            server.enqueue(MockResponse().setBody(Buffer().write(invalid)))
            NativeModelDownloader(store).use { downloader ->
                try { downloader.downloadAndImport(model(server), { _, _ -> }); fail("404 accepted") }
                catch (error: IllegalStateException) { assertTrue(error.message!!.contains("404")) }
                assertOldPreserved()
                try {
                    downloader.downloadAndImport(model(server).copy(sha256 = hash(invalid)), { _, _ -> })
                    fail("Invalid GGUF accepted")
                } catch (_: IllegalArgumentException) { }
            }
            assertNull(validatedPath)
            assertOldPreserved()
        }
    }

    @Test fun failedNativeValidationKeepsPreviousSelectionAndCleansStaging() = runBlocking {
        validationFailure = true
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            NativeModelDownloader(store).use { downloader ->
                try { downloader.downloadAndImport(model(server), { _, _ -> }); fail("Failed model selected") }
                catch (error: IllegalStateException) { assertEquals("Model cannot load", error.message) }
            }
            assertOldPreserved()
        }
    }

    @Test fun cancellationInterruptsBodyReadAndRetainsOldModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setBodyDelay(30, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            NativeModelDownloader(store, client).use { downloader ->
                val job = launch(Dispatchers.IO) { downloader.downloadAndImport(model(server), { _, _ -> }) }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertNull(validatedPath)
                assertOldPreserved()
            }
        }
    }

    @Test fun manualImportStillUsesSameValidationAndPublication() = runBlocking {
        val input = temporary.newFile("manual.gguf").apply { writeBytes(payload) }
        store.import(Uri.fromFile(input))
        assertEquals(hash(payload), TranslationSettings(context).read().nativeModelId)
        assertArrayEquals(payload, input.readBytes())
        assertArrayEquals(payload, store.file(hash(payload)).readBytes())
        assertTrue(store.file(hash(oldPayload)).exists())
    }

    @Test fun selectingAndDeletingOneModelPreservesOtherModelsAndImportedSource() = runBlocking {
        val input = temporary.newFile("manual.gguf").apply { writeBytes(payload) }
        store.import(Uri.fromFile(input))
        store.select(hash(oldPayload))
        assertEquals(hash(oldPayload), TranslationSettings(context).read().nativeModelId)
        store.delete(hash(payload))
        assertEquals(hash(oldPayload), TranslationSettings(context).read().nativeModelId)
        assertArrayEquals(payload, input.readBytes())
        assertTrue(store.file(hash(oldPayload)).exists())
        store.delete(hash(oldPayload))
        assertEquals("", TranslationSettings(context).read().nativeModelId)
        assertEquals("", TranslationSettings(context).read().nativeModelName)
        assertTrue(store.installed().isEmpty())
    }

    @Test fun invalidSelectionPreservesCurrentModelAndDeletionRejectsPaths() = runBlocking {
        val input = temporary.newFile("manual.gguf").apply { writeBytes(payload) }
        store.import(Uri.fromFile(input))
        validationFailure = true
        try { store.select(hash(oldPayload)); fail("Invalid model selected") }
        catch (_: IllegalStateException) { }
        assertEquals(hash(payload), TranslationSettings(context).read().nativeModelId)
        assertThrows(IllegalArgumentException::class.java) { store.delete("../manual") }
        assertTrue(input.exists())
        assertEquals(2, store.installed().size)
    }

    @Test fun cancellingDuringSelectionValidationKeepsThePreviousChoice() = runBlocking {
        store.file(hash(payload)).apply { writeBytes(payload) }
        lateinit var selection: Job
        val cancellingStore = NativeModelStore(context) { selection.cancel() }
        selection = launch(start = CoroutineStart.LAZY) { cancellingStore.select(hash(payload)) }
        selection.start()
        selection.join()
        assertTrue(selection.isCancelled)
        assertEquals(hash(oldPayload), TranslationSettings(context).read().nativeModelId)
        assertArrayEquals(payload, store.file(hash(payload)).readBytes())
    }

    @Test fun failedPreferenceWriteNeverDeletesAnAlreadyInstalledModel() = runBlocking {
        val installed = store.file(hash(payload)).apply { writeBytes(payload) }
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                if (name != "translation_model_names") return prefs
                return object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                                apply { editor.putString(key, value) }
                            override fun commit() = false
                        }
                    }
                }
            }
        }
        val failingStore = NativeModelStore(failingContext) { }
        try {
            failingStore.publish(installed, "existing.gguf", hash(payload))
            fail("Preference failure accepted")
        } catch (_: IllegalStateException) { }
        assertArrayEquals(payload, installed.readBytes())
        assertEquals(hash(oldPayload), TranslationSettings(context).read().nativeModelId)
        assertArrayEquals(oldPayload, store.file(hash(oldPayload)).readBytes())
    }
}
