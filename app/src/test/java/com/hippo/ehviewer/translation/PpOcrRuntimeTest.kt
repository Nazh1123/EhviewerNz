package com.hippo.ehviewer.translation

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
class PpOcrRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private val libraries = linkedMapOf("libonnxruntime.so" to "core", "libonnxruntime4j_jni.so" to "jni")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun archive(entries: Map<String, String>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun official(entries: Map<String, String> = libraries) = archive(
        entries.mapKeys { "jni/arm64-v8a/${it.key}" } + mapOf("classes.jar" to "never install", "jni/x86/libonnxruntime.so" to "other ABI"))
    private fun runtime(aar: ByteArray, url: String = "https://example.com/runtime.aar"): PpOcrRuntime {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = temp.root
            override fun getCacheDir() = temp.root
        }
        val manifest = JSONObject().put("version", "test").put("bundle", JSONObject().put("url", url)
            .put("size", aar.size).put("sha256", digest(aar)))
            .put("models", JSONArray().apply { libraries.forEach { (name, value) ->
                put(JSONObject().put("name", name).put("size", value.length).put("sha256", digest(value.toByteArray())))
            } })
        return PpOcrRuntime(context, manifest.toString())
    }
    private fun noParts() = assertFalse(temp.root.walk().any { it.extension == "part" })
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid component accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun officialDownloadInstallsOnlyPinnedArm64Libraries() = runBlocking {
        val aar = official()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(aar)))
            val runtime = runtime(aar, server.url("/runtime.aar").toString())
            assertFalse(runtime.ready())
            runtime.download { _, _, _ -> }
            assertTrue(runtime.ready())
            val files = runtime.store.verifiedFiles()
            assertEquals(libraries.keys, files.keys)
            files.forEach { (name, path) -> assertEquals(libraries[name], File(path).readText()) }
            assertEquals(libraries.keys, File(temp.root, "ppocr-runtime-test").list()!!.toSet())
            noParts()
            runtime.store.delete()
            assertFalse(runtime.ready())
            runtime.installOfficialArchive(aar.inputStream()) { _, _, _ -> }
            assertTrue(runtime.ready())
            runtime.store.verifiedFiles(); noParts()
        }
    }
    @Test fun corruptArchiveAndOversizedDownloadLeaveNoInstalledComponent() = runBlocking {
        val aar = official()
        val runtime = runtime(aar)
        val changed = aar.copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() }
        for (invalid in listOf(changed, aar + byteArrayOf(1), aar.dropLast(1).toByteArray())) {
            rejected { runtime.installOfficialArchive(invalid.inputStream()) { _, _, _ -> } }
            assertFalse(runtime.ready()); noParts()
        }
    }
    @Test fun validOuterHashDoesNotBypassNativeFileHashesOrCompleteness() = runBlocking {
        for (aar in listOf(official(libraries + ("libonnxruntime.so" to "evil")), official(libraries.filterKeys { it.endsWith("jni.so") }))) {
            val runtime = runtime(aar)
            rejected { runtime.installOfficialArchive(aar.inputStream()) { _, _, _ -> } }
            assertFalse(runtime.ready()); noParts()
        }
    }
    @Test fun compactZipImportRejectsTraversalAndPreservesInstalledFilesOnFailure() = runBlocking {
        val runtime = runtime(official())
        val zip = temp.newFile("component.zip").apply { writeBytes(archive(libraries)) }
        runtime.import(Uri.fromFile(zip)) { _, _, _ -> }
        assertTrue(runtime.ready())
        for (entries in listOf(libraries + ("../escape.so" to "evil"), libraries + ("libonnxruntime4j_jni.so" to "bad"))) {
            zip.writeBytes(archive(entries))
            try { runtime.import(Uri.fromFile(zip)) { _, _, _ -> }; fail("Invalid ZIP accepted") }
            catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            assertTrue(runtime.ready()); runtime.store.verifiedFiles(); noParts()
        }
        assertFalse(File(temp.root, "escape.so").exists())
    }
    @Test fun cancellationDuringStagingPublishesNothing() = runBlocking {
        val aar = official()
        val runtime = runtime(aar)
        val job = launch {
            runtime.installOfficialArchive(aar.inputStream()) { name, _, _ ->
                if (name == "libonnxruntime.so") throw CancellationException("cancel install")
            }
        }
        job.join()
        assertTrue(job.isCancelled); assertFalse(runtime.ready()); noParts()
    }
}
