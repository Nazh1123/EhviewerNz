package com.hippo.ehviewer.ui.fragment

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.superresolution.SuperResolutionModels
import com.hippo.ehviewer.superresolution.SuperResolutionSettings
import com.hippo.preference.ListPreference
import com.hippo.preference.SwitchPreference
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE, qualifiers = "zh-rCN")
class SuperResolutionPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fragment: SuperResolutionFragment
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        Settings.initialize(app)
    }

    private fun openSettings() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.setTheme(R.style.AppTheme_Settings)
        fragment = SuperResolutionFragment()
        host.supportFragmentManager.beginTransaction().add(fragment, "super-resolution").commitNow()
    }

    private fun preference(key: String): Preference = requireNotNull(fragment.findPreference(key))

    @Test fun xmlUsesSharedControlsAndStylesWithTheOriginalDefaults() {
        openSettings()
        val automatic = preference(SuperResolutionSettings.AUTO_ENABLE) as SwitchPreference
        assertFalse(automatic.isChecked)
        assertEquals(R.layout.preference_widget_fixed_switch, automatic.widgetLayoutResource)
        assertTrue((preference(SuperResolutionSettings.PERSIST) as SwitchPreference).isChecked)
        for ((key, value) in mapOf(SuperResolutionSettings.SCALE to "2",
            SuperResolutionModels.BACKEND to "auto_fast", SuperResolutionSettings.AHEAD to "2",
            SuperResolutionSettings.CACHE to "512")) {
            val list = preference(key) as ListPreference
            assertEquals(value, list.value)
            assertFalse(list.summary.isNullOrEmpty())
        }
        fun checkRows(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val row = group.getPreference(index)
                assertFalse(row.key, row.isIconSpaceReserved)
                assertNotEquals(app.getString(R.string.sr_clear_saved), row.title)
                assertNotEquals(app.getString(android.R.string.cancel), row.title)
                if (row is PreferenceGroup) checkRows(row)
            }
        }
        checkRows(fragment.preferenceScreen)
    }

    @Test fun xmlPreservesExistingSettingsAndListSelections() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(SuperResolutionSettings.AUTO_ENABLE, true)
            .putBoolean(SuperResolutionSettings.PERSIST, false)
            .putString(SuperResolutionSettings.SCALE, "8")
            .putString(SuperResolutionModels.BACKEND, "cpu")
            .putString(SuperResolutionSettings.AHEAD, "10")
            .putString(SuperResolutionSettings.CACHE, "8192").commit()
        openSettings()
        assertTrue((preference(SuperResolutionSettings.AUTO_ENABLE) as SwitchPreference).isChecked)
        assertFalse((preference(SuperResolutionSettings.PERSIST) as SwitchPreference).isChecked)
        assertEquals("8", (preference(SuperResolutionSettings.SCALE) as ListPreference).value)
        assertEquals("cpu", (preference(SuperResolutionModels.BACKEND) as ListPreference).value)
        assertEquals("10", (preference(SuperResolutionSettings.AHEAD) as ListPreference).value)
        assertEquals("8192", (preference(SuperResolutionSettings.CACHE) as ListPreference).value)
    }

    @Test fun xmlSummariesFollowSelectionChanges() {
        openSettings()
        val scale = preference(SuperResolutionSettings.SCALE) as ListPreference
        for (value in listOf("4", "8", "auto")) {
            scale.value = value
            assertEquals(scale.entry.toString(), scale.summary.toString())
        }
        val ahead = preference(SuperResolutionSettings.AHEAD) as ListPreference
        for (value in listOf("0", "10")) {
            ahead.value = value
            assertEquals("当前页 + 后续 $value 页", ahead.summary.toString())
        }
    }

    @Test fun xmlSummaryTemplatesWorkInEverySupportedLanguage() {
        for (language in listOf("en", "de", "es", "fr", "ja", "ko", "th", "zh-CN", "zh-HK", "zh-TW")) {
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme_Settings)
            val screen = PreferenceManager(context).inflateFromResource(context, R.xml.super_resolution_settings, null)
            val scale = requireNotNull(screen.findPreference<ListPreference>(SuperResolutionSettings.SCALE))
            scale.value = "auto"
            assertEquals(language, scale.entry.toString(), scale.summary.toString())
            val ahead = requireNotNull(screen.findPreference<ListPreference>(SuperResolutionSettings.AHEAD))
            ahead.value = "10"
            assertTrue(language, ahead.summary.toString().contains("10"))
            assertFalse(language, ahead.summary.toString().contains("%1$"))
        }
    }

    @Test fun downloadCompletionDisablesTheRowAndRemovalAllowsDownloadingAgain() {
        openSettings()
        MockWebServer().use { server ->
            val models = useTestModels(server)
            server.enqueue(MockResponse().setBody("graph").setBodyDelay(100, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("weights"))
            val download = preference("super_resolution_download")
            download.performClick()
            assertFalse(preference("super_resolution_license").isEnabled)
            awaitOperation()
            assertTrue(models.store.ready())
            assertEquals(app.getString(R.string.sr_downloaded), download.title)
            assertFalse(download.isEnabled)
            // Opening the page again must retain the completed state.
            fragment.onResume()
            assertFalse(download.isEnabled)
            preference("super_resolution_remove").performClick()
            awaitOperation()
            assertFalse(models.store.ready())
            assertTrue(download.isEnabled)
            assertEquals(app.getString(R.string.sr_download), download.title)
        }
    }

    @Test fun failedDownloadRemainsRetryableAndRestoresOtherActions() {
        openSettings()
        MockWebServer().use { server ->
            useTestModels(server)
            server.enqueue(MockResponse().setBody("graph"))
            server.enqueue(MockResponse().setResponseCode(503))
            preference("super_resolution_download").performClick()
            awaitOperation()
            assertTrue(preference("super_resolution_download").isEnabled)
            assertEquals(app.getString(R.string.sr_download), preference("super_resolution_download").title)
            assertTrue(preference("super_resolution_license").isEnabled)
            assertTrue(preference("super_resolution_info").summary.toString().contains("503"))
        }
    }

    private fun useTestModels(server: MockWebServer): SuperResolutionModels {
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = temporary.root
        }
        val manifest = JSONObject().put("revision", "test").put("models", JSONArray().apply {
            for ((name, data) in listOf("scale2.0x_model.param" to "graph", "scale2.0x_model.bin" to "weights")) {
                put(JSONObject().put("name", name).put("size", data.toByteArray().size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
                        .joinToString("") { "%02x".format(it) }))
            }
        }).toString()
        return SuperResolutionModels(context, manifest, server.url("/models/").toString()).also {
            ReflectionHelpers.setField(fragment, "models", it)
            fragment.onResume()
        }
    }

    private fun awaitOperation() {
        val job = ReflectionHelpers.getField<Job>(fragment, "work")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Settings operation did not finish", job.isCompleted)
    }
}
