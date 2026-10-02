package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.*
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.translation.*
import com.hippo.ehviewer.ui.SettingsActivity
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class TranslationFragment : BasePreferenceFragmentCompat() {
    private lateinit var settings: TranslationSettings
    private var scope: CoroutineScope? = null
    private val apiPreferences = mutableListOf<Preference>()
    private val nativePreferences = mutableListOf<Preference>()
    private lateinit var nativeModel: Preference
    private lateinit var targetLanguage: ListPreference
    private var apiTest: Job? = null
    private var nativeWork: Job? = null
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        apiPreferences.clear()
        nativePreferences.clear()
        settings = TranslationSettings(requireContext())
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val options = settings.read()
        var group: PreferenceGroup = screen
        fun category(title: Int) {
            group = PreferenceCategory(context).apply {
                setTitle(title)
                order = screen.preferenceCount
                screen.addPreference(this)
            }
        }
        fun add(preference: Preference) { preference.isPersistent = false; group.addPreference(preference) }
        category(R.string.translation_category_engine)
        add(ListPreference(context).apply {
            key = "translation_backend"
            setTitle(R.string.translation_backend)
            entries = arrayOf(getString(R.string.translation_backend_native),
                getString(R.string.translation_backend_mlkit), getString(R.string.translation_backend_api))
            entryValues = TranslationBackend.entries.map { it.name }.toTypedArray()
            value = options.backend.name
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                val backend = TranslationBackend.valueOf(value.toString())
                val previous = settings.read()
                val updated = previous.withBackend(backend)
                settings.save(updated)
                if (updated.target != previous.target &&
                    !(previous.target == "zh-CN" && updated.target == "zh") &&
                    !(previous.target == "zh" && updated.target == "zh-CN"))
                    Toast.makeText(context, R.string.translation_target_reset, Toast.LENGTH_LONG).show()
                apiPreferences.forEach { it.isVisible = backend == TranslationBackend.LLM_API }
                nativePreferences.forEach { it.isVisible = backend == TranslationBackend.NATIVE_LLM }
                refreshLanguages()
                refreshStatus()
                true
            }
        })
        nativeModel = Preference(context).apply {
            key = "translation_native_model"
            setTitle(R.string.translation_native_current)
            isSelectable = false
        }
        nativePreferences.add(nativeModel)
        add(nativeModel)
        val nativeTest = Preference(context).apply {
            key = "translation_native_test"
            setTitle(R.string.translation_native_test)
            setSummary(R.string.translation_native_test_help)
            setOnPreferenceClickListener {
                if (nativeWork?.isCompleted != false) nativeWork = scope?.launch {
                    nativePreferences.forEach { it.isEnabled = false }
                    setSummary(R.string.translation_native_testing)
                    try {
                        val result = withContext(Dispatchers.IO) {
                            TranslationRuntime.withModelMaintenance {
                                val selected = settings.read()
                                NativeTranslator(context.applicationContext, selected).use {
                                    val translated = it.translateDetailed(listOf(selected.sampleText()))
                                    check(translated.error == null) { translated.error ?: "Invalid translation" }
                                    "${selected.sampleText()}\n\n${translated.translations.single()}"
                                }
                            }
                        }
                        AlertDialog.Builder(requireContext()).setTitle(R.string.translation_native_test)
                            .setMessage(result)
                            .setPositiveButton(android.R.string.ok, null).show()
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (error: Exception) {
                        Toast.makeText(context, getString(R.string.translation_native_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show()
                    } catch (_: LinkageError) {
                        Toast.makeText(context, R.string.translation_native_unavailable, Toast.LENGTH_LONG).show()
                    } finally {
                        nativePreferences.forEach { it.isEnabled = true }
                        setSummary(R.string.translation_native_test_help)
                    }
                }
                true
            }
        }
        nativePreferences.add(nativeTest)
        add(nativeTest)
        nativePreferences.forEach {
            it.isVisible = options.backend == TranslationBackend.NATIVE_LLM
            it.isEnabled = nativeWork?.isCompleted != false
        }
        fun apiField(keyName: String, title: Int, help: Int, initial: String, secret: Boolean = false,
                     save: (TranslationOptions, String) -> TranslationOptions) {
            val field = EditTextPreference(context).apply {
                key = keyName
                setTitle(title)
                setDialogMessage(help)
                text = initial
                summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                    if (secret && !it.text.isNullOrEmpty()) getString(R.string.translation_key_saved)
                    else it.text?.takeIf(String::isNotBlank) ?: getString(help)
                }
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_TEXT_VARIATION_URI
                    it.setSingleLine(true)
                }
                setOnPreferenceChangeListener { _, value ->
                    val updated = save(settings.read(), value.toString().trim())
                    if (!updated.validApiUrl()) {
                        Toast.makeText(context, R.string.translation_api_invalid_url, Toast.LENGTH_LONG).show()
                        false
                    } else { settings.save(updated); true }
                }
            }
            apiPreferences.add(field)
            add(field)
        }
        apiField("translation_api_url", R.string.translation_api_url, R.string.translation_api_url_help,
            options.apiUrl) { current, value -> current.copy(apiUrl = value) }
        apiField("translation_api_model", R.string.translation_api_model, R.string.translation_api_model_help,
            options.apiModel) { current, value -> current.copy(apiModel = value) }
        apiField("translation_api_key", R.string.translation_api_key, R.string.translation_api_key_help,
            options.apiKey, secret = true) { current, value -> current.copy(apiKey = value) }
        val test = Preference(context).apply {
            key = "translation_api_test"
            setTitle(R.string.translation_api_test)
            setSummary(R.string.translation_api_test_help)
            setOnPreferenceClickListener {
                if (apiTest?.isActive != true) apiTest = scope?.launch {
                    isEnabled = false
                    setSummary(R.string.translation_api_testing)
                    try {
                        val selected = settings.read()
                        val result = withContext(Dispatchers.IO) {
                            ApiTranslator(selected).use {
                                val translated = it.translateDetailed(listOf(selected.sampleText()))
                                check(translated.error == null) { translated.error ?: "Invalid translation" }
                                translated.translations.single()
                            }
                        }
                        AlertDialog.Builder(requireContext()).setTitle(R.string.translation_api_test)
                            .setMessage("${selected.sampleText()}\n\n$result")
                            .setPositiveButton(android.R.string.ok, null).show()
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (error: Exception) {
                        Toast.makeText(context, getString(R.string.translation_api_test_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show()
                    } finally {
                        isEnabled = true
                        setSummary(R.string.translation_api_test_help)
                    }
                }
                true
            }
        }
        apiPreferences.add(test)
        add(test)
        apiPreferences.forEach { it.isVisible = options.backend == TranslationBackend.LLM_API }
        category(R.string.translation_category_reading)
        targetLanguage = ListPreference(context).apply {
            key = "translation_target"
            setTitle(R.string.translation_target)
            setDialogTitle(R.string.translation_target)
            summaryProvider = Preference.SummaryProvider<ListPreference> {
                val current = settings.read()
                val name = it.entry?.toString().orEmpty()
                getString(if (current.backend == TranslationBackend.ML_KIT)
                    R.string.translation_target_mlkit_summary else R.string.translation_target_llm_summary, name)
            }
            setOnPreferenceChangeListener { _, value ->
                val updated = settings.read().copy(target = value.toString())
                if (!TranslationLanguages.validTarget(updated)) false
                else {
                    settings.save(updated)
                    refreshLanguages()
                    refreshStatus()
                    true
                }
            }
        }
        add(targetLanguage)
        refreshLanguages()
        add(EditTextPreference(context).apply {
            key = "translation_ahead"
            setTitle(R.string.translation_ahead)
            setDialogMessage(R.string.translation_ahead_help)
            text = options.ahead.toString()
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val count = it.text?.toIntOrNull() ?: 2
                if (count == 0) getString(R.string.translation_current_only)
                else getString(R.string.translation_ahead_summary, count)
            }
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER; it.setSelectAllOnFocus(true) }
            setOnPreferenceChangeListener { _, value ->
                val count = value.toString().toIntOrNull()
                if (count == null || count !in 0..10) {
                    Toast.makeText(context, R.string.translation_ahead_range, Toast.LENGTH_SHORT).show()
                    false
                } else { settings.save(settings.read().copy(ahead = count)); true }
            }
        })
        add(SwitchPreferenceCompat(context).apply {
            key = "translation_inpaint"
            setTitle(R.string.translation_inpaint)
            setSummary(R.string.translation_inpaint_help)
            isChecked = options.inpaint
            setOnPreferenceChangeListener { _, value -> settings.save(settings.read().copy(inpaint = value as Boolean)); true }
        })
        category(R.string.translation_category_models)
        add(Preference(context).apply {
            key = "translation_model_management"
            setTitle(R.string.translation_model_management)
            setSummary(R.string.translation_model_management_help)
            fragment = TranslationModelsFragment::class.java.name
        })
        category(R.string.translation_category_storage)
        add(SwitchPreferenceCompat(context).apply {
            key = "translation_persist_downloaded"
            setTitle(R.string.translation_persist_downloaded)
            setSummary(R.string.translation_persist_downloaded_help)
            isChecked = options.persistDownloaded
            setOnPreferenceChangeListener { _, value ->
                settings.save(settings.read().copy(persistDownloaded = value as Boolean)); true
            }
        })
        add(com.hippo.preference.ListPreference(context).apply {
            key = "translation_cache_size"
            setTitle(R.string.translation_cache_size)
            dialogTitle = getString(R.string.translation_cache_size)
            entries = TranslationSettings.CACHE_SIZES_MB.map { "$it MiB" }.toTypedArray()
            setEntryValues(TranslationSettings.CACHE_SIZES_MB.map { it.toString() }.toTypedArray())
            value = options.cacheSizeMb.toString()
            summary = "%s"
            setOnPreferenceChangeListener { _, value ->
                val size = value.toString().toIntOrNull()
                if (size !in TranslationSettings.CACHE_SIZES_MB) false
                else {
                    settings.save(settings.read().copy(cacheSizeMb = requireNotNull(size)))
                    TranslationStorage.pruneCache(context)
                    true
                }
            }
        })
        add(Preference(context).apply {
            key = "translation_clear"
            setTitle(R.string.translation_clear_cache)
            setSummary(R.string.translation_cache_description)
            setOnPreferenceClickListener {
                val app = context.applicationContext
                scope?.launch {
                    withContext(Dispatchers.IO) {
                        TranslationRuntime.lock.withLock {
                            TranslationCache(File(app.cacheDir, "translated-pages")).clear()
                            TranslationRuntime.preparedPages.clear()
                        }
                    }
                    Toast.makeText(app, R.string.translation_cache_cleared, Toast.LENGTH_SHORT).show()
                }
                true
            }
        })
        category(R.string.translation_category_about)
        add(Preference(context).apply {
            setTitle(R.string.translation_title)
            setSummary(R.string.translation_explanation)
            isSelectable = false
        })
        preferenceScreen = screen
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        refreshStatus()
    }

    private fun refreshLanguages() {
        val options = settings.read()
        val mlKit = options.backend == TranslationBackend.ML_KIT
        val codes = TranslationLanguages.targets(options.backend)
        val locale = resources.configuration.locales[0]
        targetLanguage.entries = if (!mlKit) resources.getStringArray(R.array.translation_llm_target_entries) else codes.map { code ->
            when (code) {
                "zh" -> getString(R.string.translation_target_zh)
                "en" -> getString(R.string.translation_target_en)
                "ko" -> getString(R.string.translation_target_ko)
                else -> TranslationLanguages.displayName(code, locale)
            }
        }.toTypedArray()
        targetLanguage.entryValues = codes.toTypedArray()
        targetLanguage.value = options.target
    }

    private fun refreshStatus() {
        val options = settings.read()
        nativeModel.summary = options.nativeModelName.takeIf { it.isNotBlank() }
            ?: getString(R.string.translation_native_not_selected)
    }
    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSettingsTitle(R.string.settings_translation)
        refreshStatus()
    }

    override fun onDestroyView() {
        scope?.cancel()
        scope = null
        super.onDestroyView()
    }
}
