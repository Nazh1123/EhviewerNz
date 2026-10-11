package com.hippo.ehviewer.translation

import android.app.Application
import com.hippo.ehviewer.translation.engine.NativeSampling
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NativeAdaptiveBatchTest {
    @Test fun tokenLimitNeverDropsAnOversizedRegion() {
        val policy = NativeBatchPolicy()
        assertEquals(2, policy.batchSize(listOf(180, 180, 180), 0))
        assertEquals(1, policy.batchSize(listOf(10000, 1), 0))
        assertEquals(1, policy.batchSize(listOf(10000, 1), 1))
        assertEquals(8, policy.batchSize(List(30) { 1 }, 0))
    }

    @Test fun repeatedFailuresShrinkButPrimarySuccessesEventuallyProbeLargerBatches() {
        val policy = NativeBatchPolicy()
        val tokens = List(20) { 1 }
        for (size in listOf(8, 4, 2)) {
            assertEquals(size, policy.batchSize(tokens, 0))
            policy.failed(size)
        }
        repeat(7) { policy.succeeded(1) }
        assertEquals(1, policy.batchSize(tokens, 0))
        policy.succeeded(1)
        assertEquals(2, policy.batchSize(tokens, 0))
        repeat(3) { policy.succeeded(2) }
        assertEquals(3, policy.batchSize(tokens, 0))
        policy.failed(2)
        assertEquals(1, policy.batchSize(tokens, 0))
    }

    @Test fun largeRequestsCheckpointChunksWithOriginalOffsetsAndRepeatedSources() = runBlocking<Unit> {
        val sources = List(19) { "same" }.toMutableList().apply { add(5, "") }
        val sizes = mutableListOf<Int>()
        val saved = linkedMapOf<Int, String>()
        NativeTranslator(TranslationOptions()) { messages ->
            val count = Regex("<\\|\\d+\\|>").findAll(messages.getJSONObject(1).getString("content")).count()
            sizes.add(count)
            (1..count).joinToString("\n") { "<|$it|>译文" } to null
        }.use { translator ->
            val result = translator.translateDetailed(sources) { saved.putAll(it) }
            assertEquals(listOf(8, 8, 3), sizes)
            assertEquals(sources.indices.filter { it != 5 }.toSet(), saved.keys)
            assertTrue(result.missingIndices.isEmpty())
            assertEquals(sources.map { if (it.isBlank()) "" else "译文" }, result.translations)
        }
    }

    @Test fun failedPrefixIsNeverCommittedAndFallbacksDoNotImmediatelyRestoreBatching() = runBlocking<Unit> {
        val sizes = mutableListOf<Int>()
        NativeTranslator(TranslationOptions()) { messages ->
            val count = Regex("<\\|\\d+\\|>").findAll(messages.getJSONObject(1).getString("content")).count()
            sizes.add(count)
            (if (count > 1) "<|1|>merged" else "<|1|>译文") to null
        }.use { translator ->
            repeat(2) {
                val saved = linkedMapOf<Int, String>()
                val result = translator.translateDetailed(listOf("a", "b")) { saved.putAll(it) }
                assertEquals(mapOf(0 to "译文", 1 to "译文"), saved)
                assertTrue(result.missingIndices.isEmpty())
                translator.unloadModel()
            }
            assertEquals(listOf(2, 1, 1, 1, 1), sizes)
        }
    }

    @Test fun samplingProfilesUseContentIdentityRatherThanUserSuppliedFileNames() {
        assertEquals(NativeSampling.HY_TRANSLATION, NativeTranslator.sampling(NativeModelCatalog.models[0].sha256))
        assertEquals(NativeSampling.HY_TRANSLATION,
            NativeTranslator.sampling("4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7"))
        assertEquals(NativeSampling.HY_MT2_MANGA, NativeTranslator.sampling(NativeModelCatalog.models[1].sha256))
        assertEquals(NativeSampling.GREEDY, NativeTranslator.sampling("HY-MT1.5-1.8B-Q4_K_M.gguf"))
        assertEquals(NativeSampling.GREEDY, NativeTranslator.sampling("unknown-hash"))
    }

    @Test fun cancellationInNextChunkKeepsTheFirstChunkAndNeverStartsTheThird() = runBlocking<Unit> {
        val nextStarted = CompletableDeferred<Unit>()
        val saved = linkedMapOf<Int, String>()
        var calls = 0
        NativeTranslator(TranslationOptions()) { _ ->
            if (++calls == 1) (1..8).joinToString("\n") { "<|$it|>译文" } to null
            else { nextStarted.complete(Unit); awaitCancellation() }
        }.use { translator ->
            val worker = launch { translator.translateDetailed(List(19) { "same" }) { saved.putAll(it) } }
            withTimeout(5000) { nextStarted.await() }
            worker.cancelAndJoin()
            assertEquals(2, calls)
            assertEquals((0..7).toSet(), saved.keys)
        }
    }
}
