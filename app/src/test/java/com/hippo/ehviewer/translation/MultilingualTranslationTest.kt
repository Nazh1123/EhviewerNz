package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import com.hippo.ehviewer.translation.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class MultilingualTranslationTest {
    @Test fun eachSourcePersistsAcrossBackendsAndUsesItsOwnPromptAndSample() {
        val settings = TranslationSettings(RuntimeEnvironment.getApplication())
        assertEquals("ja", settings.read().source)
        for (source in TranslationLanguages.sources) {
            for (backend in TranslationBackend.entries) {
                val options = TranslationOptions(source = source, target = "fr", backend = backend)
                settings.save(options)
                assertEquals(options, settings.read())
                assertEquals(source, settings.read().withBackend(TranslationBackend.ML_KIT).source)
                assertEquals(TranslationLanguages.promptName(source), options.engineConfig().translator.fromLangName)
                assertFalse(options.sampleText().isBlank())
                if (source != "ja") assertNotEquals(TranslationOptions().sampleText(), options.sampleText())
            }
        }
        assertEquals("zh", TranslationOptions(source = "zh-CN").mlKitSource)
        assertEquals("zh", TranslationOptions(source = "zh-TW").mlKitSource)
        assertEquals("en", TranslationOptions(source = "en").mlKitSource)
        for (source in listOf("fr", "zh", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) { settings.save(TranslationOptions(source = source)) }
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("manga_translation", 0).edit()
            .putString("source_language", "invalid").commit()
        assertEquals("ja", settings.read().source)
    }

    @Test fun switchingSourceSeparatesPreparationResultAndPartialCheckpointIdentities() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "multilingual-input").apply { writeText("same image") }
        val cache = TranslationCache(File(file.parentFile, "language-cache"))
        for (backend in TranslationBackend.entries) {
            val japanese = TranslationOptions(target = "fr", backend = backend)
            val english = japanese.copy(source = "en")
            assertNotEquals(japanese.cacheIdentity(), english.cacheIdentity())
            assertNotEquals(japanese.preparationIdentity(), english.preparationIdentity())
            assertEquals(cache.key(file, japanese), cache.key(file, english))
            assertNotEquals(cache.preparationKey(file, japanese), cache.preparationKey(file, english))
            assertEquals(english.preparationIdentity(), english.copy(target = "en").preparationIdentity())
            assertNotEquals(english.cacheIdentity(), english.copy(target = "en").cacheIdentity())
        }
    }

    @Test fun readinessRequiresTheSelectedPairAndNeverAnEnglishDownload() = runBlocking<Unit> {
        assertEquals(setOf("ko", "zh"), OfflineTranslator.requiredLanguages("ko", "zh"))
        assertFalse(OfflineTranslator.isReady("ko", "zh", setOf("ja", "zh")))
        assertTrue(OfflineTranslator.isReady("ko", "zh", setOf("ko", "zh")))
        assertTrue(OfflineTranslator.isReady("en", "zh", setOf("zh")))
        assertTrue(OfflineTranslator.isReady("ko", "en", setOf("ko")))
        assertFalse(OfflineTranslator.isReady("en", "ko", setOf("ja")))
        assertTrue(OfflineTranslator.isReady("zh", "zh", emptySet()))
        assertTrue(OfflineTranslator.isReady("en", "en", emptySet()))
        OfflineTranslator("en", "en").use {
            it.prepare() // Would contact the SDK/network if an unnecessary client were created.
            assertEquals(listOf("Already English"), it.translate(listOf("Already English")))
        }
    }

    @Test fun dictionaryMarkersAreRejectedWithoutStrippingValidMultilingualText() {
        for (text in listOf("뜸<UNK>벨豐", "hello<PAD>", "<S>你好", "text<UNUSED3>", "<SEP>"))
            assertEquals("", TranslationOcrText.clean(text))
        assertEquals("Hello world!", TranslationOcrText.clean(" Hello world! "))
        assertEquals("안녕 세상", TranslationOcrText.clean("안녕<LF>세상"))
        assertEquals("你好，世界！", TranslationOcrText.clean("你好，世界！"))
        assertEquals("今日はHappyな日です。", TranslationOcrText.clean("今日はHappyな日です。"))
    }

    @Test fun nativeBothNumberedAndPlainRequestsUseTheChosenSource() = runBlocking<Unit> {
        for ((source, name) in listOf("en" to "English", "ko" to "Korean", "zh-TW" to "Traditional Chinese (Taiwan)")) {
            val options = TranslationOptions(source = source, target = "fr")
            val numbered = NativeTranslator.buildNumberedMessages(options, listOf(options.sampleText(), "second"))
            assertTrue(numbered.getJSONObject(0).getString("content").contains("$name comic text to French"))
            NativeTranslator(options) { messages ->
                assertTrue(messages.getJSONObject(0).getString("content").startsWith("Translate $name text to French. Output translation only."))
                "Bonjour" to null
            }.use { assertEquals(listOf("Bonjour"), it.translate(listOf(options.sampleText()))) }
        }
        NativeTranslator(TranslationOptions(source = "en")) { messages ->
            assertTrue(messages.getJSONObject(0).getString("content").startsWith("将以下English文本翻译为简体中文"))
            "明天去学校" to null
        }.use { assertEquals(listOf("明天去学校"), it.translate(listOf("I will go to school tomorrow."))) }
    }

    private fun line(text: String, y: Float) = TextLine(
        listOf(Pt(10f, y), Pt(160f, y), Pt(160f, y + 20f), Pt(10f, y + 20f)), 1f
    ).apply { this.text = text }

    @Test fun groupingPreservesWordsInEnglishAndKoreanAndCjkRemainsContinuous() {
        for ((source, texts, expected) in listOf(
            Triple("en", listOf(" Hello ", " world! "), "Hello world!"),
            Triple("ko", listOf("오늘 날씨가", "좋네요."), "오늘 날씨가 좋네요."),
            Triple("ja", listOf("明日は", "学校へ行きます。"), "明日は学校へ行きます。"),
            Triple("zh-TW", listOf("明天我要", "去學校。"), "明天我要去學校。")
        )) {
            val regions = Grouping.group(texts.mapIndexed { i, text -> line(text, 10f + 22f * i) },
                TranslationLanguages.lineSeparator(source))
            assertEquals(1, regions.size)
            assertEquals(expected, regions.single().sourceText)
        }
    }

    @Test fun incompleteOcrBubbleKeepsItsOriginalTextWithoutTranslationOrInpainting() = runBlocking<Unit> {
        val original = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        try {
            val translator = object : Translator {
                override suspend fun translate(queries: List<String>): List<String> = error("Incomplete bubble must be retained")
            }
            ResumablePipeline(
                detect = { Detection(listOf(line("", 10f), line("", 32f)),
                    Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)) },
                recognize = { _, lines ->
                    lines[0].text = TranslationOcrText.clean("Hello")
                    lines[1].text = TranslationOcrText.clean("world<UNK>")
                },
                inpaint = { _, _, _ -> error("Incomplete bubble must not be erased") },
                translator = translator, cfg = TranslationOptions(source = "en").engineConfig(), release = {}, warm = {},
                sourceSeparator = " ",
            ).use { pipeline ->
                pipeline.prepare(original).use { prepared ->
                    assertTrue(prepared.regions.isEmpty())
                    assertTrue(pipeline.translatePrepared(original, prepared, false) is PageResult.Skipped)
                }
            }
        } finally { original.recycle() }
    }

    @Test fun pipelineCarriesMultilineSourceThroughTranslationAndRetainedAnalysis() = runBlocking<Unit> {
        val original = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        try {
            val translator = object : Translator {
                override suspend fun translate(queries: List<String>): List<String> {
                    assertEquals(listOf("Hello world!"), queries)
                    return listOf("你好，世界！")
                }
            }
            ResumablePipeline(
                detect = { Detection(listOf(line("", 10f), line("", 32f)),
                    Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)) },
                recognize = { _, lines -> lines[0].text = "Hello"; lines[1].text = "world!" },
                inpaint = { page, _, _ -> page.copy(Bitmap.Config.ARGB_8888, true) },
                translator = translator, cfg = TranslationOptions(source = "en").engineConfig(), release = {}, warm = {},
                sourceSeparator = " ", translationIdentity = "english", overlapInpainting = false,
            ).use { pipeline ->
                pipeline.prepare(original).use { prepared ->
                    assertEquals("Hello world!", prepared.regions.single().sourceText)
                    val result = pipeline.translatePrepared(original, prepared, false) as PageResult.Translated
                    try {
                        assertEquals("Hello world!", prepared.regions.single().sourceText)
                        assertEquals("你好，世界！", prepared.regions.single().translatedText)
                        assertEquals(0, prepared.translationResume("english")!!.missingCount)
                        val joined = TextRegion(prepared.regions.single().lines, "h")
                        PreparedPage(Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888), listOf(joined), 2, 0, 0).use {
                            assertFalse(it.restoreTranslations("english", prepared.translationResume("english")!!))
                        }
                    } finally { result.page.recycle() }
                }
            }
        } finally { original.recycle() }
    }
}
