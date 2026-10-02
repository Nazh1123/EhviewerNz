package com.hippo.ehviewer.translation

import android.content.Intent
import com.hippo.ehviewer.ui.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = TranslationNavigationTest.TestApplication::class, sdk = [28])
class TranslationModelPreparationTest {
    @Test fun preparingMangaInMlKitModeNeverDownloadsLanguageModels() {
        val context = RuntimeEnvironment.getApplication()
        TranslationSettings(context).save(TranslationOptions(backend = TranslationBackend.ML_KIT, target = "fr"))
        val intent = Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TRANSLATION, true)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val manga = AtomicInteger()
            val language = AtomicInteger()
            val complete = AtomicInteger()
            TranslationModelPreparation(controller.get(), { complete.incrementAndGet() },
                downloadManga = { manga.incrementAndGet() },
                downloadLanguage = { language.incrementAndGet() }, checkWifi = {}).use { preparation ->
                preparation.prepareManga()
                awaitCompletion(complete)
                assertEquals(1, manga.get())
                assertEquals(0, language.get())
            }
        }
    }

    @Test fun preparingLanguageDoesNotContactMangaDownloadSourceEvenOnFailure() {
        val context = RuntimeEnvironment.getApplication()
        TranslationSettings(context).save(TranslationOptions(backend = TranslationBackend.ML_KIT, target = "fr"))
        val intent = Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TRANSLATION, true)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val manga = AtomicInteger()
            val language = AtomicInteger()
            val complete = AtomicInteger()
            TranslationModelPreparation(controller.get(), { complete.incrementAndGet() },
                downloadManga = { manga.incrementAndGet() },
                downloadLanguage = { target ->
                    assertEquals("fr", target)
                    language.incrementAndGet()
                    error("Google download unavailable")
                }, checkWifi = {}).use { preparation ->
                preparation.prepareMlKit()
                awaitCompletion(complete)
                assertEquals(0, manga.get())
                assertEquals(1, language.get())
            }
        }
    }

    private fun awaitCompletion(complete: AtomicInteger) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (complete.get() == 0 && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("Model preparation should finish", 1, complete.get())
    }
}
