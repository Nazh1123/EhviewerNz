package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.*
import com.hippo.ehviewer.translation.engine.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class AutomaticSourceLanguageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val auto = TranslationOptions(source = "auto", target = "fr")
    private val english = "I will go to school tomorrow and meet my friends."

    @Test @Config(qualifiers = "en")
    fun detectionNoticeNamesTraditionalTaiwanTargetExplicitly() {
        assertEquals("Translating: English → Traditional Chinese (Taiwan)",
            TranslationLanguages.sourceDetectedMessage(context, "en", "zh-TW"))
        assertEquals("Translating: English → Traditional Chinese (Hong Kong)",
            TranslationLanguages.sourceDetectedMessage(context, "en", "zh-HK"))
    }

    @Test fun automaticEnglishDetectionPreservesTaiwanTargetInNativeRequests() = runBlocking<Unit> {
        val configured = auto.copy(target = "zh-TW")
        val source = GallerySourceLanguage { SourceLanguageGuess("en", .95f) }
        val created = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        GallerySourceTranslator({ source.options(configured) }) { selected ->
            assertEquals("zh-TW", selected.target)
            created.add(selected.source)
            NativeTranslator(selected) { messages ->
                prompts.add(messages.getJSONObject(0).getString("content"))
                "<|1|>明天我要去學校，然後和朋友見面。" to null
            }
        }.use { translator ->
            translator.translate(listOf(english))
            assertEquals("en", source.observe(configured, listOf(english)))
            translator.translate(listOf(english))
        }
        assertEquals(listOf("auto", "en"), created)
        assertTrue(prompts[0].contains("Translate the following text into Traditional Chinese (Taiwan):"))
        assertTrue(prompts[1].contains("Translate the following English text into Traditional Chinese (Taiwan):"))
        for (prompt in prompts) {
            assertTrue(prompt.contains("Use Traditional Chinese characters for all translated text."))
            assertTrue(prompt.contains("Use Taiwanese wording."))
            assertFalse(prompt.contains("text into Chinese:"))
        }
        assertTrue(NativeTranslator.sampleInstruction(source.options(configured))
            .contains("Use Traditional Chinese characters for all translated text."))
        val effective = source.options(configured)
        assertNotEquals(effective.cacheIdentity().replace("\ntraditional-script-v1", ""), effective.cacheIdentity())
    }

    @Test fun automaticEnglishDetectionPreservesTaiwanTargetInApiRequests() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>明天我要去學校。"}}]}""")) }
            val configured = auto.copy(target = "zh-TW", backend = TranslationBackend.LLM_API,
                apiUrl = server.url("/v1/chat/completions").toString())
            val source = GallerySourceLanguage { SourceLanguageGuess("en", .95f) }
            GallerySourceTranslator({ source.options(configured) }) { selected ->
                assertEquals("zh-TW", selected.target)
                ApiTranslator(selected)
            }.use { translator ->
                translator.translate(listOf(english))
                assertEquals("en", source.observe(configured, listOf(english)))
                translator.translate(listOf(english))
            }
            for (from in listOf("", "English ")) {
                val messages = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                val prompt = messages.getJSONObject(0).getString("content")
                assertTrue(prompt.contains("Translate the following ${from}text into Traditional Chinese (Taiwan)."))
                assertTrue(prompt.contains("Use Traditional Chinese characters for all translated text."))
                assertTrue(prompt.contains("Use Taiwanese wording."))
                assertEquals("<|1|>$english", messages.getJSONObject(1).getString("content"))
            }
        }
    }

    @Test fun manualSourcesSkipIdentificationAndKeepTheConfiguredPromptLanguage() = runBlocking<Unit> {
        val source = GallerySourceLanguage { fail("Manual sources must not identify OCR text"); null }
        MockWebServer().use { server ->
            for (language in TranslationLanguages.manualSources) {
                val configured = auto.copy(source = language, backend = TranslationBackend.LLM_API,
                    apiUrl = server.url("/v1/chat/completions").toString())
                assertNull(source.observe(configured, listOf(english)) {
                    fail("Manual sources must skip the detection step")
                })
                assertNull(source.language)
                assertEquals(configured, source.options(configured))
                val expected = "${TranslationLanguages.promptName(language)} text into French"
                assertTrue(NativeTranslator.buildNumberedMessages(source.options(configured), listOf(english))
                    .getJSONObject(0).getString("content").contains(expected))
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>Bonjour"}}]}"""))
                ApiTranslator(source.options(configured)).use { it.translate(listOf(english)) }
                val messages = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                assertTrue(messages.getJSONObject(0).getString("content").contains(expected))
            }
        }
    }

    @Test fun automaticDecisionCannotOverrideAManuallySelectedSource() = runBlocking<Unit> {
        var calls = 0
        val source = GallerySourceLanguage { calls++; SourceLanguageGuess("en", .95f) }
        assertEquals("en", source.observe(auto, listOf(english)))
        for (language in TranslationLanguages.manualSources) {
            val configured = auto.copy(source = language)
            assertNull(source.observe(configured, listOf(english)))
            assertEquals(configured, source.options(configured))
        }
        assertEquals(1, calls)
        assertEquals("en", source.options(auto).source)
    }

    @Test fun confidenceAndScriptChecksRejectShortUnsupportedAndMixedText() {
        fun select(text: String, vararg guesses: Pair<String, Float>) = OcrSourceLanguageIdentifier.select(text,
            guesses.map { SourceLanguageGuess(it.first, it.second) })?.language
        assertEquals("en", select(english, "en" to .95f, "fr" to .01f))
        assertEquals("ja", select("明日は学校へ行きます。友達と一緒に勉強します。", "ja" to .98f))
        assertEquals("ko", select("내일 학교에 가서 친구들과 함께 공부할 거예요.", "ko" to .98f))
        assertEquals("zh", select("明天我要去学校，然后和朋友一起学习。", "zh" to .98f))
        assertEquals("zh", select("明天我要去學校，然後和朋友一起學習。", "zh" to .98f))
        assertNull(select("BOOM!", "en" to .99f))
        assertNull(select("1234567890", "en" to .99f))
        assertNull(select(english, "en" to .79f))
        assertNull(select(english, "en" to .85f, "fr" to .7f))
        assertNull(select(english, "fr" to .99f, "en" to .01f))
        assertNull(select(english, "en" to Float.NaN))
        assertNull(select(english + " 日本語こんにちは 한국어", "en" to .99f))
        assertNull(select("学校図書館文学世界社会科学教育制度", "ja" to .99f))
    }

    @Test fun firstConfidentOcrAppliesOnlyToCurrentTaskAndReopeningIdentifiesAgain() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val first = GallerySourceLanguage {
            calls.incrementAndGet(); SourceLanguageGuess("en", .95f)
        }
        assertEquals("auto", first.options(auto).source)
        assertEquals("en", first.observe(auto, listOf(english)))
        assertEquals("en", first.options(auto).source)
        assertNull(first.observe(auto, listOf("다른 언어가 있어도 표시는 유지됩니다.")))
        assertEquals(1, calls.get())
        val reopened = GallerySourceLanguage { SourceLanguageGuess("ko", .95f) }
        assertNull(reopened.language)
        assertEquals("ko", reopened.observe(auto, listOf("새 모델로 다시 인식한 한국어 문장입니다.")))
        assertEquals("ko", reopened.language)
        assertEquals("en", first.language)
        assertNull(GallerySourceLanguage().language)
        assertEquals("ko", reopened.options(auto.copy(source = "ko")).source)
        assertEquals("auto", auto.source)
        assertEquals("ja", TranslationSettings(context).read().source)
    }

    @Test fun ambiguousAndFailedDetectionStayUnmarkedAndLaterPagesCanResolve() = runBlocking<Unit> {
        var calls = 0
        val source = GallerySourceLanguage { sample ->
            calls++
            when (calls) {
                1 -> throw IllegalStateException("Model temporarily unavailable")
                2 -> null
                else -> {
                    assertEquals(english, sample)
                    SourceLanguageGuess("en", .9f)
                }
            }
        }
        assertNull(source.observe(auto, listOf("", "<UNK>")))
        assertEquals(0, calls)
        assertNull(source.observe(auto, listOf("Hello")))
        assertNull(source.observe(auto, listOf("There")))
        assertEquals("", source.options(auto).sourceLanguageName())
        assertEquals("en", source.observe(auto, listOf(english)))
        assertEquals("English", source.options(auto).sourceLanguageName())
    }

    @Test fun cancelledOrSupersededOcrCannotMarkCurrentTask() = runBlocking<Unit> {
        val source = GallerySourceLanguage { SourceLanguageGuess("en", .99f) }
        var relevanceChecks = 0
        try {
            source.observe(auto, listOf(english)) {
                if (++relevanceChecks == 2) throw CancellationException("Page superseded")
            }
            fail("Obsolete OCR must stop")
        } catch (_: CancellationException) { }
        assertNull(source.language)
        assertNull(GallerySourceLanguage().language)
    }

    @Test fun simultaneousPagesMakeOneDecisionForCurrentTask() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val source = GallerySourceLanguage {
            calls.incrementAndGet(); delay(10); SourceLanguageGuess("ko", .9f)
        }
        val decisions = List(3) { async { source.observe(auto, listOf("오늘 친구들과 함께 학교에서 공부할 거예요.")) } }.awaitAll()
        assertEquals(1, decisions.count { it != null })
        assertEquals(1, calls.get())
        assertEquals("ko", source.options(auto).source)
    }

    @Test fun resolvedApiPagesCanRunConcurrentlyWithoutRecreatingTheirBackend() = runBlocking<Unit> {
        val source = GallerySourceLanguage { SourceLanguageGuess("en", .99f) }
        source.observe(auto, listOf(english))
        val entered = AtomicInteger()
        var created = 0
        val bothEntered = CompletableDeferred<Unit>()
        val translator = GallerySourceTranslator({ source.options(auto.copy(backend = TranslationBackend.LLM_API)) }) {
            created++
            object : Translator {
                override suspend fun translate(queries: List<String>): List<String> {
                    if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                    withTimeout(2000) { bothEntered.await() }
                    return listOf("译文")
                }
            }
        }
        translator.use {
            List(2) { async { translator.translate(listOf(english)) } }.awaitAll()
            assertEquals(1, created)
            assertEquals(2, entered.get())
        }
    }

    @Test fun unknownPromptsRemainUnspecifiedAndChinesePromptsDoNotInventAScript() {
        val message = NativeTranslator.buildNumberedMessages(auto, listOf("unknown text"))
            .getJSONObject(0).getString("content")
        assertTrue(message.contains("Translate the following text into French"))
        assertFalse(message.contains("Japanese"))
        assertFalse(message.contains("auto text"))
        val chinese = auto.copy(source = "zh")
        assertEquals("Chinese", chinese.sourceLanguageName())
        assertTrue(NativeTranslator.buildNumberedMessages(chinese, listOf("测试文本"))
            .getJSONObject(0).getString("content").contains("Chinese text into French"))
        assertEquals(setOf("ja", "ko", "zh", "fr"), OfflineTranslator.requiredLanguages("auto", "fr"))
        assertFalse(OfflineTranslator.isReady("auto", "fr", setOf("ja", "fr")))
        assertTrue(OfflineTranslator.isReady("auto", "fr", setOf("ja", "ko", "zh", "fr")))
    }

    @Test fun backendIsCreatedAfterOcrAndReplacedIfAnUnknownGalleryBecomesKnown() = runBlocking<Unit> {
        val source = GallerySourceLanguage { SourceLanguageGuess("en", .95f) }
        val created = mutableListOf<String>()
        var closes = 0
        val translator = GallerySourceTranslator({ source.options(auto) }) { options ->
            created.add(options.source)
            object : Translator, AutoCloseable {
                override suspend fun translate(queries: List<String>) = queries.map { "译文" }
                override fun close() { closes++ }
            }
        }
        assertTrue(created.isEmpty())
        translator.use {
            it.translate(listOf("短文"))
            assertEquals(listOf("auto"), created)
            source.observe(auto, listOf(english))
            it.translate(listOf(english))
            it.translate(listOf(english))
            assertEquals(listOf("auto", "en"), created)
            assertEquals(1, closes)
        }
        assertEquals(2, closes)
    }

    private fun line(y: Float) = TextLine(listOf(Pt(10f, y), Pt(170f, y), Pt(170f, y + 20), Pt(10f, y + 20)), 1f)

    @Test fun firstOcrDeterminesJoiningPromptAndCheckpointForThisPageAndFollowingPages() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>Bonjour"}}]}""")) }
            var identifications = 0
            val source = GallerySourceLanguage {
                identifications++; SourceLanguageGuess("en", .95f)
            }
            val configured = auto.copy(backend = TranslationBackend.LLM_API, apiUrl = server.url("/v1/chat/completions").toString())
            val translator = GallerySourceTranslator({ source.options(configured) }) { ApiTranslator(it) }
            val original = Bitmap.createBitmap(190, 100, Bitmap.Config.ARGB_8888)
            val input = File(context.cacheDir, "automatic-source-image").apply { writeText("same original image") }
            val cache = TranslationCache(File(context.cacheDir, "automatic-source-cache"))
            val before = cache.key(input, configured)
            try {
                translator.use {
                    ResumablePipeline(
                        detect = { Detection(listOf(line(10f), line(32f)), Bitmap.createBitmap(190, 100, Bitmap.Config.ARGB_8888)) },
                        recognize = { _, lines ->
                            lines[0].text = "I will go to school tomorrow"
                            lines[1].text = "and meet my friends."
                            source.observe(configured, lines.map { it.text })
                        },
                        inpaint = { page, _, _ -> page.copy(Bitmap.Config.ARGB_8888, true) },
                        translator = it, cfg = configured.engineConfig(), release = {}, warm = {},
                        sourceSeparatorProvider = { TranslationLanguages.lineSeparator(source.options(configured).source) },
                        translationIdentityProvider = { source.options(configured).cacheIdentity() },
                        overlapInpainting = false,
                    ).use { pipeline ->
                        repeat(2) {
                            pipeline.prepare(original).use { prepared ->
                                assertEquals(english, prepared.regions.single().sourceText)
                                val effective = source.options(configured)
                                assertEquals(before, cache.key(input, effective))
                                val result = pipeline.translatePrepared(original, prepared, false) as PageResult.Translated
                                result.page.recycle()
                                assertEquals(0, prepared.translationResume(effective.cacheIdentity())!!.missingCount)
                                assertNull(prepared.translationResume(configured.cacheIdentity()))
                            }
                            val messages = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                            assertTrue(messages.getJSONObject(0).getString("content").contains("English text into French"))
                            assertEquals("<|1|>$english", messages.getJSONObject(1).getString("content"))
                        }
                    }
                }
                assertEquals(1, identifications)
                assertEquals("auto", GallerySourceLanguage().options(configured).source)
            } finally { original.recycle() }
        }
    }
}
