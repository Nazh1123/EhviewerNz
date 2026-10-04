package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import com.hippo.ehviewer.translation.engine.PageResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in fixtures supplied by the user. No preference/cache/original-gallery edits. */
@RunWith(AndroidJUnit4::class)
class NativeComplexPageDeviceTest {
    @Test fun translatesComplexFixtures() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testComplexNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read().copy(backend = TranslationBackend.NATIVE_LLM)
        val root = File(context.getExternalFilesDir(null), "translation-complex-fixtures")
        val report = File(root, "report-${args.getString("label") ?: "run"}.json")
        val pages = JSONArray()
        report.writeText(JSONObject().put("pages", pages).toString(2))
        TranslationRuntime.lock.withLock {
            val models = TranslationModels(context).verified()
            NativeTranslator(context, options).use { translator ->
                TranslationEngineFactory.create(context, models, options, translator,
                    retainNativeModels = { true }).use { engine ->
                    for (name in listOf("4218710-16dd4eafd3-00000009", "4218710-16dd4eafd3-00000014")) {
                        val page = JSONObject().put("name", name)
                        pages.put(page)
                        val requests = JSONArray()
                        page.put("requests", requests)
                        translator.inferenceObserver = { messages, raw, usage, cached, ms, failure ->
                            requests.put(JSONObject().put("messages", messages).put("raw", raw)
                                .put("prompt", usage.promptTokens).put("completion", usage.completionTokens)
                                .put("cached", cached).put("ms", ms).put("failure", failure))
                            report.writeText(JSONObject().put("pages", pages).toString(2))
                        }
                        val bitmap = GalleryTranslationSession.decodeBounded(File(root, "$name.webp"), options.backend)
                        try {
                            engine.prepare(bitmap).use { prepared ->
                                page.put("regions", JSONArray(prepared.regions.map { it.sourceText }))
                                page.put("detectMs", prepared.detectMs).put("ocrMs", prepared.ocrMs)
                                report.writeText(JSONObject().put("pages", pages).toString(2))
                                val result = engine.translatePrepared(bitmap, prepared, false)
                                page.put("result", result.toString())
                                page.put("translations", JSONArray(prepared.regions.map { it.translatedText }))
                                if (result is PageResult.Translated) {
                                    try {
                                        File(root, "$name-${args.getString("label") ?: "run"}.png").outputStream().use {
                                            assertTrue(result.page.compress(Bitmap.CompressFormat.PNG, 100, it))
                                        }
                                    } finally { result.page.recycle() }
                                }
                                report.writeText(JSONObject().put("pages", pages).toString(2))
                                if (args.getString("verify") == "true") {
                                    assertTrue("Fixture failed: $result", result is PageResult.Translated)
                                    assertTrue(prepared.regions.none { NativeTranslationResponse.hasMarkers(it.translatedText) })
                                    assertTrue(prepared.regions.none {
                                        NativeTranslationResponse.generationFailure(it.translatedText, listOf(it.sourceText))
                                    })
                                }
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }
}
