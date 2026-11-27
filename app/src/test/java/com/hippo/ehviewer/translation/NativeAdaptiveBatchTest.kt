package com.hippo.ehviewer.translation

import android.app.Application
import com.hippo.ehviewer.translation.engine.NativeSampling
import com.hippo.ehviewer.translation.engine.Usage
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
        assertEquals(2, policy.batchSize(3) { 180 })
        assertEquals(1, policy.batchSize(2) { if (it == 0) 10000 else 1 })
        assertEquals(1, policy.batchSize(1) { error("No need to count a single remaining region") })
        assertEquals(8, policy.batchSize(30) { 1 })
    }

    @Test fun repeatedFailuresShrinkButPrimarySuccessesEventuallyProbeLargerBatches() {
        val policy = NativeBatchPolicy()
        for (size in listOf(8, 4, 2)) {
            assertEquals(size, policy.batchSize(20) { 1 })
            policy.protocolFailed(size)
        }
        repeat(7) { policy.succeeded(1) }
        assertEquals(1, policy.batchSize(20) { error("No need to count when limited to one region") })
        policy.succeeded(1)
        assertEquals(2, policy.batchSize(20) { 1 })
        repeat(3) { policy.succeeded(2) }
        assertEquals(3, policy.batchSize(20) { 1 })
        policy.protocolFailed(2)
        assertEquals(1, policy.batchSize(20) { 1 })
    }

    @Test fun contextAndOutputLimitsDoNotShrinkTheNextPage() = runBlocking<Unit> {
        for (contextLimit in listOf(true, false)) {
            val sizes = mutableListOf<Int>()
            NativeTranslator(TranslationOptions()) { messages ->
                val count = Regex("<\\|\\d+\\|>").findAll(messages.getJSONObject(1).getString("content")).count()
                sizes.add(count)
                if (sizes.size == 1) {
                    if (contextLimit) null
                    else throw NativeGenerationStopped("unfinished", Usage(30, 192), true)
                } else {
                    (1..count).joinToString("\n") { "<|$it|>译文" } to null
                }
            }.use { translator ->
                assertTrue(translator.translateDetailed(listOf("a", "b")).missingIndices.isEmpty())
                assertTrue(translator.translateDetailed(listOf("c", "d")).missingIndices.isEmpty())
            }
            assertEquals(listOf(2, 1, 1, 2), sizes)
        }
    }

    @Test fun nestedContextRejectionsStayKnownUntilModelUnload() = runBlocking<Unit> {
        val source = listOf("a", "b", "c", "d")
        val sizes = mutableListOf<Int>()
        NativeTranslator(TranslationOptions()) { messages ->
            val count = Regex("<\\|\\d+\\|>").findAll(messages.getJSONObject(1).getString("content")).count()
            sizes.add(count)
            if (count > 1) null else "<|1|>译文" to null
        }.use { translator ->
            assertTrue(translator.translateDetailed(source).missingIndices.isEmpty())
            assertEquals(listOf(4, 2, 1, 1, 2, 1, 1), sizes)
            sizes.clear()
            assertTrue(translator.translateDetailed(source).missingIndices.isEmpty())
            assertEquals(listOf(1, 1, 1, 1), sizes)
            translator.unloadModel()
            sizes.clear()
            assertTrue(translator.translateDetailed(source).missingIndices.isEmpty())
            assertEquals(listOf(4, 2, 1, 1, 2, 1, 1), sizes)
        }
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
