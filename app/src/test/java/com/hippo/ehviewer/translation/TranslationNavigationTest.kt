package com.hippo.ehviewer.translation

import android.content.Context
import android.content.Intent
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.ehviewer.ui.fragment.SettingsHeaders
import com.hippo.ehviewer.ui.fragment.TranslationFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = TranslationNavigationTest.TestApplication::class, sdk = [28], qualifiers = "zh-rCN")
class TranslationNavigationTest {
    class TestApplication : EhApplication() {
        override fun onCreate() {
            // Settings UI needs preferences, not production networking / native image initialization.
            ReflectionHelpers.setStaticField(Settings::class.java, "sSettingsPre",
                getSharedPreferences("navigation-test", Context.MODE_PRIVATE))
        }
    }

    @Test fun modelManagementOpensOneSubpageForAllBackendsAndReturnsToSettings() {
        val intent = Intent(RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_TRANSLATION, true)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            val manager = activity.supportFragmentManager
            manager.executePendingTransactions()
            val settings = manager.findFragmentById(R.id.settings) as TranslationFragment
            val management = requireNotNull(settings.findPreference<androidx.preference.Preference>("translation_model_management"))
            assertEquals("模型管理", management.title)
            for (oldKey in listOf("translation_native_auto_import", "translation_prepare", "translation_prepare_mlkit"))
                assertNull(settings.findPreference<androidx.preference.Preference>(oldKey))
            val backend = requireNotNull(settings.findPreference<ListPreference>("translation_backend"))
            for (mode in listOf("NATIVE_LLM", "LLM_API", "ML_KIT")) {
                assertTrue(backend.callChangeListener(mode))
                assertTrue(management.isVisible)
            }
            management.performClick()
            manager.executePendingTransactions()
            val page = manager.findFragmentById(R.id.settings) as com.hippo.ehviewer.ui.fragment.TranslationModelsFragment
            assertEquals("模型管理", activity.supportActionBar?.title)
            val root = requireNotNull(page.view)
            fun button(tag: String) = requireNotNull(root.findViewWithTag<com.google.android.material.button.MaterialButton>(tag))
            assertNotNull(root.findViewWithTag<android.view.View>("translation_manga_bundle"))
            button("translation_manga_bundle/action/${R.string.translation_model_import}").performClick()
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing)
            assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString().contains("ocr_48px_ctc.ncnn.bin"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            button("translation_model_tab_1").performClick()
            assertNotNull(root.findViewWithTag<android.view.View>("translation_native_import"))
            for (model in NativeModelCatalog.models) {
                assertNotNull(root.findViewWithTag<android.view.View>("translation_native_${model.sha256}"))
                assertEquals("下载", button("translation_native_${model.sha256}/action/${R.string.translation_model_download}").text.toString())
            }
            button("translation_model_tab_2").performClick()
            val targetBefore = TranslationSettings(activity).read().target
            button("translation_language_download/action/${R.string.translation_model_choose_language}").performClick()
            val languages = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val index = TranslationLanguages.mlKitTargets.indexOf("fr")
            languages.listView.performItemClick(android.view.View(activity), index, index.toLong())
            assertEquals(targetBefore, TranslationSettings(activity).read().target)
            assertTrue(manager.popBackStackImmediate())
            assertTrue(manager.findFragmentById(R.id.settings) is TranslationFragment)
            assertEquals("翻译", activity.supportActionBar?.title)
        }
    }
    @Test fun targetLanguageRowOpensVisibleChoiceListAndSavesClickedItems() {
        val intent = Intent(RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_TRANSLATION, true)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            val manager = activity.supportFragmentManager
            manager.executePendingTransactions()
            val fragment = manager.findFragmentById(R.id.settings) as TranslationFragment
            assertNull(fragment.findPreference<ListPreference>("translation_source"))
            val target = requireNotNull(fragment.findPreference<ListPreference>("translation_target"))
            val backend = requireNotNull(fragment.findPreference<ListPreference>("translation_backend"))
            fun choose(preference: ListPreference, code: String) {
                preference.performClick()
                manager.executePendingTransactions()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val list = requireNotNull(dialog.listView)
                assertTrue("${preference.key} must display its choices, not only help text", list.isShown)
                assertEquals(preference.entries.size, list.adapter.count)
                assertEquals(preference.findIndexOfValue(preference.value), list.checkedItemPosition)
                val index = preference.findIndexOfValue(code)
                assertTrue(index >= 0)
                list.performItemClick(list.getChildAt(index) ?: android.view.View(activity), index,
                    list.adapter.getItemId(index))
                manager.executePendingTransactions()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
                assertEquals(code, preference.value)
            }
            val appLanguage = Settings.getAppLanguage()
            for (mode in listOf("NATIVE_LLM", "LLM_API")) {
                assertTrue(backend.callChangeListener(mode))
                for (code in listOf("fr", "zh-HK", "zh-TW", "zh-CN")) {
                    choose(target, code)
                    assertEquals(code, TranslationSettings(activity).read().target)
                    assertTrue(target.summary.toString().startsWith("日语 → ${target.entry}"))
                }
            }
            assertEquals(appLanguage, Settings.getAppLanguage())
        }
    }

    @Test fun llmLanguagesHaveIndependentOptionsAndMlKitKeepsItsExistingChoices() {
        val intent = Intent(RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_TRANSLATION, true)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as TranslationFragment
            assertNull(fragment.findPreference<ListPreference>("translation_source"))
            val target = requireNotNull(fragment.findPreference<ListPreference>("translation_target"))
            val backend = requireNotNull(fragment.findPreference<ListPreference>("translation_backend"))
            val entries = listOf("德语", "英语", "西班牙语", "法语", "日语", "韩语", "泰语",
                "简体中文（大陆）", "繁体中文（香港）", "繁体中文（台湾）")
            val values = listOf("de", "en", "es", "fr", "ja", "ko", "th", "zh-CN", "zh-HK", "zh-TW")
            assertEquals(values, TranslationLanguages.llmTargets)
            for (mode in listOf("NATIVE_LLM", "LLM_API")) {
                assertTrue(backend.callChangeListener(mode))
                assertEquals(entries, target.entries.map { it.toString() })
                assertEquals(values, target.entryValues.map { it.toString() })
                assertNull(fragment.findPreference<ListPreference>("translation_source"))
                for (code in values) {
                    assertTrue(target.callChangeListener(code))
                    assertEquals(code, TranslationSettings(activity).read().target)
                }
                assertTrue(target.summary.toString().startsWith("日语 → ${target.entry}"))
                assertFalse(target.callChangeListener("custom"))
            }
            assertTrue(target.callChangeListener("zh-TW"))
            assertEquals("繁体中文（台湾）", target.entry.toString())
            assertTrue(backend.callChangeListener("ML_KIT"))
            assertEquals(TranslationLanguages.mlKitTargets, target.entryValues.map { it.toString() })
            assertEquals("zh", target.value)
            assertNull(fragment.findPreference<ListPreference>("translation_source"))
            assertTrue(target.summary.toString().startsWith("日语 → ${target.entry}"))
            assertFalse(target.entryValues.contains("system"))
            assertTrue(target.callChangeListener("fr"))
            assertEquals("fr", TranslationSettings(activity).read().target)
            assertTrue(backend.callChangeListener("LLM_API"))
            assertEquals("fr", target.value)
            assertNull(fragment.findPreference<ListPreference>("translation_source"))
            assertTrue(target.summary.toString().startsWith("日语 → ${target.entry}"))
            assertNull(fragment.findPreference<androidx.preference.Preference>("translation_custom_target"))
        }
    }

    @Test fun settingsHeaderOpensTranslationAndEditsSharedOptions() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val manager = activity.supportFragmentManager
            manager.executePendingTransactions()
            val headers = manager.findFragmentById(R.id.settings) as SettingsHeaders
            val screen = headers.preferenceScreen
            val translation = (0 until screen.preferenceCount).map { screen.getPreference(it) }
                .single { it.fragment == TranslationFragment::class.java.name }
            assertNotNull(translation.icon)
            assertTrue(activity.onPreferenceStartFragment(headers, translation))
            manager.executePendingTransactions()
            val fragment = manager.findFragmentById(R.id.settings) as TranslationFragment
            assertEquals(listOf(R.string.translation_category_engine, R.string.translation_category_reading,
                R.string.translation_category_models, R.string.translation_category_storage,
                R.string.translation_category_about).map { activity.getString(it) },
                (0 until fragment.preferenceScreen.preferenceCount).map {
                    fragment.preferenceScreen.getPreference(it).title.toString()
                })
            val persistence = requireNotNull(fragment.findPreference<androidx.preference.SwitchPreferenceCompat>("translation_persist_downloaded"))
            assertFalse(persistence.isChecked)
            assertTrue(persistence.callChangeListener(true))
            assertTrue(TranslationSettings(activity).read().persistDownloaded)
            val cacheSize = requireNotNull(fragment.findPreference<com.hippo.preference.ListPreference>("translation_cache_size"))
            assertEquals("256 MiB", cacheSize.summary.toString())
            assertEquals(activity.getString(R.string.translation_cache_size), cacheSize.dialogTitle)
            assertEquals(listOf("128", "256", "512", "768", "1024"), cacheSize.entryValues.map { it.toString() })
            assertTrue(cacheSize.callChangeListener("768"))
            cacheSize.value = "768"
            assertEquals("768 MiB", cacheSize.summary.toString())
            assertEquals(768, TranslationSettings(activity).read().cacheSizeMb)
            assertFalse(cacheSize.callChangeListener("100"))
            val ahead = requireNotNull(fragment.findPreference<EditTextPreference>("translation_ahead"))
            assertTrue(ahead.callChangeListener("5"))
            assertEquals(5, TranslationSettings(activity).read().ahead)
            assertFalse(ahead.callChangeListener("11"))
            assertEquals(5, TranslationSettings(activity).read().ahead)
            val target = requireNotNull(fragment.findPreference<ListPreference>("translation_target"))
            assertTrue(target.callChangeListener("en"))
            assertEquals("en", TranslationSettings(activity).read().target)
            val backend = requireNotNull(fragment.findPreference<ListPreference>("translation_backend"))
            assertEquals("NATIVE_LLM", backend.value)
            val nativeModel = requireNotNull(fragment.findPreference<androidx.preference.Preference>("translation_native_model"))
            val nativeTest = requireNotNull(fragment.findPreference<androidx.preference.Preference>("translation_native_test"))
            assertTrue(nativeModel.isVisible)
            assertTrue(nativeTest.isVisible)
            val url = requireNotNull(fragment.findPreference<EditTextPreference>("translation_api_url"))
            val model = requireNotNull(fragment.findPreference<EditTextPreference>("translation_api_model"))
            val key = requireNotNull(fragment.findPreference<EditTextPreference>("translation_api_key"))
            assertFalse(url.isVisible)
            assertTrue(backend.callChangeListener("LLM_API"))
            assertFalse(nativeModel.isVisible)
            assertFalse(nativeTest.isVisible)
            assertTrue(url.isVisible)
            assertTrue(model.callChangeListener("hy-mt"))
            assertTrue(key.callChangeListener("private-key"))
            key.text = "private-key"
            assertFalse(key.summary.toString().contains("private-key"))
            assertFalse(url.callChangeListener("invalid"))
            assertEquals("hy-mt", TranslationSettings(activity).read().apiModel)
            assertTrue(backend.callChangeListener("ML_KIT"))
            assertFalse(url.isVisible)
            assertFalse(nativeModel.isVisible)
            assertTrue(backend.callChangeListener("NATIVE_LLM"))
            assertTrue(nativeModel.isVisible)
            assertTrue(nativeTest.isVisible)
            assertFalse(url.isVisible)
            assertTrue(manager.popBackStackImmediate())
            assertTrue(manager.findFragmentById(R.id.settings) is SettingsHeaders)
            assertEquals(activity.getString(R.string.settings), activity.supportActionBar?.title)
        }
    }

    @Test fun readerShortcutOpensSameSettingsScreenAndKeepsBackNavigation() {
        val intent = Intent(RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_TRANSLATION, true)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val manager = controller.get().supportFragmentManager
            manager.executePendingTransactions()
            assertTrue(manager.findFragmentById(R.id.settings) is TranslationFragment)
            assertTrue(manager.popBackStackImmediate())
            assertTrue(manager.findFragmentById(R.id.settings) is SettingsHeaders)
        }
    }
}
