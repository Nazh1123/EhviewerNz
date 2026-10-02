package com.hippo.ehviewer.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.NativeLlm
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri

@RunWith(AndroidJUnit4::class)
class NativeTranslationDeviceTest {
    @Test fun importsAndTranslatesFixture() = runBlocking<Unit>(Dispatchers.IO) {
        val path = InstrumentationRegistry.getArguments().getString("nativeModelPath")
        assumeTrue(path != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "native-import-test").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("native-test-$name", mode)
        }
        val prefs = context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val store = NativeModelStore(context)
            assertFalse(store.ready(TranslationSettings(context).read()))
            store.import(Uri.fromFile(File(requireNotNull(path))))
            val options = TranslationSettings(context).read()
            assertTrue(store.ready(options))
            assertEquals(64, options.nativeModelId.length)
            NativeTranslator(context, options).use {
                val results = it.translate(listOf("明日は学校へ行きます。", "", "こんにちは。"))
                assertEquals(3, results.size)
                assertEquals("", results[1])
                assertTrue(results[0].isNotBlank())
                assertNotEquals("明日は学校へ行きます。", results[0])
                assertNotEquals("こんにちは。", results[2])
                android.util.Log.i("NativeTranslationSmoke", results.joinToString(" / "))
            }
            // Invalid replacement leaves both preferences and the usable previous model intact.
            val bad = File(root, "bad.gguf").apply { writeText("not a model") }
            try { store.import(Uri.fromFile(bad)); fail("Invalid model accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(options, TranslationSettings(context).read())
            assertTrue(store.ready(options))
        } finally { prefs.edit().clear().commit(); root.deleteRecursively() }
    }

    @Test fun nativeLibraryLoadsAndRejectsInvalidModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("invalid-", ".gguf", context.cacheDir)
        try {
            file.writeText("This is not a model")
            assertThrows(IllegalStateException::class.java) { NativeLlm(file.absolutePath).close() }
        } finally { file.delete() }
    }

    /** Explicit opt-in; uses an already imported model and never changes saved preferences. */
    @Test fun translatesWithImportedModel() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativeLlm") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read()
        assertTrue("Import a GGUF model first", NativeModelStore(context).ready(options))
        NativeTranslator(context, options).use {
            val source = listOf("明日は学校へ行きます。", "", "こんにちは。")
            val result = it.translate(source)
            assertEquals(source.size, result.size)
            assertEquals("", result[1])
            for (index in listOf(0, 2)) {
                assertTrue(result[index].isNotBlank())
                assertNotEquals(source[index], result[index])
                assertFalse(result[index].contains("<think>"))
            }
        }
    }
}
