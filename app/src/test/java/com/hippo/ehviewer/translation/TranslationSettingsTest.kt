package com.hippo.ehviewer.translation

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TranslationSettingsTest {
    @Test fun deletingModelRetainsOnlyItsResultIdentityAndNewSelectionTakesPrecedence() {
        val settings = TranslationSettings(RuntimeEnvironment.getApplication())
        val original = TranslationOptions(nativeModelId = "a".repeat(64), nativeModelName = "old.gguf")
        settings.save(original)
        settings.clearNativeModel()
        assertEquals("", settings.read().nativeModelId)
        assertEquals(original.cacheIdentity(), settings.readForResults().cacheIdentity())
        settings.save(settings.read().copy(target = "en"))
        assertNotEquals(original.cacheIdentity(), settings.readForResults().cacheIdentity())
        settings.selectNativeModel("b".repeat(64), "new.gguf")
        assertEquals("b".repeat(64), settings.readForResults().nativeModelId)
        settings.save(settings.read().withBackend(TranslationBackend.ML_KIT))
        assertEquals(TranslationBackend.ML_KIT, settings.readForResults().backend)
    }

    @Test fun allSdkTargetsPersistAndUseTheirOwnPromptNames() {
        val settings = TranslationSettings(RuntimeEnvironment.getApplication())
        assertEquals(com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages().toSet(),
            TranslationLanguages.mlKitTargets.toSet())
        for (code in TranslationLanguages.mlKitTargets) {
            val options = TranslationOptions(target = code, backend = TranslationBackend.ML_KIT)
            settings.save(options)
            assertEquals(options, settings.read())
            assertEquals("Japanese", options.engineConfig().translator.fromLangName)
            assertEquals(options.targetLanguageName(), options.engineConfig().translator.toLangName)
            if (code != "zh") assertNotEquals("Simplified Chinese", options.targetLanguageName())
        }
        assertEquals("French", TranslationOptions(target = "fr").targetLanguageName())
        assertEquals("Japanese", TranslationOptions(target = "ja").targetLanguageName())
        assertEquals("Traditional Chinese (Hong Kong)", TranslationOptions(target = "zh-HK").targetLanguageName())
        assertEquals("Traditional Chinese (Taiwan)", TranslationOptions(target = "zh-TW").targetLanguageName())
    }

    @Test fun independentLlmTargetsPersistForBothBackends() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        assertEquals(listOf("de", "en", "es", "fr", "ja", "ko", "th", "zh-CN", "zh-HK", "zh-TW"),
            TranslationLanguages.llmTargets)
        for (backend in listOf(TranslationBackend.NATIVE_LLM, TranslationBackend.LLM_API)) {
            for (target in TranslationLanguages.llmTargets) {
                val options = TranslationOptions(target = target, backend = backend)
                settings.save(options)
                assertEquals(options, settings.read())
                assertFalse(options.targetLanguageName().isBlank())
                assertEquals(options.targetLanguageName(), options.engineConfig().translator.toLangName)
            }
        }
        assertEquals("zh", TranslationOptions(target = "zh-TW").withBackend(TranslationBackend.ML_KIT).target)
        assertEquals("zh", TranslationOptions().withBackend(TranslationBackend.ML_KIT).target)
        assertEquals("fr", TranslationOptions(target = "fr").withBackend(TranslationBackend.ML_KIT).target)
        for (invalid in listOf("system", "custom", "zh-Hant", "invalid", "it")) {
            assertThrows(IllegalArgumentException::class.java) {
                settings.save(TranslationOptions(target = invalid))
            }
        }
    }

    @Test fun savedLegacyLanguagesMigrateAndInvalidValuesFallBack() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        val prefs = context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE)
        for (backend in listOf("NATIVE_LLM", "LLM_API")) {
            for ((saved, expected) in listOf("zh" to "zh-CN", "zh-Hant" to "zh-TW",
                    "system" to "zh-CN", "custom" to "zh-CN", "it" to "zh-CN", "fr" to "fr")) {
                prefs.edit().putString("target", saved).putString("backend", backend).commit()
                assertEquals(expected, settings.read().target)
            }
        }
        context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE).edit()
            .putString("target", "zh-Hant").putString("backend", "ML_KIT").commit()
        assertEquals("zh", settings.read().target)
    }

    @Test fun legacySourcesAreIgnoredAndRemovedWithoutChangingSelectedTargetOrOcr() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        val prefs = context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE)
        for (backend in TranslationBackend.entries) {
            val options = TranslationOptions(target = "fr", backend = backend)
            settings.save(options)
            for (source in listOf("auto", "ja", "en", "invalid")) {
                prefs.edit().putString("source", source).commit()
                val restored = settings.read()
                assertEquals(options, restored)
                assertEquals("Japanese", restored.engineConfig().translator.fromLangName)
                assertEquals("French", restored.engineConfig().translator.toLangName)
                assertEquals("明日は学校へ行きます。", restored.sampleText())
                assertEquals(options.preparationIdentity(), restored.preparationIdentity())
                assertEquals(options.cacheIdentity(), restored.cacheIdentity())
                settings.save(restored)
                assertFalse(prefs.contains("source"))
            }
            if (backend != TranslationBackend.ML_KIT) {
                assertTrue(options.cacheIdentity().contains("\nja\nfr\n"))
                assertNotEquals(options.copy(target = "zh-HK").cacheIdentity(), options.copy(target = "zh-TW").cacheIdentity())
            }
        }
    }

    @Test fun chosenTargetPersistsIndependentlyOfDeviceAndAppLocales() {
        val resources = Resources.getSystem()
        val original = android.content.res.Configuration(resources.configuration)
        val defaultLocale = Locale.getDefault()
        try {
            val options = TranslationOptions(target = "zh-HK")
            val identity = options.cacheIdentity()
            val settings = TranslationSettings(RuntimeEnvironment.getApplication())
            settings.save(options)
            for (tag in listOf("en-US", "zh-HK", "zh-TW", "zh-CN")) {
                val config = android.content.res.Configuration(original).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(tag)))
                }
                resources.updateConfiguration(config, resources.displayMetrics)
                Locale.setDefault(Locale.FRENCH)
                assertEquals(options, settings.read())
                assertEquals("Traditional Chinese (Hong Kong)", settings.read().targetLanguageName())
                assertEquals("Traditional Chinese (Hong Kong)", settings.read().engineConfig().translator.toLangName)
                assertEquals(identity, settings.read().cacheIdentity())
            }
        } finally {
            resources.updateConfiguration(original, resources.displayMetrics)
            Locale.setDefault(defaultLocale)
        }
    }

    @Test fun storageSettingsDefaultOffPersistAndDoNotChangeResultIdentity() {
        val settings = TranslationSettings(RuntimeEnvironment.getApplication())
        val defaults = settings.read()
        assertFalse(defaults.persistDownloaded)
        assertEquals(256, defaults.cacheSizeMb)
        for (size in listOf(128, 256, 512, 768, 1024)) {
            val selected = defaults.copy(persistDownloaded = true, cacheSizeMb = size)
            settings.save(selected)
            assertEquals(selected, TranslationSettings(RuntimeEnvironment.getApplication()).read())
            assertEquals(size * 1024L * 1024L, selected.cacheLimitBytes)
            assertEquals(defaults.cacheIdentity(), selected.cacheIdentity())
            assertEquals(defaults.preparationIdentity(), selected.preparationIdentity())
        }
        assertThrows(IllegalArgumentException::class.java) { settings.save(defaults.copy(cacheSizeMb = 100)) }
        RuntimeEnvironment.getApplication().getSharedPreferences("manga_translation", Context.MODE_PRIVATE)
            .edit().putInt("cache_size_mb", -1).commit()
        assertEquals(256, settings.read().cacheSizeMb)
    }

    @Test fun apiSettingsPersistAndCacheUsesBackendEndpointModelButNotSecrets() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        assertEquals(TranslationBackend.NATIVE_LLM, settings.read().backend)
        val api = TranslationOptions(backend = TranslationBackend.LLM_API, apiModel = "hy-mt", apiKey = "secret")
        settings.save(api)
        assertEquals(api, TranslationSettings(context).read())
        assertNotEquals(TranslationOptions().cacheIdentity(), api.cacheIdentity())
        assertNotEquals(api.cacheIdentity(), api.copy(apiModel = "other").cacheIdentity())
        assertNotEquals(api.cacheIdentity(), api.copy(apiUrl = "https://example.com/v1/chat/completions").cacheIdentity())
        assertEquals(api.cacheIdentity(), api.copy(apiKey = "new-secret", ahead = 0).cacheIdentity())
        assertFalse(api.cacheIdentity().contains("secret"))
        assertEquals(TranslationOptions().cacheIdentity(), TranslationOptions(apiModel = "unused").cacheIdentity())
    }

    @Test fun apiUrlAllowsHttpAndHttpsOnAnyHostButRejectsInvalidAddresses() {
        listOf("http://127.0.0.1:8080/v1/chat/completions", "http://localhost:8080/v1/chat/completions",
            "http://[::1]:8080/v1/chat/completions", "http://192.168.1.100:8080/v1/chat/completions",
            "http://10.0.0.5:1234/v1/chat/completions", "http://172.16.0.5:8000/v1/chat/completions",
            "http://llm-pc.local:8080/v1/chat/completions", "http://[fd00::1234]:8080/v1/chat/completions",
            "http://example.com/v1/chat/completions", "https://example.com/v1/chat/completions",
            "https://192.168.1.100:8443/v1/chat/completions").forEach {
            assertTrue(it, TranslationOptions(apiUrl = it).validApiUrl())
        }
        listOf("", "file:///tmp/model", "ftp://example.com/api", "https://",
            "192.168.1.100:8080/v1/chat/completions", "http://192.168.1.100:65536/api",
            "http://user:password@192.168.1.100:8080/api", "http://192.168.1.100/api#fragment",
            "https://user:password@example.com/api", "https://example.com/api#fragment").forEach {
            assertFalse(it, TranslationOptions(apiUrl = it).validApiUrl())
        }
    }

    @Test fun lanAndRemoteApiAddressesPersistAfterTrimming() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        listOf("http://192.168.1.100:8080/v1/chat/completions",
            "http://llm-pc.local:1234/v1/chat/completions", "http://[fd00::1234]:8080/v1/chat/completions",
            "http://example.com/v1/chat/completions", "https://example.com/v1/chat/completions").forEach { endpoint ->
            val options = TranslationOptions(backend = TranslationBackend.LLM_API, apiUrl = "  $endpoint  ",
                apiModel = "chosen-model")
            settings.save(options)
            assertEquals(options.copy(apiUrl = endpoint), TranslationSettings(context).read())
        }
    }

    @Test fun nativeDefaultPreservesExistingBackendAndSeparatesModelCaches() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        assertEquals(TranslationBackend.NATIVE_LLM, TranslationOptions().backend)
        assertEquals(TranslationBackend.NATIVE_LLM, settings.read().backend)
        for (backend in TranslationBackend.entries) {
            val options = TranslationOptions(nativeModelId = "a".repeat(64), nativeModelName = "test.gguf").withBackend(backend)
            settings.save(options)
            assertEquals(options, TranslationSettings(context).read())
        }
        val native = TranslationOptions(nativeModelId = "a".repeat(64))
        assertNotEquals(native.cacheIdentity(), native.copy(nativeModelId = "b".repeat(64)).cacheIdentity())
        assertEquals(native.cacheIdentity(), native.copy(nativeModelName = "renamed.gguf").cacheIdentity())
        assertNotEquals(native.preparationIdentity(), native.copy(backend = TranslationBackend.ML_KIT).preparationIdentity())
        assertEquals(native.copy(backend = TranslationBackend.ML_KIT).cacheIdentity(),
            native.copy(backend = TranslationBackend.ML_KIT, nativeModelId = "b".repeat(64)).cacheIdentity())
        context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE).edit().putString("backend", "invalid").commit()
        assertEquals(TranslationBackend.NATIVE_LLM, settings.read().backend)
        assertThrows(IllegalArgumentException::class.java) { settings.save(native.copy(nativeModelId = "../model")) }
    }

    @Test fun llmBackendsUseOriginalSizeAndReaderOcrWhileNativePagesStaySequential() {
        val native = TranslationOptions()
        val api = native.copy(backend = TranslationBackend.LLM_API)
        val mlkit = native.copy(backend = TranslationBackend.ML_KIT)
        assertEquals(com.hippo.ehviewer.translation.engine.OcrConfig(), native.engineConfig().ocr)
        assertEquals(com.hippo.ehviewer.translation.engine.OcrConfig(), api.engineConfig().ocr)
        assertEquals(com.hippo.ehviewer.translation.engine.OcrConfig(), mlkit.engineConfig().ocr)
        assertNotEquals(api.preparationIdentity(), mlkit.preparationIdentity())
        assertEquals(native.preparationIdentity(), api.preparationIdentity())
        assertNotEquals(native.preparationIdentity(), mlkit.preparationIdentity())
        assertEquals(8, api.pageConcurrency)
        assertEquals(1, native.pageConcurrency)
        assertEquals(1, mlkit.pageConcurrency)
        assertTrue(api.preparationIdentity().contains("original-size"))
        assertTrue(api.cacheIdentity().contains("original-size"))
        assertFalse(api.cacheIdentity().contains("max-edge-2048"))
        assertTrue(native.cacheIdentity().contains("llama-jni-v13-reader-regions-prefix-kv"))
        assertFalse(native.cacheIdentity().contains("llama-jni-v10-strict-regions-prefix-kv"))
        assertFalse(native.cacheIdentity().contains("llama-jni-v8-user-manga-prefix-kv"))
        assertFalse(native.cacheIdentity().contains("llama-jni-v7-user-manga-prefix-kv"))
        assertFalse(native.cacheIdentity().contains("llama-jni-v6-compact-manga-prefix-kv"))
        assertFalse(native.cacheIdentity().contains("llama-jni-v5-prefix-kv"))
        assertTrue(api.cacheIdentity().contains("llm-api-v5-reader-regions"))
        assertTrue(mlkit.cacheIdentity().contains("mlkit-17.0.3-multilingual"))
        assertFalse(api.cacheIdentity().contains("llm-api-v1\n"))
    }

    @Test fun defaultAndSavedWindowSurviveReopeningSettings() {
        val context = RuntimeEnvironment.getApplication()
        val settings = TranslationSettings(context)
        assertEquals(2, settings.read().ahead)
        settings.save(TranslationOptions("en", false, 10))
        assertEquals(TranslationOptions("en", false, 10), TranslationSettings(context).read())
        settings.save(settings.read().copy(ahead = 0))
        assertEquals(0, TranslationSettings(context).read().ahead)
    }
    @Test fun corruptedWindowIsClampedAndInvalidInputRejected() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("manga_translation", Context.MODE_PRIVATE).edit().putInt("ahead", 1000).commit()
        assertEquals(10, TranslationSettings(context).read().ahead)
        assertThrows(IllegalArgumentException::class.java) { TranslationSettings(context).save(TranslationOptions(ahead = -1)) }
        assertThrows(IllegalArgumentException::class.java) { TranslationSettings(context).save(TranslationOptions(ahead = 11)) }
    }
}
