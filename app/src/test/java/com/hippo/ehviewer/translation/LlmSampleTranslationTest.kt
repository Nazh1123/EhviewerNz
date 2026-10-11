package com.hippo.ehviewer.translation

import android.app.Application
import com.hippo.ehviewer.translation.engine.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class LlmSampleTranslationTest {
    @Test fun nativeSamplesSharePlainNumberedThinkingAndFenceHandling() = runBlocking<Unit> {
        val source = "明日は学校へ行きます。"
        val answer = "明天我要去学校。\n一起走吧。😀"
        val usage = Usage(20, 12)
        for (raw in listOf(answer, "<think>reasoning</think>\n$answer", "prefilled reasoning</think>$answer",
            "<|1|>$answer", "```text\n<|1|>$answer\n```", "```\n$answer\n```")) {
            val expected = LlmSampleTranslation.parse(source, raw, usage)
            assertNull(raw, expected.error)
            assertEquals(listOf(answer), expected.translations)
            assertEquals(usage, expected.usage)
            NativeTranslator(TranslationOptions()) { messages ->
                assertEquals(LlmSampleTranslation.messages(TranslationOptions(), source).toString(), messages.toString())
                raw to usage
            }.use { assertEquals(expected, it.translateSample(source)) }
        }
    }

    @Test fun invalidSamplesKeepTheirSourceAndRemainRetryableForBothBackends() = runBlocking<Unit> {
        val source = "原文"
        val usage = Usage(20, 4)
        for (raw in listOf("", " ", "<think>unfinished", "<think>reasoning</think>", "```\nunfinished",
            "<|1", "<|1|>", "<|2|>wrong", "<|1|>one<|1|>two", "<|1|>one<|2|>two")) {
            val expected = LlmSampleTranslation.parse(source, raw, usage)
            assertNotNull(raw, expected.error)
            assertEquals(listOf(source), expected.translations)
            assertEquals(setOf(0), expected.missingIndices)
            assertEquals(usage, expected.usage)
            NativeTranslator(TranslationOptions()) { _ -> raw to usage }
                .use { assertEquals(expected, it.translateSample(source)) }
        }
    }

    @Test fun samplePromptKeepsAutomaticSourceAndTraditionalScriptSelection() {
        val options = TranslationOptions(source = "auto", target = "zh-TW")
        val messages = LlmSampleTranslation.messages(options, "source")
        assertEquals(1, messages.length())
        val message = messages.getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val prompt = message.getString("content")
        assertTrue(prompt.contains(options.engineConfig().translator.targetScriptInstruction()))
        assertTrue(prompt.endsWith("Translate the following text into Traditional Chinese (Taiwan), output only translation:\n\nsource"))
        assertFalse(prompt.contains("<|1|>"))
    }

    @Test fun sampleStreamingGuardAllowsWrappedAnswersAndStillStopsRepetition() {
        val source = listOf("原文")
        val fenced = "```text\n<|1|>译文\n```"
        assertTrue(NativeTranslationResponse.generationFailure(fenced, source))
        assertFalse(NativeTranslationResponse.generationFailure(fenced, source, validateRegionMarkers = false))
        assertNull(LlmSampleTranslation.parse(source.single(), fenced, null).error)
        assertTrue(NativeTranslationResponse.generationFailure("译".repeat(24), source, validateRegionMarkers = false))
    }
}
