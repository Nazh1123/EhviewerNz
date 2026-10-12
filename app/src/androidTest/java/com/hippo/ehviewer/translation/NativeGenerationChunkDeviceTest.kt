package com.hippo.ehviewer.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.translation.engine.TranslationOutputLimitException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGenerationChunkDeviceTest {
    @Test(timeout = 180000) fun chunksPreserveBytesEosAndPartialOutputAtTheTokenLimit() = runBlocking<Unit>(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNativeLlm") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent(context, com.hippo.ehviewer.ui.splash.SplashActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val options = TranslationSettings(context).read()
        assertTrue(NativeModelStore(context).ready(options))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content",
            NativeTranslator.sampleInstruction(options.copy(source = "ja", target = "zh-CN")) +
                "\n\n明日は学校へ行きます。田中さん、一緒に帰ろう。"))
        NativeModelStore(context).load(options.nativeModelId).use { model ->
            assertTrue(model.countTokens("明日は学校へ行きます。") > 0)
            suspend fun generate(chunked: Boolean, budget: Int): Pair<ByteArray, Boolean> {
                assertTrue(model.begin(messages, maxOutputTokens = budget, cacheEnabled = false) > 0)
                val output = ByteArrayOutputStream()
                var limited = false
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val before = model.completionTokens()
                        val piece = (if (chunked) model.nextChunk() else model.next()) ?: break
                        assertTrue(model.completionTokens() - before <= if (chunked) 4 else 1)
                        output.write(piece)
                    }
                } catch (_: TranslationOutputLimitException) { limited = true }
                if (limited) assertEquals(budget, model.completionTokens())
                return output.toByteArray() to limited
            }
            for (budget in listOf(1, 5, 192)) {
                val single = generate(false, budget)
                val chunks = generate(true, budget)
                assertArrayEquals("Budget $budget", single.first, chunks.first)
                assertEquals(single.second, chunks.second)
                assertTrue(chunks.first.isNotEmpty())
                assertEquals(budget != 192, chunks.second)
            }
        }
    }
}
