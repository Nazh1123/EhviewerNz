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
        assertEquals("en", first.observe(listOf(english)))
        assertEquals("en", first.options(auto).source)
        assertNull(first.observe(listOf("다른 언어가 있어도 표시는 유지됩니다.")))
        assertEquals(1, calls.get())
        val reopened = GallerySourceLanguage { SourceLanguageGuess("ko", .95f) }
        assertNull(reopened.language)
        assertEquals("ko", reopened.observe(listOf("새 모델로 다시 인식한 한국어 문장입니다.")))
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
        assertNull(source.observe(listOf("", "<UNK>")))
        assertEquals(0, calls)
        assertNull(source.observe(listOf("Hello")))
        assertNull(source.observe(listOf("There")))
        assertEquals("", source.options(auto).sourceLanguageName())
        assertEquals("en", source.observe(listOf(english)))
        assertEquals("English", source.options(auto).sourceLanguageName())
    }

    @Test fun cancelledOrSupersededOcrCannotMarkCurrentTask() = runBlocking<Unit> {
        val source = GallerySourceLanguage { SourceLanguageGuess("en", .99f) }
        var relevanceChecks = 0
        try {
            source.observe(listOf(english)) {
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
        val decisions = List(3) { async { source.observe(listOf("오늘 친구들과 함께 학교에서 공부할 거예요.")) } }.awaitAll()
        assertEquals(1, decisions.count { it != null })
        assertEquals(1, calls.get())
        assertEquals("ko", source.options(auto).source)
    }

    @Test fun resolvedApiPagesCanRunConcurrentlyWithoutRecreatingTheirBackend() = runBlocking<Unit> {
        val source = GallerySourceLanguage { SourceLanguageGuess("en", .99f) }
        source.observe(listOf(english))
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
        assertTrue(message.contains("Translate comic text to French"))
        assertFalse(message.contains("Japanese"))
        assertFalse(message.contains("auto text"))
        val chinese = auto.copy(source = "zh")
        assertEquals("Chinese", chinese.sourceLanguageName())
        assertTrue(NativeTranslator.buildNumberedMessages(chinese, listOf("测试文本"))
            .getJSONObject(0).getString("content").contains("Chinese comic text to French"))
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
            source.observe(listOf(english))
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
                            source.observe(lines.map { it.text })
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
