package com.hippo.ehviewer.translation

import android.app.Application
import kotlinx.coroutines.*
import com.hippo.ehviewer.translation.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class LlmProtocolTest {
    @Test fun duplicateOrTruncatedMarkersRemainRetryable() = runBlocking<Unit> {
        for (response in listOf("<|1|>甲<|1|>乙", "<|1|甲", "<|1")) {
            val result = LlmTranslator(transport = { response to null }).translateDetailed(listOf("原文"))
            assertEquals(listOf("原文"), result.translations)
            assertEquals(setOf(0), result.missingIndices)
        }
    }
    @Test fun readerPromptPreservesRegionMarkersAndTargetLanguage() = runBlocking<Unit> {
        val config = TranslatorConfig(toLangName = "English")
        val translator = LlmTranslator(config, transport = { messages ->
            assertEquals(listOf("system", "user"),
                (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.contains("dialogue and captions"))
            assertTrue(system.contains("terminology consistent"))
            assertTrue(system.contains("English"))
            assertEquals("<|1|>こんにちは\n<|2|>ありがとう", messages.getJSONObject(1).getString("content"))
            "<|2>|Thanks\n<|1>Hello" to Usage(100, 20)
        })
        val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
        assertEquals(listOf("Hello", "Thanks"), result.translations)
        assertNull(result.error)
        assertEquals(Usage(100, 20), result.usage)
    }

    @Test fun unexpectedOrEmptyIdsCannotHideAMissingExpectedRegion() = runBlocking<Unit> {
        for (response in listOf("<|1|>你好\n<|99|>extra", "<|1|>你好\n<|2|>   ")) {
            val translator = LlmTranslator(transport = { response to null })
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("你好", "ありがとう"), result.translations)
            assertNotNull(result.error)
        }
    }

    @Test fun injectedTransportCancellationIsNotConvertedToSourceText() = runBlocking<Unit> {
        val translator = LlmTranslator(transport = { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { runBlocking { translator.translateDetailed(listOf("こんにちは")) } }
    }

    @Test fun numberedSegmentsPreserveWrappedLinesAndCanShareOneLine() = runBlocking<Unit> {
        for (response in listOf(
            "<|1|>你好，\n明天见。\n<|2|>谢谢。",
            "```text\n<|1|>你好，\n明天见。<|2|>谢谢。\n```",
        )) {
            val translator = LlmTranslator(transport = { response to null })
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("你好，\n明天见。", "谢谢。"), result.translations)
            assertNull(result.error)
            assertTrue(result.missingIndices.isEmpty())
        }
    }

    @Test fun missingPositionsDoNotDependOnWhetherAValidTranslationEqualsItsSource() = runBlocking<Unit> {
        val translator = LlmTranslator(transport = { "<|2|>OK" to null })
        val result = translator.translateDetailed(listOf("こんにちは", "OK", ""))
        assertEquals(setOf(0), result.missingIndices)
        assertEquals(listOf("こんにちは", "OK", ""), result.translations)
        assertNotNull(result.error)
    }

    @Test fun numberedApiProtocolDoesNotAssignAnUnstructuredParagraphToMultipleRegions() = runBlocking<Unit> {
        val translator = LlmTranslator(transport = { "你好，谢谢。" to null })
        val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
        assertEquals(setOf(0, 1), result.missingIndices)
        assertEquals(listOf("こんにちは", "ありがとう"), result.translations)
        assertEquals("Parsed 0/2 regions", result.error)
    }
}
