package com.hippo.ehviewer.translation

import android.app.Application
import com.hippo.unifile.UniFile
import java.io.File
import li.joye.yakuyomi.engine.TranslationResume
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TranslationResultStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val cacheDir get() = File(temp.root, "cache")
    private val galleryDir get() = File(temp.root, "gallery").apply { mkdirs() }
    private val savedDir get() = File(galleryDir, "_translated")
    private val legacyDir get() = File(temp.root, "legacy")
    private fun store(enabled: Boolean = false, name: String = "001", gid: Long? = 1,
                      downloaded: Boolean = true) = TranslationResultStore(cacheDir, UniFile.fromFile(galleryDir),
        TranslationOptions(persistDownloaded = enabled), gid, downloaded, name)
    private fun TranslationResultStore.write(key: String, text: String) = writeImage(key) { it.write(text.toByteArray()) }
    private fun UniFile.readText() = openInputStream().bufferedReader().use { it.readText() }

    private fun configured(options: TranslationOptions, name: String = "001") =
        TranslationResultStore(cacheDir, UniFile.fromFile(galleryDir), options, 1, true, name)

    @Test fun automaticModeReopensResolvedCachesWithoutAnotherOcrOrTranslation() {
        val source = temp.newFile().apply { writeText("original pixels") }
        val auto = TranslationOptions(source = "auto", target = "fr")
        for (language in TranslationLanguages.detectedSources) {
            TranslationCache(cacheDir).clear()
            val resolved = configured(auto.copy(source = language))
            val key = resolved.key(source)
            resolved.write(key, language)
            val reopened = configured(auto)
            val hit = reopened.findResult(reopened.lookupKeys(source))!!
            assertEquals(key, hit.key)
            assertEquals(language, hit.image!!.readText())
            assertEquals(language, configured(auto.copy(target = "en")).findResult(
                configured(auto.copy(target = "en")).lookupKeys(source))!!.image!!.readText())
        }
    }

    @Test fun changingModelsOrLanguagesReusesTheSingleResultButChangedContentDoesNot() {
        val source = temp.newFile().apply { writeText("original pixels") }
        val options = TranslationOptions(source = "en", backend = TranslationBackend.LLM_API,
            apiModel = "model", target = "fr")
        val resolved = configured(options)
        resolved.write(resolved.key(source), "English to French")
        for (changed in listOf(options.copy(source = "ko"), options.copy(target = "en"),
                options.copy(inpaint = false), options.copy(apiModel = "other"),
                options.copy(backend = TranslationBackend.ML_KIT))) {
            val results = configured(changed)
            assertEquals("English to French", results.findResult(results.lookupKeys(source))!!.image!!.readText())
        }
        source.writeText("changed pixels")
        assertNull(resolved.findResult(resolved.lookupKeys(source)))
    }

    @Test fun previousJapaneseSparseOverlayKeysAreCompatibleAcrossBackends() {
        val source = temp.newFile().apply { writeText("original pixels") }
        for (backend in TranslationBackend.entries) {
            TranslationCache(cacheDir).clear()
            val options = TranslationOptions(backend = backend, target = "fr", nativeModelId = "model", apiModel = "api")
            // Identity frozen before multilingual OCR was introduced.
            val identity = listOf("ehnz-overlay-v1", "981ae85617bb3323949d57b7d6e3e10181435325",
                when (backend) {
                    TranslationBackend.NATIVE_LLM -> "llama-jni-v11-japanese-source-strict-regions-prefix-kv\nmodel"
                    TranslationBackend.ML_KIT -> "mlkit-17.0.3-ja"
                    TranslationBackend.LLM_API -> "llm-api-v3-segments-981ae856\nhttp://127.0.0.1:8080/v1/chat/completions\napi"
                }, if (backend == TranslationBackend.ML_KIT) "fr" else "ja\nfr", "true", options.inputImageIdentity).joinToString("\n")
            val legacyKey = "g1_" + TranslationCache(cacheDir).key(source, identity)
            File(cacheDir, "${legacyKey}_tl.png").writeText("compatible overlay")
            for (language in listOf("ja", "auto")) {
                val reopened = configured(options.copy(source = language))
                assertEquals("compatible overlay", reopened.findResult(reopened.lookupKeys(source))!!.image!!.readText())
            }
            val other = configured(options.copy(source = "en"))
            assertEquals("compatible overlay", other.findResult(other.lookupKeys(source))!!.image!!.readText())
        }
    }

    @Test fun resolvedPartialAndNoTextCachesCanBeAdoptedByAutomaticMode() {
        val source = temp.newFile().apply { writeText("original pixels") }
        val options = TranslationOptions(source = "ko", target = "fr")
        val resolved = configured(options)
        val key = resolved.key(source)
        val resume = TranslationResume(listOf("first", "second"), mapOf(0 to "premier"))
        resolved.writePartial(key, resume) { it.write("preview".toByteArray()) }
        val reopened = configured(options.copy(source = "auto", persistDownloaded = true))
        val hit = reopened.findResult(reopened.lookupKeys(source))!!
        assertNull(reopened.readResume(hit.key)) // Display is reusable; text continuation requires the same settings.
        assertEquals(resume, configured(options).readResume(hit.key))
        reopened.retain(hit.key, hit.image!!)
        TranslationCache(cacheDir).clear()
        assertEquals(resume, configured(options).readResume(hit.key))
        reopened.invalidate(hit.key)
        resolved.recordSkipped(key)
        assertNull(reopened.findResult(reopened.lookupKeys(source))!!.image)
        TranslationCache(cacheDir).clear()
        assertTrue(reopened.isSkipped(key))
    }

    @Test fun completeResolvedCacheWinsOverAnUnresolvedPartial() {
        val source = temp.newFile().apply { writeText("original pixels") }
        val options = TranslationOptions(source = "auto")
        val auto = configured(options)
        auto.writePartial(auto.key(source), TranslationResume(listOf("a", "b"), mapOf(0 to "a"))) { it.write(1) }
        val resolved = configured(options.copy(source = "en"))
        resolved.write(resolved.key(source), "complete")
        assertEquals("complete", auto.findResult(auto.lookupKeys(source))!!.image!!.readText())
    }

    @Test fun namedSavedResultsAreAccessibleWithoutGalleryMetadataOrOriginalContent() {
        store(true, "Chapter/001").write("g1_content", "overlay")
        val reopened = store(name = "Chapter/001", gid = null)
        assertEquals("overlay", reopened.findSavedResult()!!.image!!.readText())
        assertEquals("overlay", reopened.existingImage("different-content")!!.readText())
    }

    @Test fun oneContentKeyReplacesItsImageAndCheckpointAcrossSettingsAndStates() {
        val source = temp.newFile().apply { writeText("original pixels") }
        val first = configured(TranslationOptions(nativeModelId = "first"))
        val key = first.key(source)
        first.write(key, "first model")
        val second = configured(TranslationOptions(nativeModelId = "second", source = "en", target = "fr"))
        assertEquals(key, second.key(source))
        assertEquals("first model", second.findResult(second.lookupKeys(source))!!.image!!.readText())
        second.writePartial(key, TranslationResume(listOf("a", "b"), mapOf(0 to "a"))) { it.write(1) }
        assertEquals(setOf("${key}_tl.partial", "${key}_tl.regions"), cacheDir.list()!!.toSet())
        assertNull(first.readResume(key))
        second.write(key, "second model")
        assertEquals(setOf("${key}_tl.png"), cacheDir.list()!!.toSet())
        second.recordSkipped(key)
        assertEquals(setOf("${key}_tl.skip"), cacheDir.list()!!.toSet())
        second.write(key, "translated again")
        assertEquals(setOf("${key}_tl.png"), cacheDir.list()!!.toSet())
    }

    @Test fun adoptingOldConfigurationCachePublishesOnlyTheCanonicalResultAndKeepsItsCheckpoint() {
        val source = temp.newFile().apply { writeText("original pixels") }
        val options = TranslationOptions(source = "en")
        val previousKey = "g1_" + TranslationCache(cacheDir).key(source, options.cacheIdentity())
        val results = configured(options)
        val resume = TranslationResume(listOf("a", "b"), mapOf(0 to "a"))
        results.writePartial(previousKey, resume) { it.write(1) }
        val canonical = results.key(source)
        val hit = results.findResult(results.lookupKeys(source))!!
        assertEquals(previousKey, hit.key)
        results.retain(canonical, hit.image!!)
        results.discardCachedAliases(results.lookupKeys(source), canonical)
        assertEquals(setOf("${canonical}_tl.partial", "${canonical}_tl.regions"), cacheDir.list()!!.toSet())
        assertEquals(resume, results.readResume(canonical))
    }

    @Test fun savedOverlayIsFoundDirectlyByNameWithoutAnIndexOrMatchingHash() {
        val results = store(true)
        assertEquals("001_tl.png", results.write("g1_hash", "overlay").name)
        assertEquals("overlay", store().existingImage("g1_different_hash")?.readText())
        assertNull(store(name = "002").existingImage("g1_hash"))
        assertEquals(setOf("001_tl.png"), savedDir.list()!!.toSet())
    }

    @Test fun sameNamedPagesInDifferentChaptersKeepTheirDirectoryHierarchy() {
        store(true, "Chapter A/001").write("g1_a", "first")
        store(true, "Chapter B/001").write("g1_b", "second")
        assertEquals("first", store(name = "Chapter A/001").existingImage("unused")?.readText())
        assertEquals("second", store(name = "Chapter B/001").existingImage("unused")?.readText())
        assertTrue(File(savedDir, "Chapter A/001_tl.png").isFile)
        assertTrue(File(savedDir, "Chapter B/001_tl.png").isFile)
    }

    @Test fun translatingTheSameSourceReplacesItsNamedOverlayWithoutSuffixes() {
        store(true).write("g1_zh", "Chinese")
        store(true).write("g1_en", "English")
        assertEquals("English", store().existingImage("unused")?.readText())
        assertEquals(setOf("001_tl.png"), savedDir.list()!!.toSet())
    }

    @Test fun savedOverlayCanBeOpenedWithoutAnyMetadata() {
        savedDir.mkdirs()
        File(savedDir, "001_tl.png").writeText("external overlay")
        assertEquals("external overlay", store().existingImage("unused")?.readText())
    }

    @Test fun oldHashNamedImagesAreNotLoadedOrMigrated() {
        savedDir.mkdirs(); legacyDir.mkdirs(); cacheDir.mkdirs()
        File(savedDir, "g1_hash.png").writeText("old full page")
        File(legacyDir, "g1_hash.png").writeText("legacy full page")
        File(cacheDir, "g1_hash.png").writeText("old cache")
        assertNull(store(true).existingImage("g1_hash"))
        assertEquals(setOf("g1_hash.png"), savedDir.list()!!.toSet())
    }

    @Test fun partialPreviewAndCheckpointSurviveCacheAdoptionAndReopening() {
        val key = "g1_partial"
        val resume = TranslationResume(listOf("a", "b"), mapOf(0 to "译文"))
        store().writePartial(key, resume) { it.write("preview".toByteArray()) }
        val persistent = store(true)
        assertEquals("001_tl.partial", persistent.retain(key, persistent.existingImage(key)!!).name)
        TranslationCache(cacheDir).clear()
        assertEquals("preview", store().existingImage(key)?.readText())
        assertEquals(resume, store().readResume(key))
        assertNull(store().readResume("different-configuration"))
        persistent.write(key, "complete")
        assertEquals(setOf("001_tl.png"), savedDir.list()!!.toSet())
        assertNull(store().readResume(key))
    }

    @Test fun brokenOrMissingCheckpointKeepsThePreviewPartial() {
        val key = "g1_partial"
        store().writePartial(key, TranslationResume(listOf("a", "b"), mapOf(0 to "译文"))) { it.write(1) }
        val checkpoint = File(cacheDir, "${key}_tl.regions")
        for (json in listOf("broken", """{"version":1,"key":"wrong","regions":["a"],"translations":{"0":"wrong"}}""",
                """{"version":1,"key":"$key","regions":["a"],"translations":{"4":"wrong"}}""")) {
            checkpoint.writeText(json)
            assertNull(store().readResume(key))
            assertTrue(TranslationResultStore.isPartial(store().existingImage(key)!!))
        }
        checkpoint.delete()
        assertNull(store().readResume(key))
        assertNotNull(store().existingImage(key))
    }

    @Test fun cachePartialAndCheckpointAreEvictedTogether() {
        store().writePartial("g1_partial", TranslationResume(listOf("a", "b"), mapOf(0 to "译文"))) { it.write(ByteArray(200)) }
        TranslationCache(cacheDir, 1).prune()
        assertTrue(cacheDir.list()!!.isEmpty())
    }

    @Test fun persistenceRequiresOptInAndWritableGalleryIdentity() {
        for (results in listOf(store(), store(true, downloaded = false), store(true, gid = null))) {
            assertEquals(File(cacheDir, "g1_page_tl.png").absolutePath, results.write("g1_page", "overlay").uri.path)
        }
        assertEquals(File(savedDir, "001_tl.png").absolutePath, store(true).write("g1_page", "overlay").uri.path)
        val unavailable = TranslationResultStore(cacheDir, null, TranslationOptions(persistDownloaded = true), 1, true, "001")
        assertEquals(File(cacheDir, "g1_page_tl.png").absolutePath, unavailable.write("g1_page", "overlay").uri.path)
    }

    @Test fun savedResultsSurviveCacheClearingAndDisablingPersistence() {
        store(true).write("g1_page", "overlay")
        store(true, "002").recordSkipped("g1_empty")
        TranslationCache(cacheDir, 0).prune()
        TranslationCache(cacheDir).clear()
        assertEquals("overlay", store().existingImage("unused")?.readText())
        assertTrue(store(name = "002").isSkipped("unused"))
    }

    @Test fun enablingPersistenceAdoptsCurrentCacheWithoutEvictingOtherPendingPages() {
        store().write("g1_one", "first")
        store().write("g1_two", "second")
        store().recordSkipped("g1_empty")
        val first = store(true)
        first.retain("g1_one", first.existingImage("g1_one")!!)
        assertTrue(store(true, "003").isSkipped("g1_empty"))
        val second = store(true, "002")
        second.retain("g1_two", second.existingImage("g1_two")!!)
        TranslationCache(cacheDir).clear()
        assertEquals("first", store().existingImage("unused")?.readText())
        assertEquals("second", store(name = "002").existingImage("unused")?.readText())
    }

    @Test fun selectedDeletionRemovesNestedNamedOverlaysAndPreservesOriginalsAndUnrelatedFiles() {
        store(true, "Chapter/001").write("g1_page", "overlay")
        store().write("g1_page", "cache")
        store(gid = 10).write("g10_page", "other gallery cache")
        val original = File(galleryDir, "001.jpg").apply { writeText("original") }
        val unrelated = File(savedDir, "notes.txt").apply { writeText("notes") }
        val previous = File(savedDir, "g1_${"a".repeat(64)}.png").apply { writeText("old full page") }
        assertTrue(TranslationResultStore.deleteGalleries(cacheDir, legacyDir, setOf(1), mapOf(1L to UniFile.fromFile(galleryDir)!!)))
        assertFalse(File(savedDir, "Chapter/001_tl.png").exists())
        assertFalse(previous.exists())
        assertEquals("other gallery cache", File(cacheDir, "g10_page_tl.png").readText())
        assertEquals("original", original.readText())
        assertEquals("notes", unrelated.readText())
    }

    @Test fun retryInvalidatesOnlyTheRequestedOriginalNameAndItsCache() {
        store(true).write("g1_one", "first")
        store(true, "002").write("g1_two", "second")
        store().write("g1_one", "cached")
        store().invalidate("g1_one")
        assertNull(store().existingImage("g1_one"))
        assertEquals("second", store(name = "002").existingImage("unused")?.readText())
    }

    @Test fun interruptedWritePreservesPreviousOverlayAndRemovesTemporaryFile() {
        val results = store(true)
        results.write("g1_page", "complete")
        assertThrows(IllegalStateException::class.java) {
            results.writeImage("g1_page") { it.write(1); error("interrupted") }
        }
        assertEquals("complete", store().existingImage("unused")?.readText())
        assertEquals(listOf("001_tl.png"), savedDir.list()!!.toList())
    }

    @Test fun sourceNamesCannotEscapeTheTranslatedDirectory() {
        assertThrows(IllegalArgumentException::class.java) { store(true, "../001").write("g1_page", "overlay") }
        assertFalse(File(galleryDir, "001_tl.png").exists())
    }
}
