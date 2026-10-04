package com.hippo.ehviewer.translation

import kotlinx.coroutines.runBlocking
import com.hippo.ehviewer.translation.engine.LlmBatching
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.TranslationOutputLimitException
import com.hippo.ehviewer.translation.engine.Usage
import org.junit.Assert.*
import org.junit.Test

class LlmBatchingTest {
    @Test fun rejectedInputsTerminateAtEachRegionAndPreserveTheirPositions() = runBlocking<Unit> {
        var calls = 0
        val source = listOf("a", "b", "c", "d", "e")
        val result = LlmBatching.translate(source) { calls++; null }
        assertEquals(source, result.translations)
        assertEquals(source.indices.toSet(), result.missingIndices)
        assertNotNull(result.error)
        assertEquals(2 * source.size - 1, calls)
        assertNull(result.usage)
    }

    @Test fun failurePositionsAreOffsetWithoutGuessingFromUnchangedValidText() = runBlocking<Unit> {
        val result = LlmBatching.translate(listOf("a", "b", "c", "d")) { queries ->
            if (queries.size > 2) throw TranslationOutputLimitException(usage = Usage(100, 1000))
            if (queries.first() == "a") LlmTranslator.TranslateResult(listOf("a", "译文b"), Usage(20, 5))
            else LlmTranslator.TranslateResult(listOf("译文c", "d"), Usage(20, 5), "Parsed 1/2 regions", missingIndices = setOf(1))
        }
        assertEquals(listOf("a", "译文b", "译文c", "d"), result.translations)
        assertEquals(setOf(3), result.missingIndices)
        assertEquals(Usage(140, 1010), result.usage)
        assertNotNull(result.error)
    }

    @Test fun arbitraryInferenceFailuresAreNeverRetriedByMessageMatching() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                LlmBatching.translate(listOf("a", "b")) {
                    calls++
                    throw IllegalStateException("Translation was truncated")
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test fun aBlankPageNeverStartsInference() = runBlocking<Unit> {
        val source = listOf("", " ")
        assertEquals(source, LlmBatching.translate(source) { error("Unexpected inference") }.translations)
    }
}
