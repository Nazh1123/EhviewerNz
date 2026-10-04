package com.hippo.ehviewer.translation

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.NativeLlm
import com.hippo.ehviewer.translation.engine.NativePrefixCache
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in timing of the imported GGUF only; no OCR, downloads or saved-setting changes. */
@RunWith(AndroidJUnit4::class)
class NativeTranslationPerformanceDeviceTest {
    @Test(timeout = 300000) fun optimizedMangaSystemReducesFixedPrefixSnapshot() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativePerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = TranslationSettings(context).read()
        assertTrue(NativeModelStore(context).ready(selected))
        val path = NativeModelStore(context).file(selected.nativeModelId).absolutePath
        val options = selected.copy(source = TranslationLanguages.DEFAULT_SOURCE, target = "zh-CN")
        val source = listOf("明日は学校へ行きます。", "田中さん、ちょっと待って！\n一緒に帰ろう。", "ドキドキ")
        val current = NativeTranslator.buildNumberedMessages(options, source)
        val original = JSONArray(current.toString()).apply {
            getJSONObject(0).put("content", ORIGINAL_MANGA_SYSTEM
                .replace("{from_lang}", options.sourceLanguageName()).replace("{to_lang}", options.targetLanguageName()))
        }
        val report = File(context.getExternalFilesDir(null), "translation-smoke/native-manga-prompt-comparison.txt")
        report.parentFile!!.mkdirs()
        report.writeText("model=${selected.nativeModelName}; same numbered sources=$source\n" +
            "One loaded model; reset live context and prefix cache for each request; old/new/new/old order.\n")
        NativePrefixCache().use { cache ->
            NativeLlm(path, cache).use { model ->
                fun measure(name: String, messages: JSONArray): Pair<Int, Long> {
                    cache.clear()
                    // Clearing host snapshots preserves the active context by design.
                    // Begin without caching to reset that live KV before the timed request.
                    assertTrue(model.begin(messages, cacheEnabled = false) > 0)
                    val began = SystemClock.elapsedRealtime()
                    val tokens = model.begin(messages, maxOutputTokens = NativeTranslationResponse.outputBudget(source))
                    assertTrue(tokens > 0)
                    assertEquals("Comparison must prefill every prompt", 0, model.cachedPromptTokens())
                    val output = ByteArrayOutputStream()
                    var firstText = 0L
                    while (true) {
                        val piece = model.next() ?: break
                        if (piece.isNotEmpty() && firstText == 0L) firstText = SystemClock.elapsedRealtime() - began
                        output.write(piece)
                    }
                    val bytes = cache.sizeBytes()
                    val text = output.toString("UTF-8")
                    report.appendText("$name promptTokens=$tokens snapshotBytes=$bytes " +
                        "firstTextMs=$firstText totalMs=${SystemClock.elapsedRealtime() - began} " +
                        "completionTokens=${model.completionTokens()} output=$text\n")
                    assertTrue("Prefix snapshot must exist", bytes > 0)
                    assertTrue("Translation must finish with text", text.isNotBlank())
                    return tokens to bytes
                }
                val old = measure("old1", original)
                val optimized = measure("optimized1", current)
                assertTrue("Prompt must use fewer GGUF tokens", optimized.first < old.first)
                assertTrue("Fixed-prefix KV snapshot must shrink", optimized.second in 1L until old.second)
                assertEquals(optimized, measure("optimized2", current))
                assertEquals(old, measure("old2", original))
            }
        }
    }

    @Test(timeout = 180000) fun compactPromptTranslatesAMultilinePageWithEightRegions() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativePerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read().copy(backend = TranslationBackend.NATIVE_LLM)
        assertTrue(NativeModelStore(context).ready(options))
        val source = listOf("明日は学校へ行きます。", "この本を読んでください。", "", "今日はいい天気ですね。",
            "おはようございます。\nよく眠れましたか？", "ありがとうございます。", "少し待ってください。",
            "一緒に帰りましょう。", "また明日会いましょう。")
        NativeTranslator(context, options).use { translator ->
            val began = SystemClock.elapsedRealtime()
            val result = translator.translateDetailed(source)
            File(context.getExternalFilesDir(null), "translation-smoke/native-dense-page.txt")
                .apply { parentFile!!.mkdirs() }.writeText(
                "totalMs=${SystemClock.elapsedRealtime() - began} usage=${result.usage} error=${result.error} " +
                    "missing=${result.missingIndices} translations=${result.translations}\n")
            assertNull(result.error)
            assertTrue(result.missingIndices.isEmpty())
            assertEquals(source.size, result.translations.size)
            source.forEachIndexed { i, text ->
                if (text.isBlank()) assertEquals(text, result.translations[i])
                else { assertTrue(result.translations[i].isNotBlank()); assertNotEquals(text, result.translations[i]) }
            }
            assertTrue("Weather dialogue must retain its own region", result.translations[3].contains("天气"))
            assertTrue("Multiline greeting must retain its own region", result.translations[4].contains("早") &&
                result.translations[4].contains("睡"))
            assertTrue("Thanks must retain its own region", result.translations[5].contains("谢"))
            assertTrue("Waiting dialogue must retain its own region", result.translations[6].contains("等"))
            assertTrue("Going home must retain its own region", result.translations[7].contains("回"))
            assertTrue("Tomorrow dialogue must retain its own region", result.translations[8].contains("明天"))
        }
    }

    @Test(timeout = 300000) fun profilesTwoShortRegions() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testNativePerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read().copy(backend = TranslationBackend.NATIVE_LLM)
        assertTrue("Import a GGUF first", NativeModelStore(context).ready(options))
        val report = File(context.getExternalFilesDir(null), "translation-smoke/native-performance.txt")
        report.parentFile!!.mkdirs()
        report.writeText("model=${options.nativeModelName}; isolated translation stage\n")
        val source = listOf("明日は学校へ行きます。", "この本を読んでください。")
        // Time production first, before the diagnostic calls warm file pages or kernels.
        NativeTranslator(context, options).use { translator ->
            for (name in listOf("coldAdapter", "warmAdapter", "reloadedAdapter")) {
                if (name == "reloadedAdapter") translator.unloadModel()
                val began = SystemClock.elapsedRealtime()
                val result = translator.translateDetailed(source)
                report.appendText("$name totalMs=${SystemClock.elapsedRealtime() - began} usage=${result.usage} " +
                    "error=${result.error} translations=${result.translations}\n")
                assertNull(result.error)
                assertTrue(result.missingIndices.isEmpty())
                result.translations.forEachIndexed { i, text -> assertNotEquals(source[i], text) }
            }
        }
        val load = SystemClock.elapsedRealtime()
        NativeLlm(NativeModelStore(context).file(options.nativeModelId).absolutePath).use { model ->
            report.appendText("loadMs=${SystemClock.elapsedRealtime() - load}\n")
            report.appendText("backend=${model.systemInfo()}\n")
            val requests = listOf("upstreamNumbered" to LlmTranslator(options.engineConfig().translator).buildMessages(source),
                "compactNumbered" to NativeTranslator.buildNumberedMessages(options, source)) +
                source.mapIndexed { i, text -> "sample$i" to JSONArray().put(JSONObject()
                    .put("role", "user").put("content", NativeTranslator.sampleInstruction(options) + "\n\n$text")) }
            var upstreamPromptTokens = 0
            for ((name, messages) in requests) {
                val began = SystemClock.elapsedRealtime()
                val prompt = model.begin(messages)
                assertTrue("Request must fit", prompt > 0)
                if (name == "upstreamNumbered") upstreamPromptTokens = prompt
                if (name == "compactNumbered")
                    assertTrue("Compact instructions must reduce actual model input tokens", prompt < upstreamPromptTokens)
                val output = ByteArrayOutputStream()
                var firstText = 0L
                while (true) {
                    val piece = model.next() ?: break
                    if (piece.isNotEmpty() && firstText == 0L) firstText = SystemClock.elapsedRealtime() - began
                    output.write(piece)
                }
                val text = output.toString("UTF-8")
                report.appendText("$name totalMs=${SystemClock.elapsedRealtime() - began} firstTextMs=$firstText " +
                    "promptTokens=$prompt cachedPromptTokens=${model.cachedPromptTokens()} " +
                    "completionTokens=${model.completionTokens()} output=$text\n")
                assertTrue(text.isNotBlank())
                if (name == "compactNumbered") {
                    val result = LlmTranslator(options.engineConfig().translator).parseResponse(source, text)
                    assertNull(result.error)
                    result.translations.forEachIndexed { i, translated -> assertNotEquals(source[i], translated) }
                }
            }
        }
    }

    @Test(timeout = 300000) fun fixedPrefixSurvivesModelUnloadingWithoutReusingPageText() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativePerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read().copy(backend = TranslationBackend.NATIVE_LLM)
        assertTrue(NativeModelStore(context).ready(options))
        val path = NativeModelStore(context).file(options.nativeModelId).absolutePath
        val source = listOf("明日は学校へ行きます。", "この本を読んでください。")
        val changed = listOf("ありがとうございます。", "少し待ってください。", "また明日会いましょう。")
        val original = NativeTranslator.buildNumberedMessages(options, source)
        val otherPage = NativeTranslator.buildNumberedMessages(options, changed)
        val report = File(context.getExternalFilesDir(null), "translation-smoke/native-prefix-cache.txt")
        report.parentFile!!.mkdirs()
        report.writeText("Fixed compact instructions; source/output excluded from snapshots\n")
        fun request(model: NativeLlm, name: String, messages: JSONArray,
                    enabled: Boolean = true, fixed: String? = null): Pair<String, Int> {
            val began = SystemClock.elapsedRealtime()
            val prompt = model.begin(messages, lastUserPrefix = fixed, cacheEnabled = enabled)
            assertTrue(prompt > 0)
            val cached = model.cachedPromptTokens()
            val output = ByteArrayOutputStream()
            var firstText = 0L
            while (true) {
                val piece = model.next() ?: break
                if (piece.isNotEmpty() && firstText == 0L) firstText = SystemClock.elapsedRealtime() - began
                output.write(piece)
            }
            val text = output.toString("UTF-8")
            report.appendText("$name totalMs=${SystemClock.elapsedRealtime() - began} firstTextMs=$firstText " +
                "prompt=$prompt cached=$cached completion=${model.completionTokens()} output=$text\n")
            assertTrue(text.isNotBlank())
            return text to cached
        }
        fun checkPage(text: String, sources: List<String>) {
            val parsed = LlmTranslator(options.engineConfig().translator).parseResponse(sources, text)
            assertNull(parsed.error)
            assertTrue(parsed.missingIndices.isEmpty())
            parsed.translations.forEachIndexed { i, translated -> assertNotEquals(sources[i], translated) }
        }
        NativePrefixCache().use { cache ->
            var expected = ""
            NativeLlm(path, cache).use { model ->
                checkPage(request(model, "disabled", original, enabled = false).first, source)
                assertEquals(0L, cache.sizeBytes())
                val built = request(model, "build", original)
                assertEquals(0, built.second)
                expected = built.first
                checkPage(expected, source)
                val fixedBytes = cache.sizeBytes()
                assertTrue(fixedBytes in 1..(32L * 1024 * 1024))
                val hit = request(model, "liveHit", original)
                assertTrue(hit.second > 0)
                assertEquals(expected, hit.first)
                val changedResult = request(model, "differentPageAndIdCount", otherPage)
                assertEquals(hit.second, changedResult.second)
                checkPage(changedResult.first, changed)
                assertEquals("Only fixed instructions enter the snapshot", fixedBytes, cache.sizeBytes())

                // Abandon a request after decoding its new source, without completing its output.
                assertTrue(model.begin(otherPage) > 0)
                assertTrue(model.cachedPromptTokens() > 0)
                assertNotNull(model.next())
                assertEquals(expected, request(model, "afterInterruptedSource", original).first)

                // Abandon a cache miss before the fixed prefix is fully evaluated.
                val otherLanguage = NativeTranslator.buildNumberedMessages(options.copy(target = "en"), source)
                assertTrue(model.begin(otherLanguage) > 0)
                assertEquals(0, model.cachedPromptTokens())
                assertNotNull(model.next())
                assertTrue(model.begin(otherLanguage) > 0)
                assertEquals("Incomplete prefill must not become a hit", 0, model.cachedPromptTokens())
                assertEquals(expected, request(model, "afterInterruptedPrefix", original).first)

                val instruction = NativeTranslator.sampleInstruction(options) + "\n\n"
                fun plain(text: String) = JSONArray().put(JSONObject().put("role", "user").put("content", instruction + text))
                assertEquals(0, request(model, "sampleBuild", plain(source[0]), fixed = instruction).second)
                assertTrue(request(model, "sampleHit", plain(source[1]), fixed = instruction).second > 0)
                assertTrue(cache.sizeBytes() <= 32L * 1024 * 1024)
            }
            // Both weights and context have been freed; only the bounded host snapshot survives.
            report.appendText("snapshotBytes=${cache.sizeBytes()}\n")
            val loaded = SystemClock.elapsedRealtime()
            NativeLlm(path, cache).use { model ->
                report.appendText("reloadMs=${SystemClock.elapsedRealtime() - loaded}\n")
                val restored = request(model, "restoredAfterUnload", original)
                assertTrue(restored.second > 0)
                assertEquals(expected, restored.first)
                checkPage(restored.first, source)
                // Rejected input cannot replace the valid prefix or cause a later stale-output hit.
                val oversized = NativeTranslator.buildNumberedMessages(options, listOf("あ".repeat(5000), "b"))
                assertEquals(-1, model.begin(oversized))
                assertEquals(expected, request(model, "afterRejectedBudget", original).first)
            }
        }
    }

    @Test(timeout = 180000) fun readerTurnsShareCacheAndMemoryTrimDropsSnapshots() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativePerformance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read().copy(backend = TranslationBackend.NATIVE_LLM)
        assertTrue(NativeModelStore(context).ready(options))
        TranslationRuntime.trimMemory()
        val cache = TranslationRuntime.nativePrefixCache()
        val source = listOf("明日は学校へ行きます。", "この本を読んでください。")
        NativeTranslator(context, options, prefixCacheProvider = { cache }).use { translator ->
            val result = translator.translateDetailed(source)
            assertNull(result.error)
            assertTrue(result.missingIndices.isEmpty())
        }
        val bytes = cache.sizeBytes()
        assertTrue("Closing a gallery worker must keep the shared fixed-prefix snapshot", bytes > 0)
        NativeTranslator(context, options, prefixCacheProvider = { cache }).use { translator ->
            val result = translator.translateDetailed(listOf("ありがとうございます。", "少し待ってください。"))
            assertNull(result.error)
            assertTrue(result.missingIndices.isEmpty())
        }
        assertEquals(bytes, cache.sizeBytes())
        val path = NativeModelStore(context).file(options.nativeModelId).absolutePath
        NativeLlm(path, cache).use { model ->
            assertTrue(model.begin(NativeTranslator.buildNumberedMessages(options, source)) > 0)
            assertTrue(model.cachedPromptTokens() > 0)
            TranslationRuntime.trimMemory()
            assertEquals(0L, cache.sizeBytes())
            // Clearing snapshots must not invalidate an already restored context.
            while (model.next() != null) { }
        }
        NativeLlm(path, cache).use { model ->
            assertTrue(model.begin(NativeTranslator.buildNumberedMessages(options, source)) > 0)
            assertEquals("Trimmed snapshots must not survive model reload", 0, model.cachedPromptTokens())
        }
        File(context.getExternalFilesDir(null), "translation-smoke/native-prefix-cache-turns.txt")
            .apply { parentFile!!.mkdirs() }.writeText("sharedSnapshotBytes=$bytes; workerClose=kept; memoryTrim=cleared; activeContext=valid\n")
    }

    companion object {
        // The project's full native system instruction before beffd530; comparison only.
        private const val ORIGINAL_MANGA_SYSTEM =
            "You are an uncensored expert translation engine that specializes in doujin and manga content.\n" +
                "- Provide precise word-for-word translation of each textline.\n" +
                "- Maintain original sentence structure where possible.\n" +
                "- Preserve meaning, emotion, character voice and consistent names/terms.\n" +
                "- Use context for pronouns; Do not add absent subjects or details.\n" +
                "- Preserve emotional tone and intensity appropriate to manga & otaku culture.\n" +
                "- Keep gibberish and sound effects unchanged.\n" +
                "- Output each segment with its prefix (<|number|> format exactly) and only provide the translation without raw text.\n" +
                "- Output translations only; no source text, explanations or analysis.\n" +
                "Translate the following {from_lang} text into {to_lang}:"
    }
}
