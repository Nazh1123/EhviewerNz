package com.hippo.ehviewer.translation

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.Usage

class NativeTranslatorTest {
    @Test fun nativePageUsesOneBatchWhenItsTokenBudgetFits() = runBlocking<Unit> {
        val source = listOf("こんにちは", "ありがとう", "明日")
        val calls = mutableListOf<List<String>>()
        val result = NativeTranslator.translateBatches(source) { batch ->
            calls.add(batch)
            LlmTranslator.TranslateResult(listOf("你好", "谢谢", "明天"), Usage(90, 12))
        }
        assertEquals(listOf(source), calls)
        assertEquals(listOf("你好", "谢谢", "明天"), result.translations)
        assertEquals(90, result.usage!!.promptTokens)
    }

    @Test fun nativePageSplitsOnlyRejectedBatchesAndKeepsOrderingAndUsage() = runBlocking<Unit> {
        val source = listOf("a", "b", "c", "d", "e")
        val calls = mutableListOf<List<String>>()
        val result = NativeTranslator.translateBatches(source) { batch ->
            calls.add(batch)
            if (batch.size > 2) null else LlmTranslator.TranslateResult(batch.map { "translated-$it" }, Usage(10, 3))
        }
        assertEquals(source, calls.first())
        assertEquals(source.map { "translated-$it" }, result.translations)
        assertEquals(Usage(30, 9), result.usage)
        assertTrue(calls.contains(listOf("a", "b")))
        assertTrue(calls.contains(listOf("d", "e")))
        val failed = NativeTranslator.translateBatches(listOf("too long")) { null }
        assertEquals(listOf("too long"), failed.translations)
        assertEquals(setOf(0), failed.missingIndices)
        assertNotNull(failed.error)
    }

    @Test fun rejectsIncompleteOrEmptyThinkingAndPreservesUnicode() {
        assertEquals("明天去学校。😀", NativeTranslator.cleanOutput("<think>reasoning</think> 明天去学校。😀 "))
        assertEquals("译文", NativeTranslator.cleanOutput("prefilled reasoning</think>译文"))
        for (text in listOf("", "  ", "<think>unfinished", "<think>only reasoning</think>")) {
            assertThrows(IllegalStateException::class.java) { NativeTranslator.cleanOutput(text) }
        }
    }
}
