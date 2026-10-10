package com.hippo.ehviewer.translation

import android.app.Application
import kotlinx.coroutines.*
import com.hippo.ehviewer.translation.engine.Usage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ApiTranslatorTest {
    private fun options(server: MockWebServer) = TranslationOptions(backend = TranslationBackend.LLM_API,
        apiUrl = server.url("/v1/chat/completions").toString())

    @Test fun selectedNonJapaneseSourcesReachRealApiTransport() {
        MockWebServer().use { server ->
            for ((source, name) in listOf("en" to "English", "ko" to "Korean", "zh-TW" to "Traditional Chinese (Taiwan)")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>Bonjour"}}]}"""))
                val selected = options(server).copy(source = source, target = "fr")
                ApiTranslator(selected).use { assertEquals(listOf("Bonjour"), runBlocking { it.translate(listOf(selected.sampleText())) }) }
                val messages = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                assertTrue(messages.getJSONObject(0).getString("content").contains("$name text into French"))
                assertEquals("<|1|>${selected.sampleText()}", messages.getJSONObject(1).getString("content"))
            }
        }
    }

    @Test fun japaneseSourceAndSelectedChineseRegionReachApiRequests() {
        MockWebServer().use { server ->
            for ((target, name) in listOf("zh-CN" to "Simplified Chinese",
                "zh-HK" to "Traditional Chinese (Hong Kong)", "zh-TW" to "Traditional Chinese (Taiwan)",
                "zh-Hant-HK" to "Traditional Chinese (Hong Kong)", "zh-Hant-TW" to "Traditional Chinese (Taiwan)")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>譯文"}}]}"""))
                ApiTranslator(options(server).copy(target = target)).use {
                    runBlocking { it.translate(listOf("こんにちは")) }
                }
                val body = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
                val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(prompt.contains("Japanese text into $name"))
                if (target == "zh-CN") {
                    assertFalse(prompt.contains("大陆"))
                    assertFalse(prompt.contains("Mainland", ignoreCase = true))
                    assertFalse(prompt.contains("Simplified Chinese ("))
                }
            }
        }
    }

    @Test fun configuredHttpHostPortPathAndQueryAreUsedWithoutRewriting() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("0.0.0.0"), 0)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>你好"}}]}"""))
            // A loopback alias outside the old allowlist keeps this regression independent of LAN setup.
            val endpoint = server.url("/custom/v1/chat/completions?api-version=test").newBuilder()
                .host("127.0.0.2").build()
            ApiTranslator(options(server).copy(apiUrl = endpoint.toString(), apiModel = "chosen-model")).use {
                assertEquals(listOf("你好"), runBlocking { it.translate(listOf("こんにちは")) })
            }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("127.0.0.2:${server.port}", request.getHeader("Host"))
            assertEquals("/custom/v1/chat/completions?api-version=test", request.path)
            assertEquals("chosen-model", JSONObject(request.body.readUtf8()).getString("model"))
        }
    }

    @Test fun localRequestsOmitOptionalAuthAndModelAndPreserveRegionOrder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"<|3|>早上好\n<|1|>你好"}}],"usage":{"prompt_tokens":90,"completion_tokens":12}}"""))
            ApiTranslator(options(server)).use { translator ->
                val result = runBlocking { translator.translateDetailed(listOf("こんにちは", "", "おはよう")) }
                assertEquals(listOf("你好", "", "早上好"), result.translations)
                assertNull(result.error)
                assertEquals(90, result.usage!!.promptTokens)
                assertEquals(12, result.usage!!.completionTokens)
            }
            assertEquals(1, server.requestCount)
            run {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                val body = JSONObject(request.body.readUtf8())
                assertNull(request.getHeader("Authorization"))
                assertFalse(body.has("model"))
                assertFalse(body.getBoolean("stream"))
                val messages = body.getJSONArray("messages")
                assertEquals(com.hippo.ehviewer.translation.engine.LlmTranslator(options(server).engineConfig().translator)
                    .buildMessages(listOf("こんにちは", "", "おはよう")).toString(), messages.toString())
                assertEquals("system", messages.getJSONObject(0).getString("role"))
                assertTrue(messages.getJSONObject(0).getString("content").contains("terminology consistent"))
                assertTrue(messages.getJSONObject(0).getString("content").contains("same marker"))
                assertEquals("<|1|>こんにちは\n<|2|>\n<|3|>おはよう",
                    messages.getJSONObject(messages.length() - 1).getString("content"))
                assertEquals(0.3, body.getDouble("temperature"), 0.0)
            }
        }
    }

    @Test fun configuredModelAuthAndTargetAreSentAndThinkingIsRemoved() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<think>analysis</think> <|1|>Hello"}}]}"""))
            ApiTranslator(options(server).copy(target = "en", apiKey = "secret-test", apiModel = "chosen-model")).use {
                assertEquals(listOf("Hello"), runBlocking { it.translate(listOf("こんにちは")) })
            }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            val body = JSONObject(request.body.readUtf8())
            assertEquals("Bearer secret-test", request.getHeader("Authorization"))
            assertEquals("chosen-model", body.getString("model"))
            assertTrue(body.toString().contains("English"))
        }
    }

    @Test fun httpErrorsAndIncompleteResponsesFailInsteadOfReturningOriginalText() {
        listOf(
            401 to "secret-provider-body",
            200 to "not json",
            200 to """{"choices":[{"message":{"content":null}}]}""",
            200 to """{"choices":[{"message":{"content":" "}}]}""",
        ).forEach { (code, body) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody(body))
                ApiTranslator(options(server)).use { translator ->
                    val error = assertThrows(IOException::class.java) { runBlocking { translator.translate(listOf("原文")) } }
                    assertFalse(error.message.orEmpty().contains("secret-provider-body"))
                }
            }
        }
    }

    @Test fun cancellationDoesNotWaitForServerTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            ApiTranslator(options(server)).use { translator ->
                val job = launch(Dispatchers.IO) { translator.translate(listOf("こんにちは")) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                withTimeout(1000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }
    }

    @Test fun pageJumpCancelsOldHttpRequestAndReusesTranslatorForNewPage() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>新页面"}}]}"""))
            ApiTranslator(options(server)).use { translator ->
                val oldPage = TranslationPageRequest(2, false)
                val worker = async(Dispatchers.IO) {
                    try {
                        oldPage.apiCall { translator.translate(listOf("旧页第一句", "旧页第二句")) }
                        fail("Old page should have been interrupted")
                    } catch (_: SupersededTranslationPage) { }
                    TranslationPageRequest(19, false).apiCall { translator.translate(listOf("新页面原文")) }
                }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                oldPage.supersede()
                assertEquals(listOf("新页面"), withTimeout(3000) { worker.await() })
                val newRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertTrue(newRequest.body.readUtf8().contains("新页面原文"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun partialNumberedOutputFallsBackOnlyTheMissingRegionAndReportsTheError() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|2>|谢谢"}}]}"""))
            ApiTranslator(options(server)).use { translator ->
                val result = translator.translateDetailed(listOf("こんにちは", "ありがとう"))
                assertEquals(listOf("こんにちは", "谢谢"), result.translations)
                assertNotNull(result.error)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun outputLimitSplitsThePageAndRejectsTheTruncatedText() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"<|1|>不完整译文"}}],"usage":{"prompt_tokens":100,"completion_tokens":1000}}"""))
            for (text in listOf("<|1|>甲\n<|2|>乙", "<|1|>丙\n<|2|>丁")) {
                server.enqueue(MockResponse().setBody(JSONObject().put("choices", org.json.JSONArray().put(
                    JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", text))))
                    .put("usage", JSONObject().put("prompt_tokens", 50).put("completion_tokens", 10)).toString()))
            }
            ApiTranslator(options(server)).use { translator ->
                val result = translator.translateDetailed(listOf("a", "b", "c", "d"))
                assertEquals(listOf("甲", "乙", "丙", "丁"), result.translations)
                assertNull(result.error)
                assertEquals(Usage(200, 1020), result.usage)
            }
            assertEquals(3, server.requestCount)
            val inputs = (1..3).map {
                val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals("system", messages.getJSONObject(0).getString("role"))
                messages.getJSONObject(messages.length() - 1).getString("content")
            }
            assertEquals(listOf("<|1|>a\n<|2|>b\n<|3|>c\n<|4|>d", "<|1|>a\n<|2|>b", "<|1|>c\n<|2|>d"), inputs)
        }
    }

    @Test fun oneOverBudgetApiRegionDoesNotDiscardTheOtherHalf() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val limit = MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"<|1|>unfinished"}}]}""")
            server.enqueue(limit)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>有效译文"}}]}"""))
            server.enqueue(limit)
            ApiTranslator(options(server)).use { translator ->
                val result = translator.translateDetailed(listOf("a", "long source"))
                assertEquals(listOf("有效译文", "long source"), result.translations)
                assertEquals(setOf(1), result.missingIndices)
                assertNotNull(result.error)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun singleTruncatedApiResultIsAnExplicitFailureWithNoUsableTranslation() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""))
            ApiTranslator(options(server)).use { translator ->
                val result = translator.translateDetailed(listOf("原文"))
                assertEquals(listOf("原文"), result.translations)
                assertEquals(setOf(0), result.missingIndices)
                assertNotNull(result.error)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun contentFilterIsNotRetriedAsAnOutputBudgetFailure() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}"""))
            ApiTranslator(options(server)).use { translator ->
                assertThrows(IOException::class.java) { runBlocking { translator.translate(listOf("a", "b")) } }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unsupportedTemperatureRetriesOnceWithoutChangingThePageOrEndpoint() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("temperature is not supported"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"<|1|>你好\n<|2|>谢谢"}}]}"""))
            ApiTranslator(options(server)).use { translator ->
                assertEquals(listOf("你好", "谢谢"), translator.translate(listOf("こんにちは", "ありがとう")))
            }
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            val initial = JSONObject(first.body.readUtf8())
            val retried = JSONObject(second.body.readUtf8())
            assertTrue(initial.has("temperature"))
            assertFalse(retried.has("temperature"))
            assertEquals(initial.getJSONArray("messages").toString(), retried.getJSONArray("messages").toString())
            assertEquals(first.path, second.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun blankPageDoesNotSendARequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            ApiTranslator(options(server)).use { assertEquals(listOf("", " "), it.translate(listOf("", " "))) }
            assertEquals(0, server.requestCount)
        }
    }
}
