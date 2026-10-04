package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NativeTranslationCheckpointTest {
    @Test fun completedThinkingMustNotDisablePlainSingleRegionCompatibility() = runBlocking<Unit> {
        val raw = "<think><|1|></think>你好"
        assertFalse(NativeTranslationResponse.generationFailure(raw, listOf("こんにちは")))
        NativeTranslator(TranslationOptions()) { raw to Usage(20, 10) }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは"))
            assertEquals(emptySet<Int>(), result.missingIndices)
            assertEquals(listOf("你好"), result.translations)
        }
    }

    @Test fun inProgressThinkingMayMentionAValidRegionBeforeItsClosingTag() {
        assertFalse(NativeTranslationResponse.generationFailure(
            "<think>I should translate the greeting in <|1|>", listOf("こんにちは")))
        val thinking = "<think>Check <|1|>, <|1|> again, and <|number|>"
        for (end in thinking.indices)
            assertFalse(NativeTranslationResponse.generationFailure(thinking.take(end + 1), listOf("こんにちは")))
        assertTrue(NativeTranslationResponse.generationFailure(thinking + "</think>preamble <|1|>你好", listOf("こんにちは")))
        assertTrue(NativeTranslationResponse.generationFailure("<think>" + "哈".repeat(32), listOf("こんにちは")))
        assertEquals(setOf(0), NativeTranslationResponse.parse(listOf("こんにちは"), thinking, null).missingIndices)
    }

    @Test fun cancellingSecondFallbackMustRetainFirstCompletedFallback() = runBlocking<Unit> {
        val options = TranslationOptions(backend = TranslationBackend.NATIVE_LLM,
            nativeModelId = NativeModelCatalog.models.first().sha256)
        val page = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        var resume = false
        try {
            NativeTranslator(options) { _ ->
                if (resume) {
                    calls++
                    "谢谢" to Usage(20, 4)
                } else
                when (++calls) {
                    1 -> "unnumbered page" to Usage(20, 4)
                    2 -> "你好" to Usage(20, 4)
                    else -> { secondStarted.complete(Unit); awaitCancellation() }
                }
            }.use { translator ->
                val selected = GallerySourceTranslator({ options }) { translator }
                ResumablePipeline(detect = { error("Already prepared") },
                    recognize = { _, _ -> error("Already prepared") },
                    inpaint = { input, _, _ -> input.copy(Bitmap.Config.ARGB_8888, true) },
                    translator = selected, cfg = EngineConfig(), release = {}, warm = {},
                    translationIdentity = options.cacheIdentity(),
                    translationBatchSize = TranslationEngineFactory.translationBatchSize(options)).use { pipeline ->
                    val regions = listOf("こんにちは", "ありがとう").mapIndexed { index, text ->
                        val y = 10f + index * 45f
                        TextRegion(listOf(TextLine(listOf(Pt(10f, y), Pt(90f, y),
                            Pt(90f, y + 25f), Pt(10f, y + 25f)), 1f).apply { this.text = text }), "h")
                    }
                    PreparedPage(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), regions, 2, 0, 0).use { prepared ->
                        val worker = launch { pipeline.translatePrepared(page, prepared, false) }
                        withTimeout(5000) { secondStarted.await() }
                        worker.cancelAndJoin()
                        assertEquals(3, calls)
                        assertEquals(mapOf(0 to "你好"), prepared.translationResume(options.cacheIdentity())!!.translations)
                        resume = true
                        val result = pipeline.translatePrepared(page, prepared, true) as PageResult.Translated
                        result.page.recycle()
                        assertEquals(4, calls)
                        assertEquals(listOf("你好", "谢谢"), prepared.regions.map { it.translatedText })
                    }
                }
            }
        } finally { page.recycle() }
    }

    @Test fun checkpointsMapNestedSplitsBlanksAndPreservedCandidatesByIndex() = runBlocking<Unit> {
        val source = listOf("a", "", "ん。", "a", "b", "c", "d")
        val checkpoints = linkedMapOf<Int, String>()
        NativeTranslator(TranslationOptions(source = "en")) { messages ->
            val content = messages.getJSONObject(1).getString("content")
            if ("<|2|>" in content) null
            else "译文" + content.substringAfter('>') to Usage(10, 2)
        }.use { translator ->
            val result = translator.translateDetailed(source) { checkpoints.putAll(it) }
            assertEquals(mapOf(0 to "译文a", 2 to "ん。", 3 to "译文a", 4 to "译文b", 5 to "译文c", 6 to "译文d"), checkpoints)
            assertEquals(source.indices.map { checkpoints[it] ?: source[it] }, result.translations)
            assertEquals(Usage(50, 10), result.usage)
            assertTrue(result.missingIndices.isEmpty())
        }
    }

    @Test fun ambiguousPrefixesNeverReachCheckpointsAndFailedRetriesStayMissing() = runBlocking<Unit> {
        val checkpoints = linkedMapOf<Int, String>()
        NativeTranslator(TranslationOptions()) { messages ->
            val content = messages.getJSONObject(1).getString("content")
            when {
                "<|2|>" in content -> "<|1|>merged and shifted" to null
                content.endsWith("a") -> {
                    assertTrue(checkpoints.isEmpty())
                    "译文a" to null
                }
                else -> {
                    assertEquals(mapOf(0 to "译文a"), checkpoints)
                    "" to null
                }
            }
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "b")) { checkpoints.putAll(it) }
            assertEquals(mapOf(0 to "译文a"), checkpoints)
            assertEquals(setOf(1), result.missingIndices)
        }
    }

    @Test fun completedTruncatedPrefixIsSavedBeforeCancelledRetry() = runBlocking<Unit> {
        val checkpoints = linkedMapOf<Int, String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if ("<|2|>" in messages.getJSONObject(1).getString("content"))
                throw NativeGenerationStopped("<|1|>译文a<|2|>unfinished", Usage(20, 192), true)
            assertEquals(mapOf(0 to "译文a"), checkpoints)
            throw CancellationException("Leave during tail retry")
        }.use { translator ->
            try {
                translator.translateDetailed(listOf("a", "b")) { checkpoints.putAll(it) }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(mapOf(0 to "译文a"), checkpoints)
        }
    }
}
