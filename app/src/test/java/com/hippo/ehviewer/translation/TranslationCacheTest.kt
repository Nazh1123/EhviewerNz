package com.hippo.ehviewer.translation

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preprocessingKeyTracksInputResolutionAndIgnoresLanguageAndRequestSettings() {
        val cache = TranslationCache(temp.newFolder("prepared"))
        val source = temp.newFile("source").apply { writeText("pixels") }
        val options = TranslationOptions()
        val original = cache.preparationKey(source, options)
        assertEquals(original, cache.preparationKey(source, options.copy(target = "en", ahead = 10,
            apiModel = "another", apiKey = "secret")))
        val api = options.copy(backend = TranslationBackend.LLM_API)
        assertEquals(original, cache.preparationKey(source, api))
        assertNotEquals(original, cache.preparationKey(source, options.copy(backend = TranslationBackend.ML_KIT)))
        assertEquals(cache.preparationKey(source, api), cache.preparationKey(source,
            api.copy(target = "en", ahead = 10, apiModel = "another", apiKey = "secret")))
        assertNotEquals(original, cache.preparationKey(source, options.copy(inpaint = false)))
        source.writeText("new pixels")
        assertNotEquals(original, cache.preparationKey(source, options))
    }

    @Test fun resultsDependOnlyOnContentAndReuseAcrossModelAndLanguageChanges() {
        val cache = TranslationCache(temp.newFolder("cache"))
        val first = temp.newFile("page1").apply { writeText("same image") }
        val second = temp.newFile("page2").apply { writeText("same image") }
        val options = TranslationOptions()
        val key = cache.key(first, options)
        assertEquals(key, cache.key(second, options))
        assertEquals(key, cache.key(first, options.copy(target = "en", source = "ko")))
        assertEquals(key, cache.key(first, options.copy(inpaint = false, nativeModelId = "another")))
        assertEquals(key, cache.key(first, options.copy(backend = TranslationBackend.LLM_API, apiModel = "another")))
        second.writeText("updated gallery page")
        assertNotEquals(key, cache.key(second, options))
    }

    @Test fun pruningEvictsOldestResultAndNeverTouchesOriginal() {
        val original = temp.newFile("source.png").apply { writeText("original") }
        val cache = TranslationCache(temp.newFolder("cache"), 12)
        val old = cache.image("old").apply { writeText("12345678"); setLastModified(1000) }
        val recent = cache.image("recent").apply { writeText("abcdefgh"); setLastModified(2000) }
        cache.prune()
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertEquals("original", original.readText())
    }

    @Test fun retryClearsOnlySelectedPageAndClearIsScopedToCacheArtifacts() {
        val dir = temp.newFolder("cache")
        val cache = TranslationCache(dir)
        cache.image("one").writeText("image")
        cache.skipped("one").writeText("skipped")
        cache.image("two").writeText("other")
        val unrelated = File(dir, "keep.txt").apply { writeText("keep") }
        cache.invalidate("one")
        assertFalse(cache.image("one").exists())
        assertFalse(cache.skipped("one").exists())
        assertTrue(cache.image("two").exists())
        cache.clear()
        assertFalse(cache.image("two").exists())
        assertTrue(unrelated.exists())
    }
}
