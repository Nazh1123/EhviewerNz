package com.hippo.ehviewer.superresolution

import android.app.Application
import android.content.ContextWrapper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class SuperResolutionDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val param = "scale2.0x_model.param"
    private val bin = "scale2.0x_model.bin"
    private val graph = "test model graph"
    private val weights = "test model weights"
    private val directory get() = File(temporary.root, "super-resolution-models")

    private fun models(server: MockWebServer): SuperResolutionModels {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = temporary.root
        }
        val manifest = JSONObject().put("revision", "test").put("models", JSONArray().apply {
            for ((name, data) in listOf(param to graph, bin to weights)) put(JSONObject()
                .put("name", name).put("size", data.toByteArray().size)
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
                    .joinToString("") { "%02x".format(it) }))
        }).toString()
        return SuperResolutionModels(context, manifest, server.url("/models/").toString())
    }

    @Test fun firstDownloadFetchesBothFilesBeforeMarkingTheModelReady() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(graph))
            server.enqueue(MockResponse().setBody(weights))
            val models = models(server)
            assertFalse(models.store.ready())
            val completed = mutableListOf<String>()
            models.download { name, bytes, total ->
                if (bytes == total) {
                    completed += name
                    // The first response has not yet completed the model set.
                    if (name == param) assertFalse(models.store.ready())
                }
            }
            assertEquals(listOf(param, bin), completed)
            assertEquals("/models/$param", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals("/models/$bin", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals(2, server.requestCount)
            assertTrue(models.store.ready())
            val files = models.files()
            assertEquals(graph, File(checkNotNull(files.param.path)).readText())
            assertEquals(weights, File(checkNotNull(files.weights.path)).readText())
        }
    }

    @Test fun failedSecondResponseCanBeRetriedWithoutLeavingPartialWeightFiles() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(graph))
            server.enqueue(MockResponse().setResponseCode(503))
            val models = models(server)
            val failure = runCatching { models.download { _, _, _ -> } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("503"))
            assertEquals(graph, File(directory, param).readText())
            assertFalse(models.store.ready())
            assertFalse(File(directory, bin).exists())
            server.enqueue(MockResponse().setBody(graph))
            server.enqueue(MockResponse().setBody(weights))
            models.download { _, _, _ -> }
            assertTrue(models.store.ready())
            models.files()
            assertEquals(4, server.requestCount)
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
        }
    }

    @Test fun publishingOneFileDoesNotTrustAnUnverifiedExistingCompanion() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val models = models(server)
            File(directory, bin).writeText("x".repeat(weights.length))
            models.store.installFile(param, graph.byteInputStream()) { _, _, _ -> }
            assertTrue(models.store.ready()) // Inventory alone only checks sizes.
            val failure = runCatching { models.files() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("checksum"))
        }
    }
}
