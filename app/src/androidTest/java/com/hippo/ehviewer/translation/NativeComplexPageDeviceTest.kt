package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import com.hippo.ehviewer.translation.engine.PageResult
import com.hippo.ehviewer.translation.engine.NativeLlm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream

/** Opt-in fixtures supplied by the user. No preference/cache/original-gallery edits. */
@RunWith(AndroidJUnit4::class)
class NativeComplexPageDeviceTest {
    /** Full-rule variants against the same OCR, to separate model wording from image stages. */
    @Test fun comparesDialogueRules() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testDialogueRules") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = TranslationSettings(context).read().copy(source = "en")
        val root = File(context.getExternalFilesDir(null), "translation-gallery-fixtures")
        val baseline = JSONObject(File(root, "report-baseline-en.json").readText()).getJSONArray("pages")
        val output = JSONArray()
        val report = File(root, "dialogue-rule-comparison.json")
        NativeLlm(NativeModelStore(context).file(options.nativeModelId).absolutePath).use { model ->
            for (index in 0 until baseline.length()) {
                val page = baseline.getJSONObject(index)
                if (page.getString("name") !in listOf("00000004", "00000011")) continue
                val regions = page.getJSONArray("regions")
                val queries = List(regions.length()) { regions.getString(it) }
                for ((variant, rule) in listOf("current" to "", "fragments" to "Keep incomplete sentences unfinished.",
                    "colloquial" to "Translate colloquial speech by its intended meaning; keep incomplete sentences unfinished.")) {
                    val messages = NativeTranslator.buildNumberedMessages(options, queries)
                    val system = messages.getJSONObject(0)
                    if (rule.isNotEmpty()) system.put("content", system.getString("content")
                        .replace("\nTranslate the following", "\n$rule\nTranslate the following"))
                    val tokens = model.begin(messages, maxOutputTokens = NativeTranslationResponse.outputBudget(queries))
                    assertTrue(tokens > 0)
                    val bytes = ByteArrayOutputStream()
                    while (true) bytes.write(model.next() ?: break)
                    output.put(JSONObject().put("page", page.getString("name")).put("variant", variant)
                        .put("promptTokens", tokens).put("raw", bytes.toString("UTF-8")))
                    report.writeText(output.toString(2))
                }
            }
        }
    }

    @Test fun translatesComplexFixtures() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testComplexNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = TranslationSettings(context).read()
        val options = selected.copy(backend = TranslationBackend.NATIVE_LLM,
            source = args.getString("source") ?: selected.source)
        val fixtureSet = args.getString("fixtureSet") ?: "translation-complex-fixtures"
        val label = args.getString("label") ?: "run"
        require(fixtureSet.matches(Regex("[a-zA-Z0-9-]+")) && label.matches(Regex("[a-zA-Z0-9-]+")))
        val root = File(context.getExternalFilesDir(null), fixtureSet)
        val names = if (args.getString("pages") == "all") root.listFiles()!!.filter { it.extension == "webp" }
            .sortedBy { it.name }.map { it.nameWithoutExtension }
        else args.getString("pages")?.split(",") ?: listOf("4218710-16dd4eafd3-00000009", "4218710-16dd4eafd3-00000014")
        require(names.isNotEmpty() && names.all { it.matches(Regex("[a-zA-Z0-9-]+")) })
        val report = File(root, "report-$label.json")
        val pages = JSONArray()
        val document = JSONObject().put("model", options.nativeModelName).put("source", options.source)
            .put("target", options.target).put("pages", pages)
        fun save() = report.writeText(document.toString(2))
        save()
        var activePage: JSONObject? = null
        val failures = mutableListOf<String>()
        val sourceLanguage = GallerySourceLanguage()
        fun resolved() = sourceLanguage.options(options)
        TranslationRuntime.lock.withLock {
            val models = TranslationModels(context).verified()
            GallerySourceTranslator({ resolved() }) { current ->
                NativeTranslator(context, current).apply {
                    inferenceObserver = { messages, raw, usage, cached, ms, failure ->
                        activePage!!.getJSONArray("requests").put(JSONObject().put("messages", messages).put("raw", raw)
                            .put("prompt", usage.promptTokens).put("completion", usage.completionTokens)
                            .put("cached", cached).put("ms", ms).put("failure", failure))
                        save()
                    }
                }
            }.use { translator ->
                TranslationEngineFactory.create(context, models, options, translator,
                    retainNativeModels = { true }, resolvedOptions = { resolved() }, onRecognized = { lines ->
                        if (options.source == TranslationLanguages.AUTO_SOURCE) sourceLanguage.observe(options, lines.map { it.text })
                        activePage?.put("ocrLines", JSONArray(lines.map { line ->
                            JSONObject().put("text", line.text).put("direction", line.direction)
                                .put("quad", JSONArray(line.quad.map { JSONArray(listOf(it.x, it.y)) }))
                        }))
                        save()
                    }).use { engine ->
                    for (name in names) {
                        val page = JSONObject().put("name", name)
                        activePage = page
                        pages.put(page)
                        page.put("requests", JSONArray())
                        val bitmap = GalleryTranslationSession.decodeBounded(File(root, "$name.webp"), options.backend)
                        try {
                            engine.prepare(bitmap).use { prepared ->
                                page.put("regions", JSONArray(prepared.regions.map { it.sourceText }))
                                page.put("resolvedSource", resolved().source)
                                page.put("regionDetails", JSONArray(prepared.regions.map { region ->
                                    JSONObject().put("lines", JSONArray(region.lines.map { it.text }))
                                        .put("bounds", JSONArray(listOf(region.x0, region.y0, region.x1, region.y1)))
                                }))
                                page.put("detectMs", prepared.detectMs).put("ocrMs", prepared.ocrMs)
                                save()
                                val result = engine.translatePrepared(bitmap, prepared, false)
                                page.put("result", result.toString())
                                page.put("translations", JSONArray(prepared.regions.map { it.translatedText }))
                                page.put("missing", prepared.translationResume(resolved().cacheIdentity())?.missingCount)
                                if (result is PageResult.Translated) {
                                    try {
                                        File(root, "$name-$label.png").outputStream().use {
                                            assertTrue(result.page.compress(Bitmap.CompressFormat.PNG, 100, it))
                                        }
                                    } finally { result.page.recycle() }
                                }
                                save()
                                if (args.getString("verify") == "true") {
                                    if (result !is PageResult.Translated ||
                                        prepared.translationResume(resolved().cacheIdentity())?.missingCount != 0 ||
                                        prepared.regions.any { NativeTranslationResponse.hasMarkers(it.translatedText) } ||
                                        prepared.regions.any {
                                            NativeTranslationResponse.generationFailure(it.translatedText, listOf(it.sourceText))
                                        }) failures.add("$name: $result")
                                }
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
