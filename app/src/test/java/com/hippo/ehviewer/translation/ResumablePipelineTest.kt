package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.*
import li.joye.yakuyomi.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyBitmap

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ResumablePipelineTest {
    @Test fun persistedPartialCheckpointRetriesOnlyMissingRegionsAfterPreparationIsRecreated() = runBlocking {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var calls = 0
        val translator = object : DetailedTranslator {
            override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult {
                calls++
                return if (calls == 1) {
                    assertEquals(listOf("こんにちは", "ありがとう"), queries)
                    LlmTranslator.TranslateResult(listOf("你好", queries[1]), error = "Missing region", missingIndices = setOf(1))
                } else {
                    assertEquals(listOf("ありがとう"), queries)
                    LlmTranslator.TranslateResult(listOf("谢谢"))
                }
            }
        }
        try {
            Stages().engine("checkpoint", batchSize = Int.MAX_VALUE, detailed = translator) { error("Unexpected") }.use { engine ->
                val resume = twoRegions(source).use { prepared ->
                    val result = engine.translatePrepared(source, prepared, false) as PageResult.Translated
                    result.page.recycle()
                    prepared.translationResume("checkpoint")!!.also { assertEquals(1, it.missingCount) }
                }
                twoRegions(source).use { prepared ->
                    assertTrue(prepared.restoreTranslations("checkpoint", resume))
                    val result = engine.translatePrepared(source, prepared, false) as PageResult.Translated
                    result.page.recycle()
                    assertEquals(0, prepared.translationResume("checkpoint")!!.missingCount)
                    assertEquals(2, calls)
                }
                twoRegions(source).use { prepared ->
                    prepared.regions[0].lines[0].text = "changed OCR"
                    assertFalse(prepared.restoreTranslations("checkpoint", resume))
                    assertNull(prepared.translationResume("checkpoint"))
                }
            }
        } finally { source.recycle() }
    }

    @Implements(Bitmap::class)
    class TrackingBitmap : ShadowLegacyBitmap() {
        @Implementation override fun copy(config: Bitmap.Config, mutable: Boolean): Bitmap =
            super.copy(config, mutable).also { copies.add(it) }
        companion object { val copies = mutableListOf<Bitmap>() }
    }

    @Test @Config(shadows = [TrackingBitmap::class])
    fun renderProgressFailureRecyclesTheUnreturnedOutputAndKeepsPreparation() = runBlocking {
        for (cancel in listOf(false, true)) {
            TrackingBitmap.copies.clear()
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                Stages().engine { listOf("你好") }.use { engine ->
                    engine.prepare(source).use { prepared ->
                        try {
                            val result = engine.translatePrepared(source, prepared, false, { stage, _ ->
                                if (stage == TranslationStage.RENDER) {
                                    if (cancel) throw CancellationException("Cancelled after render")
                                    else error("Progress observer failed")
                                }
                            })
                            assertFalse(cancel)
                            assertTrue(result is PageResult.Failed)
                        } catch (_: CancellationException) { assertTrue(cancel) }
                        assertEquals(2, TrackingBitmap.copies.size)
                        assertSame(prepared.cleaned, TrackingBitmap.copies.first())
                        assertFalse(prepared.cleaned!!.isRecycled)
                        assertTrue(TrackingBitmap.copies.last().isRecycled)
                    }
                }
            } finally { source.recycle(); TrackingBitmap.copies.clear() }
        }
    }

    private class Stages {
        var detects = 0; var ocrs = 0; var inpaints = 0
        var lastMask: Bitmap? = null
        var beforeInpaint: suspend () -> Unit = {}
        fun engine(identity: String? = null, overlap: Boolean = true, batchSize: Int = 1,
                   detailed: DetailedTranslator? = null, analysis: Boolean = false, overlapLayout: Boolean = false,
                   translate: suspend (List<String>) -> List<String>) = ResumablePipeline(
            detect = { page ->
                detects++
                Detection(listOf(TextLine(listOf(Pt(8f, 8f), Pt(90f, 8f), Pt(90f, 40f), Pt(8f, 40f)), 1f)),
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { lastMask = it })
            },
            recognize = { _, lines -> ocrs++; lines.forEach { it.text = "こんにちは" } },
            inpaint = { page, _, _ -> inpaints++; beforeInpaint(); page.copy(Bitmap.Config.ARGB_8888, true) },
            translator = detailed ?: object : Translator { override suspend fun translate(queries: List<String>) = translate.invoke(queries) },
            cfg = EngineConfig(), release = {}, warm = {},
            translationIdentity = identity, overlapInpainting = overlap,
            translationBatchSize = batchSize, retainAnalysis = analysis, overlapLayout = overlapLayout,
        )
    }

    @Test fun localLayoutFinishesWhileInpaintingWaitsAndCompositionWaitsForBoth() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val inpaintStarted = CompletableDeferred<Unit>()
        val finishInpaint = CompletableDeferred<Unit>()
        val layoutReady = CompletableDeferred<Unit>()
        val oldSink = EngineTrace.sink
        EngineTrace.sink = { if (it == "resume.layout.exit") layoutReady.complete(Unit) }
        stages.beforeInpaint = { inpaintStarted.complete(Unit); finishInpaint.await() }
        try {
            stages.engine("local", overlap = false, overlapLayout = true) {
                assertEquals("Language inference must finish before image work", 0, stages.inpaints)
                listOf("你好")
            }.use { engine ->
                engine.prepare(source).use { prepared ->
                    val completed = java.util.concurrent.ConcurrentHashMap<TranslationStage, Float>()
                    val worker = async { engine.translatePrepared(source, prepared, false,
                        { stage, value -> completed[stage] = value }) }
                    try {
                        withTimeout(5000) { inpaintStarted.await(); layoutReady.await() }
                        assertFalse(worker.isCompleted)
                        assertFalse(completed.containsKey(TranslationStage.RENDER))
                    } finally { finishInpaint.complete(Unit) }
                    val result = withTimeout(5000) { worker.await() } as PageResult.Translated
                    assertEquals(1f, completed[TranslationStage.RENDER])
                    assertNotSame(prepared.cleaned, result.page)
                    result.page.recycle()
                }
            }
        } finally { finishInpaint.complete(Unit); EngineTrace.sink = oldSink; source.recycle() }
    }

    @Test fun localTranslationFailureDoesNotStartLazyInpainting() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        try {
            stages.engine(overlap = false, overlapLayout = true) { error("Translation failed") }.use { engine ->
                engine.prepare(source).use { prepared ->
                    assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
                    assertEquals(0, stages.inpaints)
                    assertNull(prepared.cleaned)
                }
            }
        } finally { source.recycle() }
    }

    @Test fun localInpaintFailureRetainsTranslationForRetry() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        var calls = 0
        stages.beforeInpaint = { error("Image failed") }
        try {
            stages.engine("local", overlap = false, overlapLayout = true) { calls++; listOf("你好") }.use { engine ->
                engine.prepare(source).use { prepared ->
                    assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
                    assertNull(prepared.cleaned)
                    stages.beforeInpaint = {}
                    val result = engine.translatePrepared(source, prepared, true) as PageResult.Translated
                    assertEquals(1, calls)
                    assertEquals(2, stages.inpaints)
                    result.page.recycle()
                }
            }
        } finally { source.recycle() }
    }

    @Test fun localCancellationWaitsForInpaintingAndReusesCompletedWork() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val inpaintStarted = CompletableDeferred<Unit>()
        val finishInpaint = CompletableDeferred<Unit>()
        var calls = 0
        stages.beforeInpaint = { inpaintStarted.complete(Unit); finishInpaint.await() }
        try {
            stages.engine("local", overlap = false, overlapLayout = true) { calls++; listOf("你好") }.use { engine ->
                engine.prepare(source).use { prepared ->
                    val worker = async { engine.translatePrepared(source, prepared, false) }
                    try {
                        withTimeout(5000) { inpaintStarted.await() }
                        worker.cancel()
                        yield()
                        assertFalse("Native buffers are still in use", worker.isCompleted)
                    } finally { finishInpaint.complete(Unit) }
                    withTimeout(5000) { worker.join() }
                    assertTrue(worker.isCancelled)
                    assertNotNull(prepared.cleaned)
                    val result = engine.translatePrepared(source, prepared, true) as PageResult.Translated
                    assertEquals(1, calls)
                    assertEquals(1, stages.inpaints)
                    result.page.recycle()
                }
            }
        } finally { finishInpaint.complete(Unit); source.recycle() }
    }

    @Test fun obsoletePreparationDoesNotLoadTheDetector() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val request = TranslationPageRequest(0, false).apply { supersede() }
        try {
            stages.engine { error("Unexpected translation") }.use { engine ->
                try {
                    withContext(NonCancellable) { engine.prepare(source, checkRelevant = request::ensureRelevant) }
                    fail("Obsolete request began preparation")
                } catch (_: SupersededTranslationPage) { }
                assertEquals(0, stages.detects)
                assertEquals(0, stages.ocrs)
                assertNull(stages.lastMask)
            }
        } finally { source.recycle() }
    }

    @Test fun pageChangeAfterDetectionDoesNotLoadOcrAndReleasesTheMask() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val request = TranslationPageRequest(0, false)
        try {
            stages.engine { error("Unexpected translation") }.use { engine ->
                try {
                    withContext(NonCancellable) {
                        engine.prepare(source, { stage, _ ->
                            if (stage == TranslationStage.DETECT) request.supersede()
                        }, request::ensureRelevant)
                    }
                    fail("Obsolete detection proceeded to OCR")
                } catch (_: SupersededTranslationPage) { }
                assertEquals(1, stages.detects)
                assertEquals(0, stages.ocrs)
                assertTrue(stages.lastMask!!.isRecycled)
                assertFalse(source.isRecycled)
            }
        } finally { source.recycle() }
    }

    @Test fun stoppingDuringDetectionUsesTheOriginalJobInsideNonCancellableCleanup() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        try {
            stages.engine { error("Unexpected translation") }.use { engine ->
                val worker = async {
                    val owner = currentCoroutineContext().job
                    withContext(NonCancellable) {
                        engine.prepare(source, { stage, _ ->
                            if (stage == TranslationStage.DETECT) owner.cancel()
                        }, { owner.ensureActive() })
                    }
                }
                try {
                    worker.await()
                    fail("Stopped worker proceeded to OCR")
                } catch (_: CancellationException) { }
                assertEquals(1, stages.detects)
                assertEquals(0, stages.ocrs)
                assertTrue(stages.lastMask!!.isRecycled)
            }
        } finally { source.recycle() }
    }

    @Test fun wholePageTranslationCommitsAllRegionsBeforeProgressAndSurvivesRetry() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        var calls = 0
        val progress = mutableListOf<Float>()
        stages.engine("page-llm", batchSize = Int.MAX_VALUE) { queries ->
            calls++
            assertEquals(listOf("こんにちは", "ありがとう"), queries)
            listOf("你好", "谢谢")
        }.use { engine ->
            val result = engine.translatePrepared(source, prepared, false, { stage, fraction ->
                if (stage == TranslationStage.TRANSLATE) progress.add(fraction)
            }) as PageResult.Translated
            assertTrue(progress.all { it == 1f })
            result.page.recycle()
            val restored = engine.translatePrepared(source, prepared, true) as PageResult.Translated
            assertEquals(1, calls)
            assertEquals(listOf("你好", "谢谢"), prepared.regions.map { it.translatedText })
            restored.page.recycle()
        }
        prepared.close(); source.recycle()
    }

    @Test fun llmParseFailureIsRetryableAndDoesNotBecomeASkippedPage() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        var valid = false
        val detailed = object : DetailedTranslator {
            override suspend fun translateDetailed(queries: List<String>) = if (valid)
                LlmTranslator.TranslateResult(listOf("你好", "谢谢"), Usage(70, 12))
            else LlmTranslator.TranslateResult(queries, Usage(70, 8), "Parsed 0/2 regions")
        }
        stages.engine("page-llm", batchSize = Int.MAX_VALUE, detailed = detailed) { error("Detailed interface lost") }.use { engine ->
            assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
            valid = true
            val result = engine.translatePrepared(source, prepared, true) as PageResult.Translated
            assertEquals(70, result.stats.promptTokens)
            assertEquals(12, result.stats.completionTokens)
            assertEquals(1, stages.inpaints)
            result.page.recycle()
        }
        prepared.close(); source.recycle()
    }

    @Test fun partialLlmResponseRepaintsMissingSourceAndReturnsIndependentAnalysis() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        val detailed = object : DetailedTranslator {
            override suspend fun translateDetailed(queries: List<String>) =
                LlmTranslator.TranslateResult(listOf("你好", queries[1]), Usage(90, 10), "Parsed 1/2 regions")
        }
        stages.engine("page-llm", batchSize = Int.MAX_VALUE, detailed = detailed, analysis = true) { error("Detailed interface lost") }.use { engine ->
            val result = engine.translatePrepared(source, prepared, false) as PageResult.Translated
            assertEquals(1, result.stats.kept)
            assertEquals(listOf("你好", "ありがとう"), result.analysis!!.regions.map { it.translatedText })
            assertNotSame(prepared.mask, result.analysis!!.mask)
            prepared.regions[0].translatedText = "changed"
            prepared.regions[0].lines[0].text = "changed source"
            prepared.close()
            assertFalse(result.analysis!!.mask.isRecycled)
            assertEquals("你好", result.analysis!!.regions[0].translatedText)
            assertEquals("こんにちは", result.analysis!!.regions[0].sourceText)
            result.analysis!!.mask.recycle(); result.page.recycle()
        }
        source.recycle()
    }

    @Test fun nativeCompatibilityFailureStillRendersTheValidRegionAndCachesOnlyThatRegion() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        var allowPlain = false
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            (if (messages.length() > 1) "<|2|>谢谢" else if (allowPlain) "你好" else "") to Usage(20, 5)
        }.use { translator ->
            stages.engine("native-partial", batchSize = Int.MAX_VALUE, detailed = translator, analysis = true) {
                error("Native adapter not called")
            }.use { engine ->
                val result = engine.translatePrepared(source, prepared, false) as PageResult.Translated
                assertEquals(1, result.stats.kept)
                assertEquals(listOf("こんにちは", "谢谢"), result.analysis!!.regions.map { it.translatedText })
                assertEquals(2, calls)
                result.analysis!!.mask.recycle(); result.page.recycle()
                allowPlain = true
                val retried = engine.translatePrepared(source, prepared, true) as PageResult.Translated
                assertEquals(3, calls)
                assertEquals(listOf("你好", "谢谢"), retried.analysis!!.regions.map { it.translatedText })
                retried.analysis!!.mask.recycle(); retried.page.recycle()
            }
        }
        prepared.close(); source.recycle()
    }

    @Test fun whollyOverBudgetNativePageRemainsFailedAndRetryable() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        var calls = 0
        NativeTranslator(TranslationOptions()) { _ -> calls++; null }.use { translator ->
            stages.engine("native-budget", batchSize = Int.MAX_VALUE, detailed = translator) {
                error("Native adapter not called")
            }.use { engine ->
                assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
                assertEquals(3, calls)
                assertTrue(engine.translatePrepared(source, prepared, true) is PageResult.Failed)
                assertEquals(6, calls)
            }
        }
        prepared.close(); source.recycle()
    }

    @Test fun explicitMissingIndicesKeepAnUnchangedButValidRegionOutOfTheRetry() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        var calls = 0
        val detailed = object : DetailedTranslator {
            override suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult {
                calls++
                return if (calls == 1) LlmTranslator.TranslateResult(queries,
                    error = "Parsed 1/2 regions", missingIndices = setOf(1))
                else {
                    assertEquals(listOf("ありがとう"), queries)
                    LlmTranslator.TranslateResult(listOf("谢谢"))
                }
            }
        }
        stages.engine("explicit-missing", batchSize = Int.MAX_VALUE, detailed = detailed) { error("Unexpected call") }.use { engine ->
            assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
            val retried = engine.translatePrepared(source, prepared, true) as PageResult.Translated
            assertEquals(1, retried.stats.kept)
            retried.page.recycle()
        }
        prepared.close(); source.recycle()
    }

    @Test fun serialInpaintingReleasesTheImageStageBeforeLanguageInferenceAndReusesItsResult() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val stages = Stages()
        var released = false
        var translations = 0
        stages.beforeInpaint = { started.complete(Unit); finish.await(); released = true }
        stages.engine(overlap = false) {
            assertTrue("Language inference overlapped the image stage", released)
            translations++
            listOf("你好")
        }.use { engine ->
            val prepared = engine.prepare(source)
            val task = async { engine.translatePrepared(source, prepared, false) }
            started.await(); yield()
            assertEquals(0, translations)
            finish.complete(Unit)
            val result = withTimeout(1000) { task.await() } as PageResult.Translated
            result.page.recycle()
            val restored = engine.translatePrepared(source, prepared, true) as PageResult.Translated
            assertEquals(1, stages.inpaints)
            assertEquals(2, translations)
            restored.page.recycle(); prepared.close()
        }
        source.recycle()
    }

    @Test fun failedSerialInpaintingNeverLoadsTheLanguageModel() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        stages.beforeInpaint = { throw IllegalStateException("Cannot allocate image workspace") }
        stages.engine(overlap = false) { fail("Language model started after image failure"); emptyList() }.use { engine ->
            val prepared = engine.prepare(source)
            assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
            assertNull(prepared.cleaned)
            prepared.close()
        }
        source.recycle()
    }

    private fun twoRegions(source: Bitmap): PreparedPage {
        val regions = listOf("こんにちは", "ありがとう").mapIndexed { index, text ->
            val top = 8f + index * 45f
            val line = TextLine(listOf(Pt(8f, top), Pt(90f, top), Pt(90f, top + 30f), Pt(8f, top + 30f)), 1f)
                .apply { this.text = text }
            TextRegion(listOf(line), "h")
        }
        return PreparedPage(Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888), regions, 2, 0, 0)
    }

    @Test fun schedulerPauseRetainsCompletedRegionsAndWaitsForNativeInpainting() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val nativeStarted = CompletableDeferred<Unit>()
        val nativeFinish = CompletableDeferred<Unit>()
        stages.beforeInpaint = { nativeStarted.complete(Unit); nativeFinish.await() }
        val calls = mutableListOf<String>()
        stages.engine("same-model-and-language") { queries ->
            calls.add(queries.single())
            nativeStarted.await()
            listOf(if (queries.single() == "こんにちは") "你好" else "谢谢")
        }.use { engine ->
            val prepared = twoRegions(source)
            val initialBytes = prepared.byteCount
            val paused = async {
                engine.translatePrepared(source, prepared, false, { stage, fraction ->
                    if (stage == TranslationStage.TRANSLATE && fraction == 0.5f) throw IllegalStateException("Yield")
                })
            }
            nativeStarted.await()
            yield()
            assertFalse("Yield must wait until inpainting safely returns", paused.isCompleted)
            nativeFinish.complete(Unit)
            assertTrue(paused.await() is PageResult.Failed)
            assertEquals(listOf("こんにちは"), calls)
            assertTrue(prepared.byteCount > initialBytes)
            val cache = PreparedPageCache()
            cache.put("source", prepared)
            val restored = cache.take("source")!!
            val progress = mutableListOf<Float>()
            val result = engine.translatePrepared(source, restored, true, { stage, fraction ->
                if (stage == TranslationStage.TRANSLATE) progress.add(fraction)
            }) as PageResult.Translated
            assertEquals(listOf("こんにちは", "ありがとう"), calls)
            assertEquals(listOf("你好", "谢谢"), restored.regions.map { it.translatedText })
            assertTrue(0.5f in progress)
            assertEquals(1, stages.inpaints)
            result.page.recycle()
            restored.close()
        }
        source.recycle()
    }

    @Test fun changingTranslationIdentityReusesPreprocessingButTranslatesEveryRegionAgain() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val prepared = twoRegions(source)
        stages.engine("zh-model-a") { listOf("旧译文") }.use { engine ->
            assertTrue(engine.translatePrepared(source, prepared, false, { stage, fraction ->
                if (stage == TranslationStage.TRANSLATE && fraction == 0.5f) throw IllegalStateException("Yield")
            }) is PageResult.Failed)
        }
        var calls = 0
        stages.engine("en-model-b") { calls++; listOf("new translation") }.use { engine ->
            val result = engine.translatePrepared(source, prepared, true) as PageResult.Translated
            assertEquals(2, calls)
            assertTrue(prepared.regions.all { it.translatedText == "new translation" })
            assertEquals(1, stages.inpaints)
            result.page.recycle()
        }
        prepared.close()
        source.recycle()
    }

    @Test fun failedRegionIsRetriedWhileCompletedRegionsRemainCached() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val calls = mutableListOf<String>()
        var fail = true
        stages.engine("same-model") { queries ->
            val query = queries.single()
            calls.add(query)
            if (query == "ありがとう" && fail) throw IllegalStateException("API unavailable")
            listOf(if (query == "こんにちは") "你好" else "谢谢")
        }.use { engine ->
            val prepared = twoRegions(source)
            assertTrue(engine.translatePrepared(source, prepared, false) is PageResult.Failed)
            fail = false
            val result = engine.translatePrepared(source, prepared, true) as PageResult.Translated
            assertEquals(listOf("こんにちは", "ありがとう", "ありがとう"), calls)
            assertEquals(listOf("你好", "谢谢"), prepared.regions.map { it.translatedText })
            result.page.recycle()
            prepared.close()
        }
        source.recycle()
    }

    @Test fun cancelledApiWaitsForStartedInpaintingThenReusesOcrAndBackgroundOnReturn() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val stages = Stages()
        val inpaintStarted = CompletableDeferred<Unit>()
        val inpaintFinish = CompletableDeferred<Unit>()
        val apiStarted = CompletableDeferred<Unit>()
        stages.beforeInpaint = { inpaintStarted.complete(Unit); inpaintFinish.await() }
        var request = TranslationPageRequest(0, false)
        var returning = false
        var calls = 0
        val cache = PreparedPageCache()
        stages.engine { queries ->
            calls++
            assertEquals(listOf("こんにちは"), queries)
            if (returning) listOf("你好") else request.apiCall { apiStarted.complete(Unit); awaitCancellation() }
        }.use { engine ->
            val prepared = engine.prepare(source)
            val first = async { engine.translatePrepared(source, prepared, false) { !request.isObsolete } }
            apiStarted.await(); inpaintStarted.await()
            request.supersede()
            yield()
            assertFalse("Must wait for native inpainting before reuse", first.isCompleted)
            inpaintFinish.complete(Unit)
            assertTrue(withTimeout(1000) { first.await() } is PageResult.Failed)
            assertNotNull(prepared.cleaned)
            cache.put("same-source", prepared)
            returning = true
            request = TranslationPageRequest(0, false)
            val restored = cache.take("same-source")!!
            val second = engine.translatePrepared(source, restored, true) { !request.isObsolete }
            assertTrue(second is PageResult.Translated)
            second as PageResult.Translated
            assertEquals(1, stages.detects); assertEquals(1, stages.ocrs); assertEquals(1, stages.inpaints)
            assertEquals(2, calls)
            assertEquals(0L, second.stats.detectMs); assertEquals(0L, second.stats.ocrMs); assertEquals(0L, second.stats.inpaintMs)
            assertNotSame(restored.cleaned, second.page)
            second.page.recycle()
            assertFalse(restored.mask.isRecycled)
            val background = restored.cleaned!!
            restored.close()
            assertTrue(background.isRecycled)
            assertFalse(source.isRecycled)
        }
        source.recycle()
    }

    @Test fun returningAfterOcrOnlySkipsDetectionAndRecognitionButStillInpaints() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        var calls = 0
        stages.engine { calls++; listOf("你好") }.use { engine ->
            val prepared = engine.prepare(source)
            assertTrue(engine.translatePrepared(source, prepared, false) { false } is PageResult.Failed)
            assertEquals(0, calls); assertEquals(0, stages.inpaints)
            val cache = PreparedPageCache()
            cache.put("source", prepared)
            val restored = cache.take("source")!!
            val result = engine.translatePrepared(source, restored, true) as PageResult.Translated
            assertEquals(1, stages.detects); assertEquals(1, stages.ocrs); assertEquals(1, stages.inpaints)
            result.page.recycle(); restored.close()
        }
        source.recycle()
    }

    @Test fun stageProgressReportsCompletionAndCreditsReusedOcrAndInpainting() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stages = Stages()
        val completed = mutableMapOf<TranslationStage, Float>()
        val report: suspend (TranslationStage, Float) -> Unit = { stage, fraction -> completed[stage] = fraction }
        stages.engine { listOf("你好") }.use { engine ->
            val prepared = engine.prepare(source, report)
            assertEquals(setOf(TranslationStage.DETECT, TranslationStage.OCR), completed.keys)
            val first = engine.translatePrepared(source, prepared, false, report) as PageResult.Translated
            assertEquals(TranslationStage.entries.toSet(), completed.keys)
            assertTrue(completed.values.all { it == 1f })
            first.page.recycle()

            completed.clear()
            val second = engine.translatePrepared(source, prepared, true, report) as PageResult.Translated
            assertEquals(TranslationStage.entries.toSet(), completed.keys)
            assertTrue(completed.values.all { it == 1f })
            assertEquals(1, stages.detects)
            assertEquals(1, stages.ocrs)
            assertEquals(1, stages.inpaints)
            second.page.recycle()
            prepared.close()
        }
        source.recycle()
    }
}
