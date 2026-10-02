package com.hippo.ehviewer.translation

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class MangaModelManagementTest {
    @get:Rule val temp = TemporaryFolder()
    private val payloads = linkedMapOf("test.param" to "parameters", "test.bin" to "weights")
    private fun models(url: String? = null): TranslationModels {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = temp.root
        }
        val manifest = JSONObject().put("models", JSONArray().apply {
            payloads.forEach { (name, payload) -> put(JSONObject().put("name", name).put("url", url)
                .put("size", payload.length).put("sha256", MessageDigest.getInstance("SHA-256")
                    .digest(payload.toByteArray()).joinToString("") { "%02x".format(it) })) }
        }).toString()
        return TranslationModels(context, manifest)
    }
    private fun sources(): List<File> = payloads.map { (name, payload) -> temp.newFile(name).apply { writeText(payload) } }

    @Test fun importsWholeVerifiedBundleAndDeletesOnlyManagedCopies() = runBlocking {
        val store = models()
        val files = sources()
        val unrelated = File(temp.root, "translation-models/keep.txt").apply { writeText("keep") }
        store.import(files.map(Uri::fromFile)) { _, _, _ -> }
        assertTrue(store.ready())
        assertEquals(store.totalBytes, store.storedBytes())
        store.delete()
        assertFalse(store.ready())
        assertEquals(0L, store.storedBytes())
        assertTrue(files.all { it.exists() })
        assertEquals("keep", unrelated.readText())
    }

    @Test fun corruptOrIncompleteImportNeverReplacesInstalledFiles() = runBlocking {
        val store = models()
        val files = sources()
        store.import(files.map(Uri::fromFile)) { _, _, _ -> }
        files.last().writeText("corrupt")
        for (input in listOf(files, files.take(1), listOf(files.first(), files.first()))) {
            var failed = false
            try { store.import(input.map(Uri::fromFile)) { _, _, _ -> } } catch (_: Exception) { failed = true }
            assertTrue(failed)
            for ((name, payload) in payloads) assertEquals(payload, File(temp.root, "translation-models/$name").readText())
            assertFalse(File(temp.root, "translation-models").listFiles()!!.any { it.extension == "part" })
        }
    }

    @Test fun cancellingDownloadInterruptsAStalledBodyAndRemovesThePartialFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(payloads.values.first()).setBodyDelay(30, TimeUnit.SECONDS))
            val store = models(server.url("/model").toString())
            val bodyStarted = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) { store.download { _, _, _ -> bodyStarted.complete(Unit) } }
            try {
                withTimeout(3000) { bodyStarted.await() }
                // Cancellation must work without separately calling store.cancel().
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertFalse(store.ready())
                assertFalse(File(temp.root, "translation-models").listFiles()!!.any { it.extension == "part" })
            } finally { store.cancel(); job.cancelAndJoin() }
        }
    }
}
