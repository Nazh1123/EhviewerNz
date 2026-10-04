package com.hippo.ehviewer.translation

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlinx.coroutines.*
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
    private val payloads = linkedMapOf("dbnet_detect.ncnn.param" to "detector", "ocr_48px_ctc.ncnn.param" to "ocr",
        "mit_aot_fixed512.ncnn.param" to "inpaint")
    private fun models(url: String? = null): TranslationModels {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = temp.root
        }
        val manifest = JSONObject().put("models", JSONArray().apply {
            payloads.forEach { (name, payload) -> put(JSONObject().put("name", name)
                .put("size", payload.length).put("sha256", MessageDigest.getInstance("SHA-256")
                    .digest(payload.toByteArray()).joinToString("") { "%02x".format(it) })) }
        }).apply { if (url != null) put("bundle", JSONObject().put("url", url)) }.toString()
        return TranslationModels(context, manifest)
    }
    private fun sources(): List<File> = payloads.map { (name, payload) -> temp.newFile(name).apply { writeText(payload) } }

    private fun archive(entries: Map<String, String> = payloads): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    private fun assertNoStaging() = assertFalse(File(temp.root, "translation-models").listFiles()!!.any { it.extension == "part" })

    @Test fun downloadsVerifiedArchiveAndReportsWholeBundleProgress() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(archive())))
            val store = models(server.url("/bundle.zip").toString())
            val progress = mutableListOf<Long>()
            MangaModelDownloader(store).use { it.downloadAndImport { _, copied, total ->
                assertEquals(store.totalBytes, total); progress.add(copied)
            } }
            assertTrue(store.ready())
            assertEquals(store.totalBytes, progress.last())
            assertEquals(progress.sorted(), progress)
            assertNotNull(store.verified())
            assertNoStaging()
            assertEquals("/bundle.zip", server.takeRequest().path)
        }
    }

    @Test fun corruptMissingOversizedAndUnexpectedEntriesPreserveInstalledBundle() = runBlocking {
        val store = models()
        store.import(sources().map(Uri::fromFile)) { _, _, _ -> }
        val first = payloads.keys.first()
        val invalid = listOf(
            payloads + (first to "x".repeat(payloads.getValue(first).length)),
            payloads + (first to "x".repeat(100)),
            payloads.filterKeys { it != first },
            payloads + ("../escape.bin" to "bad"),
        )
        val duplicateName = "X" + first.drop(1)
        val duplicate = archive(payloads + (duplicateName to payloads.getValue(first)))
            .toString(Charsets.ISO_8859_1).replace(duplicateName, first).toByteArray(Charsets.ISO_8859_1)
        val brokenZip = archive().let { it.copyOf(it.size / 3) }
        for (bytes in invalid.map(::archive) + listOf(duplicate, brokenZip)) {
            try { store.installArchive(bytes.inputStream()) { _, _, _ -> }; fail("Bad archive accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            catch (_: java.io.IOException) { }
            for ((name, data) in payloads) assertEquals(data, File(temp.root, "translation-models/$name").readText())
            assertNoStaging()
        }
        assertFalse(File(temp.root, "escape.bin").exists())
    }

    @Test fun cancellationInterruptsBlockedDownloadAndHttpErrorPreservesModels() = runBlocking {
        MockWebServer().use { server ->
            val store = models(server.url("/bundle.zip").toString())
            store.import(sources().map(Uri::fromFile)) { _, _, _ -> }
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(Buffer().write(archive())).setBodyDelay(4, TimeUnit.SECONDS))
            MangaModelDownloader(store).use { downloader ->
                try { downloader.downloadAndImport { _, _, _ -> }; fail("404 accepted") }
                catch (error: IllegalStateException) { assertTrue(error.message!!.contains("404")) }
                server.takeRequest()
                val job = launch(Dispatchers.IO) { downloader.downloadAndImport { _, _, _ -> } }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
            for ((name, data) in payloads) assertEquals(data, File(temp.root, "translation-models/$name").readText())
            assertNoStaging()
        }
    }

    @Test fun replacingVerifiedFileWithSameSizeAndTimestampStillRequiresChecksum() = runBlocking {
        val store = models()
        store.import(sources().map(Uri::fromFile)) { _, _, _ -> }
        assertEquals(store.verified(), store.verified())
        val target = File(temp.root, "translation-models/${payloads.keys.first()}")
        val stamp = Files.getLastModifiedTime(target.toPath())
        val replacement = temp.newFile("replacement").apply { writeText("x".repeat(target.length().toInt())) }
        Files.setLastModifiedTime(replacement.toPath(), stamp)
        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try { store.verified(); fail("Replacement bypassed verification") }
        catch (_: IllegalStateException) { }
    }

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

    @Test fun cancelledImportPreservesInstalledFilesAndRemovesStagingFiles() = runBlocking {
        val store = models()
        val files = sources()
        store.import(files.map(Uri::fromFile)) { _, _, _ -> }
        val job = launch(Dispatchers.IO) {
            store.import(files.map(Uri::fromFile)) { _, _, _ -> throw CancellationException("Stop importing") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(store.ready())
        for ((name, payload) in payloads) assertEquals(payload, File(temp.root, "translation-models/$name").readText())
        assertFalse(File(temp.root, "translation-models").listFiles()!!.any { it.extension == "part" })
        assertTrue(files.all { it.exists() })
    }

    @Test fun shippedImageBundleDownloadsOnlyFromThisProjectAndPinsEachFile() {
        val descriptor = RuntimeEnvironment.getApplication().assets.open("translation-models.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("https://github.com/Nazh1123/EhviewerNz/releases/download/manga-models-v1/ehviewer-manga-models-v1.zip",
            descriptor.getJSONObject("bundle").getString("url"))
        val manifest = descriptor.getJSONArray("models")
        assertEquals(7, manifest.length())
        for (index in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(index)
            assertFalse(entry.has("url"))
            assertTrue(entry.getLong("size") > 0)
            assertTrue(entry.getString("sha256").matches(Regex("[0-9a-f]{64}")))
        }
    }
}
