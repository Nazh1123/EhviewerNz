package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.*
import com.hippo.ehviewer.translation.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Translation-only output fixtures through the production native adapter; no actual JNI inference. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NativeTranslationProtocolTest {
    @Test fun catalogTranslationModelsSkipFailedNumberedPageProbe() = runBlocking<Unit> {
        for (model in NativeModelCatalog.models) {
            val sources = mutableListOf<String>()
            NativeTranslator(TranslationOptions(nativeModelId = model.sha256)) { messages ->
                assertEquals(1, messages.length())
                val source = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
                sources.add(source)
                "译文$source" to Usage(20, 4)
            }.use { translator ->
                val result = translator.translateDetailed(listOf("a", "", "b"))
                assertEquals(listOf("译文a", "", "译文b"), result.translations)
                assertEquals(listOf("a", "b"), sources)
                assertEquals(Usage(40, 8), result.usage)
            }
        }
    }

    @Test fun japaneseSourceAndSelectedRegionReachBothNumberedAndPlainNativeRequests() = runBlocking<Unit> {
        for ((target, region) in listOf("zh-HK" to "Hong Kong", "zh-TW" to "Taiwan")) {
            val options = TranslationOptions(target = target)
            val numbered = NativeTranslator.buildNumberedMessages(options, listOf("a", "b"))
            assertTrue(numbered.getJSONObject(0).getString("content")
                .startsWith("Translate the following Japanese text into Traditional Chinese ($region):"))
            NativeTranslator(options) { messages ->
                assertTrue(messages.getJSONObject(0).getString("content").startsWith(
                    "Translate the following Japanese segment into Traditional Chinese ($region),"))
                "譯文" to null
            }.use { assertEquals(listOf("譯文"), it.translate(listOf("こんにちは"))) }
        }
    }

    @Test fun repetitionGuardStopsTheActualHeartLoopButPreservesShortOrSourceRepetitions() {
        val source = listOf("こんなの置いてあった、食ってみたくなりません⁉", "うどんも選べるの７")
        assertTrue(NativeTranslationResponse.generationFailure("<|1|>还放着这种东西呢～～" + "♥".repeat(24), source))
        assertFalse(NativeTranslationResponse.generationFailure("<|1|>啊～～♥♥♥", source))
        assertFalse(NativeTranslationResponse.generationFailure("哈".repeat(32), listOf("哈".repeat(32))))
        assertTrue(NativeTranslationResponse.generationFailure("重复".repeat(12), listOf("普通台词")))
        assertTrue(NativeTranslationResponse.generationFailure("<|1|>译文♥／7>", source))
        assertFalse(NativeTranslationResponse.generationFailure("请看 https://wanimap.app", source))
    }

    @Test fun brokenSlashMarkersDoNotEnterASingleBubble() = runBlocking<Unit> {
        val retries = mutableListOf<String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) "<|1|>译文a♥／7>译文b／8>译文c" to null
            else {
                val source = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
                retries.add(source)
                "译文$source" to null
            }
        }.use { translator ->
            assertEquals(listOf("译文a", "译文b", "译文c"), translator.translate(listOf("a", "b", "c")))
            assertEquals(listOf("a", "b", "c"), retries)
        }
    }

    @Test fun prematurelyEndedIdPrefixDoesNotCommitPotentiallyMergedAndShiftedTranslations() = runBlocking<Unit> {
        val retries = mutableListOf<String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) "<|1|>a和b混在一起<|2|>c错位" to Usage(60, 12)
            else {
                val source = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
                retries.add(source)
                "译文$source" to Usage(20, 4)
            }
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "b", "c"))
            assertEquals(listOf("译文a", "译文b", "译文c"), result.translations)
            assertEquals(listOf("a", "b", "c"), retries)
            assertEquals(Usage(120, 24), result.usage)
            assertNull(result.error)
        }
    }

    @Test fun interruptedPageKeepsCompletedRegionsAndRetriesOnlyTheUnfinishedTail() = runBlocking<Unit> {
        val retries = mutableListOf<String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) throw NativeGenerationStopped(
                "<|1|>译文a<|2|>译文b<|3|>未完成", Usage(80, 192), budgetExceeded = true)
            val source = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
            retries.add(source)
            "译文$source" to Usage(20, 4)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "b", "c", "d"))
            assertEquals(listOf("译文a", "译文b", "译文c", "译文d"), result.translations)
            assertEquals(listOf("c", "d"), retries)
            assertEquals(Usage(120, 200), result.usage)
            assertNull(result.error)
        }
    }

    @Test fun interruptedPageWithoutCompletedRegionsUsesBoundedSplits() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            if (messages.length() > 1) throw NativeGenerationStopped(
                "<|1|>未完成", Usage(60, 192), budgetExceeded = true)
            "译文" + messages.getJSONObject(0).getString("content").substringAfter("\n\n") to Usage(20, 4)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "b"))
            assertEquals(listOf("译文a", "译文b"), result.translations)
            assertEquals(Usage(100, 200), result.usage)
            assertEquals(3, calls)
        }
    }

    @Test fun earlyProtocolStopKeepsValidPrefixAndFallsBackWithoutSplittingWholePage() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            if (messages.length() > 1) throw NativeGenerationStopped(
                "<|1|>译文a<|2|>错误<|number|>", Usage(60, 12), budgetExceeded = false)
            "译文" + messages.getJSONObject(0).getString("content").substringAfter("\n\n") to Usage(20, 4)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "b"))
            assertEquals(listOf("译文a", "译文b"), result.translations)
            assertEquals(2, calls)
            assertEquals(Usage(80, 16), result.usage)
        }
    }

    @Test fun earlyProtocolStopInSingleRegionPreservesSourceWithoutAnotherInference() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { _ ->
            calls++
            throw NativeGenerationStopped("<|number|>", Usage(20, 4), budgetExceeded = false)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a"))
            assertEquals(listOf("a"), result.translations)
            assertEquals(setOf(0), result.missingIndices)
            assertEquals(Usage(20, 4), result.usage)
            assertEquals(1, calls)
        }
    }

    @Test fun streamingGuardWaitsForCompleteMarkersAndAllowsReorderedIds() {
        for (output in listOf("你好", "<", "<|", "<|1", "<|1|", "<|2|>谢谢<|1|>你好"))
            assertFalse(output, NativeTranslationResponse.protocolFailure(output, 2))
        for (output in listOf("<|number|>", "<|3|>", "<|0|>", "<|1|>你好<|1|>", "<|99999999999999999999999|>"))
            assertTrue(output, NativeTranslationResponse.protocolFailure(output, 2))
    }

    @Test fun outputReservationScalesWithTextAndHasAHardUpperBound() {
        assertEquals(192, NativeTranslationResponse.outputBudget(listOf("短い台詞")))
        val medium = NativeTranslationResponse.outputBudget(List(4) { "a".repeat(20) })
        assertTrue(medium in 193..1023)
        assertEquals(1024, NativeTranslationResponse.outputBudget(listOf("a".repeat(5000))))
    }

    @Test fun literalPlaceholderRetriesOnlyItsContaminatedRegion() = runBlocking<Unit> {
        val retries = mutableListOf<String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) "<|1|>你好<|number|>别的气泡\n<|2|>谢谢" to Usage(80, 20)
            else {
                retries.add(messages.getJSONObject(0).getString("content").substringAfter("\n\n"))
                "你好" to Usage(20, 4)
            }
        }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("你好", "谢谢"), result.translations)
            assertEquals(listOf("こんにちは"), retries)
            assertEquals(Usage(100, 24), result.usage)
        }
    }

    @Test fun emptyOrUnfinishedThinkingPageFallsBackInsteadOfFailingThePage() = runBlocking<Unit> {
        for (raw in listOf("", "<think>unfinished", "</think>")) {
            var calls = 0
            NativeTranslator(TranslationOptions()) { messages ->
                calls++
                (if (messages.length() > 1) raw
                else "译文" + messages.getJSONObject(0).getString("content").substringAfter("\n\n")) to Usage(20, 4)
            }.use { translator ->
                val result = translator.translateDetailed(listOf("a", "b"))
                assertEquals(listOf("译文a", "译文b"), result.translations)
                assertNull(result.error)
                assertEquals(3, calls)
                assertEquals(Usage(60, 12), result.usage)
            }
        }
    }

    @Test fun duplicateIdsDoNotOverwriteValidRegions() = runBlocking<Unit> {
        val retries = mutableListOf<String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) "<|1|>错译<|2|>谢谢<|1|>另一气泡" to null
            else {
                retries.add(messages.getJSONObject(0).getString("content").substringAfter("\n\n"))
                "你好" to null
            }
        }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("你好", "谢谢"), result.translations)
            assertEquals(listOf("こんにちは"), retries)
        }
    }

    @Test fun plainResponseWithExtraOrLiteralMarkersKeepsSource() = runBlocking<Unit> {
        for (raw in listOf("<|1|>你好<|2|>谢谢", "你好<|number|>谢谢", "<|1|>你好<|1|>别的气泡", "你好<|2")) {
            NativeTranslator(TranslationOptions()) { _ -> raw to Usage(20, 8) }.use { translator ->
                val result = translator.translateDetailed(listOf("こんにちは"))
                assertEquals(listOf("こんにちは"), result.translations)
                assertEquals(setOf(0), result.missingIndices)
            }
        }
    }

    @Test fun localPageUsesCompactMangaPromptAndRetainsLanguageIdsBlanksAndMultilineSources() = runBlocking<Unit> {
        val source = listOf("こんにちは\n元気ですか", "", "ありがとう")
        for ((target, language) in listOf("zh-CN" to "Simplified Chinese", "en" to "English", "ko" to "Korean")) {
            val options = TranslationOptions(target = target)
            NativeTranslator(options) { messages ->
                assertEquals(2, messages.length())
                val instruction = messages.getJSONObject(0).getString("content")
                assertEquals("system", messages.getJSONObject(0).getString("role"))
                assertTrue(instruction.contains(language))
                val original = LlmTranslator(options.engineConfig().translator).buildMessages(source)
                    .getJSONObject(0).getString("content")
                assertTrue("Local fixed instruction should be substantially shorter", instruction.length < original.length)
                assertFalse(instruction.contains("ANALYSIS & DE-VERBALIZATION"))
                assertTrue(instruction.contains("Keep meaning, tone, names and sound effects"))
                assertTrue(instruction.contains("Keep every region separate"))
                assertTrue(instruction.startsWith("Translate the following Japanese text into $language:"))
                assertEquals("<|1|>こんにちは\n元気ですか\n<|2|>ありがとう",
                    messages.getJSONObject(1).getString("content"))
                "<|2|>谢谢\n<|1|>你好\n你好吗" to Usage(70, 10)
            }.use { translator ->
                val result = translator.translateDetailed(source)
                assertEquals(listOf("你好\n你好吗", "", "谢谢"), result.translations)
                assertNull(result.error)
            }
        }
    }

    @Test fun missingNonblankRegionMapsBackAcrossBlankInputPositions() = runBlocking<Unit> {
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) {
                assertEquals("<|1|>a\n<|2|>b", messages.getJSONObject(1).getString("content"))
                "<|2|>译文b" to Usage(100, 5)
            } else "" to Usage(20, 0)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("", "a", " \n", "b", ""))
            assertEquals(listOf("", "a", " \n", "译文b", ""), result.translations)
            assertEquals(setOf(1), result.missingIndices)
            assertNotNull(result.error)
            assertEquals(Usage(120, 5), result.usage)
        }
    }

    @Test fun compactSystemPromptDoesNotChangeWithSourceLengthOrNumberOfRegions() {
        val options = TranslationOptions()
        val small = NativeTranslator.buildNumberedMessages(options, listOf("a", "b"))
        val large = NativeTranslator.buildNumberedMessages(options, List(12) { "長い文章\n".repeat(it + 1) })
        assertEquals(small.getJSONObject(0).toString(), large.getJSONObject(0).toString())
        assertEquals(2, large.length()) // Project language settings still disable upstream Traditional Chinese examples.
        assertTrue(large.getJSONObject(1).getString("content").contains("<|12|>"))
    }

    @Test fun localTranslationTestAcceptsACompletePlainTranslation() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { _ ->
            calls++
            "明天我要去学校。" to Usage(20, 8)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("明日は学校へ行きます。"))
            assertNull("Test local translation must not fail on a valid unnumbered result", result.error)
            assertEquals(listOf("明天我要去学校。"), result.translations)
            assertEquals(Usage(20, 8), result.usage)
            assertEquals(1, calls)
        }
    }

    @Test fun plainRequestUsesOnlyTheUserRoleAndRetainsTheFullMultilineOutput() = runBlocking<Unit> {
        val output = "明天我要去学校。\n".repeat(60).trim()
        NativeTranslator(TranslationOptions()) { messages ->
            assertEquals(1, messages.length())
            val message = messages.getJSONObject(0)
            assertEquals("user", message.getString("role"))
            assertTrue(message.getString("content").startsWith("将以下文本翻译为简体中文"))
            assertTrue(message.getString("content").endsWith("\n\n明日は学校へ行きます。"))
            output to null
        }.use { translator ->
            val result = translator.translateDetailed(listOf("明日は学校へ行きます。"))
            assertNull(result.error)
            assertEquals(output, result.translations.single())
            assertTrue(result.translations.single().length > 220)
        }
    }

    @Test fun numberedPageStillUsesOneRequestAndMapsByIds() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            assertEquals(2, messages.length())
            "<|2|>谢谢\n<|1|>你好" to Usage(80, 10)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("你好", "谢谢"), result.translations)
            assertNull(result.error)
            assertEquals(Usage(80, 10), result.usage)
            assertEquals(1, calls)
        }
    }

    @Test fun unnumberedPageRetriesEachRegionAndRemembersTheModelProtocol() = runBlocking<Unit> {
        var numberedCalls = 0
        val plainInputs = mutableListOf<String>()
        NativeTranslator(TranslationOptions()) { messages ->
            if (messages.length() > 1) {
                numberedCalls++
                "你好，谢谢。" to Usage(80, 10)
            } else {
                val source = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
                plainInputs.add(source)
                (if (source == "こんにちは") "你好" else "谢谢") to Usage(20, 5)
            }
        }.use { translator ->
            val source = listOf("こんにちは", "", "ありがとう")
            val result = translator.translateDetailed(source)
            assertEquals(listOf("你好", "", "谢谢"), result.translations)
            assertNull(result.error)
            assertEquals(Usage(120, 20), result.usage)
            // Unloading model memory between pages must not forget the learned request mode.
            translator.unloadModel()
            val next = translator.translateDetailed(source)
            assertEquals(result.translations, next.translations)
            assertEquals(Usage(40, 10), next.usage)
            assertEquals(1, numberedCalls)
            assertEquals(listOf("こんにちは", "ありがとう", "こんにちは", "ありがとう"), plainInputs)
        }
    }

    @Test fun partialNumberedPageRetriesOnlyMissingRegions() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            if (messages.length() > 1) "<|2|>谢谢" to Usage(60, 5)
            else {
                assertTrue(messages.getJSONObject(0).getString("content").endsWith("\n\nこんにちは"))
                "你好" to Usage(20, 4)
            }
        }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("你好", "谢谢"), result.translations)
            assertNull(result.error)
            assertTrue(result.missingIndices.isEmpty())
            assertEquals(Usage(80, 9), result.usage)
            assertEquals(2, calls)
        }
    }

    @Test fun densePageRetriesOutputLimitWithSmallerNumberedRequests() = runBlocking<Unit> {
        val attempts = mutableListOf<List<String>>()
        NativeTranslator(TranslationOptions()) { messages ->
            val sources = messages.getJSONObject(messages.length() - 1).getString("content")
                .lineSequence().map { it.substringAfter('>') }.toList()
            attempts.add(sources)
            assertEquals(2, messages.length())
            if (sources.size > 2) throw TranslationOutputLimitException(usage = Usage(100, 1024))
            sources.mapIndexed { index, text -> "<|${index + 1}|>译文$text" }.joinToString("\n") to Usage(50, 10)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "b", "c", "d"))
            assertEquals(listOf("译文a", "译文b", "译文c", "译文d"), result.translations)
            assertNull(result.error)
            assertEquals(Usage(200, 1044), result.usage)
            assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("a", "b"), listOf("c", "d")), attempts)
        }
    }

    @Test fun oneInvalidMissingRegionDoesNotDiscardTheValidNumberedTranslation() = runBlocking<Unit> {
        NativeTranslator(TranslationOptions()) { messages ->
            (if (messages.length() > 1) "<|2|>谢谢" else "") to Usage(20, 5)
        }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
            assertEquals(listOf("こんにちは", "谢谢"), result.translations)
            assertEquals(setOf(0), result.missingIndices)
            assertNotNull(result.error)
            assertEquals(Usage(40, 10), result.usage)
        }
    }

    @Test fun unsupportedSystemRolesUseValidatedUserOnlyRequests() = runBlocking<Unit> {
        var attempts = 0
        NativeTranslator(TranslationOptions(target = "en")) { messages ->
            attempts++
            if (messages.length() > 1) throw UnsupportedOperationException("unsupported roles")
            assertTrue(messages.getJSONObject(0).getString("content").startsWith("Translate the following Japanese segment into English"))
            "Hello" to null
        }.use { translator ->
            val result = translator.translateDetailed(listOf("こんにちは", "やあ"))
            assertEquals(listOf("Hello", "Hello"), result.translations)
            assertNull(result.error)
            assertEquals(3, attempts)
        }
    }

    @Test fun rejectedPageBudgetSplitsBeforeGeneratingAndKeepsOrder() = runBlocking<Unit> {
        var attempts = 0
        NativeTranslator(TranslationOptions()) { messages ->
            attempts++
            if (messages.length() > 1) null
            else messages.getJSONObject(0).getString("content").substringAfter("\n\n").let {
                "译文$it" to Usage(20, 4)
            }
        }.use { translator ->
            assertEquals(listOf("译文a", "译文b"), translator.translate(listOf("a", "b")))
            assertEquals(3, attempts)
        }
    }

    @Test fun invalidOutputsRemainFailuresWithoutRepeatedInference() = runBlocking<Unit> {
        for (output in listOf("", "<think>unfinished", "<|2|>wrong bubble")) {
            var calls = 0
            NativeTranslator(TranslationOptions()) { _ -> calls++; output to null }.use { translator ->
                val result = translator.translateDetailed(listOf("こんにちは"))
                assertEquals(listOf("こんにちは"), result.translations)
                assertNotNull(result.error)
                assertEquals(setOf(0), result.missingIndices)
                assertEquals(1, calls)
            }
        }
        var calls = 0
        NativeTranslator(TranslationOptions()) { _ ->
            calls++
            throw IllegalStateException("Native token decoding failed")
        }.use { translator ->
            assertThrows(IllegalStateException::class.java) { runBlocking { translator.translate(listOf("a", "b")) } }
            assertEquals(1, calls)
        }
    }

    @Test fun oneRegionOverBudgetKeepsOtherRegionsAndDoesNotRetryWithoutBound() = runBlocking<Unit> {
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            if (messages.length() > 1) null else {
                val source = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
                if (source == "very long OCR block") throw TranslationOutputLimitException(usage = Usage(20, 1024))
                "译文$source" to Usage(20, 4)
            }
        }.use { translator ->
            val result = translator.translateDetailed(listOf("a", "very long OCR block", "b"))
            assertEquals(listOf("译文a", "very long OCR block", "译文b"), result.translations)
            assertEquals(setOf(1), result.missingIndices)
            assertNotNull(result.error)
            assertEquals(Usage(60, 1032), result.usage)
            assertEquals(5, calls)
        }
    }

    @Test fun cancellationBetweenSplitRequestsDoesNotStartTheNextHalf() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        NativeTranslator(TranslationOptions()) { _ ->
            calls++
            if (calls == 1) throw TranslationOutputLimitException()
            entered.complete(Unit)
            awaitCancellation()
        }.use { translator ->
            val worker = launch { translator.translateDetailed(listOf("a", "b", "c", "d")) }
            entered.await()
            withTimeout(1000) { worker.cancelAndJoin() }
            assertEquals(2, calls)
        }
    }

    @Test fun cancellationDuringCompatibilityRetryStopsTheRemainingRegions() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            if (messages.length() > 1) "unstructured page translation" to null
            else { started.complete(Unit); awaitCancellation() }
        }.use { translator ->
            val worker = launch { translator.translate(listOf("a", "b", "c")) }
            started.await()
            withTimeout(1000) { worker.cancelAndJoin() }
            assertEquals(2, calls)
        }
    }

    @Test fun translationOnlyOutputCompletesThePrepareTranslateAndRenderPipeline() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var calls = 0
        NativeTranslator(TranslationOptions()) { messages ->
            calls++
            if (messages.length() > 1) "你好，谢谢。" to Usage(80, 8)
            else {
                val text = messages.getJSONObject(0).getString("content").substringAfter("\n\n")
                (if (text == "こんにちは") "你好" else "谢谢") to Usage(20, 4)
            }
        }.use { translator ->
            ResumablePipeline(
                detect = { page ->
                    Detection(listOf(8f, 55f).map { top ->
                        TextLine(listOf(Pt(8f, top), Pt(90f, top), Pt(90f, top + 30f), Pt(8f, top + 30f)), 1f)
                    }, Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888))
                },
                recognize = { _, lines -> lines.forEachIndexed { index, line -> line.text = if (index == 0) "こんにちは" else "ありがとう" } },
                inpaint = { page, _, _ -> page.copy(Bitmap.Config.ARGB_8888, true) },
                translator = translator, cfg = TranslationOptions().engineConfig(), release = {}, warm = {},
                translationIdentity = "native-compatibility", overlapInpainting = false,
            ).use { pipeline ->
                val result = pipeline.translatePage(source)
                assertTrue("Plain GGUF output became failure/skipped instead of a translated page: $result", result is PageResult.Translated)
                result as PageResult.Translated
                assertEquals(2, result.stats.kept)
                assertEquals(Usage(120, 16), Usage(result.stats.promptTokens, result.stats.completionTokens))
                assertEquals(3, calls)
                result.page.recycle()
            }
        }
        source.recycle()
    }
}
