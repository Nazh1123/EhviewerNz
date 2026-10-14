package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.widget.doAfterTextChanged
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.translation.*
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.preference.ListPreference
import com.hippo.preference.SwitchPreference
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
    private lateinit var sourceLanguage: ListPreference
    private var apiTest: Job? = null
    private var nativeWork: Job? = null
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        apiPreferences.clear()
        nativePreferences.clear()
        settings = TranslationSettings(requireContext())
        val context = requireContext()
        setPreferencesFromResource(R.xml.translation_settings, rootKey)
        val options = settings.read()
        requirePreference<ListPreference>("translation_backend").apply {
            value = options.backend.name
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
        }
        nativeModel = requirePreference("translation_native_model")
        nativePreferences.add(nativeModel)
        val nativeTest = requirePreference<Preference>("translation_native_test").apply {
            setOnPreferenceClickListener {
                if (nativeWork?.isCompleted != false) nativeWork = scope?.launch {
                    nativePreferences.forEach { it.isEnabled = false }
                    setSummary(R.string.translation_native_testing)
                    try {
                        val result = withContext(Dispatchers.IO) {
                            TranslationRuntime.withModelMaintenance {
                                val selected = settings.read()
                                NativeTranslator(context.applicationContext, selected).use {
                                    val translated = it.translateSample(selected.sampleText())
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
        nativePreferences.forEach {
            it.isVisible = options.backend == TranslationBackend.NATIVE_LLM
            it.isEnabled = nativeWork?.isCompleted != false
        }
        fun apiField(keyName: String, help: Int, initial: String, secret: Boolean = false,
                     save: (TranslationOptions, String) -> TranslationOptions) {
            val field = requirePreference<EditTextPreference>(keyName).apply {
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
        }
        apiField("translation_api_url", R.string.translation_api_url_help,
            options.apiUrl) { current, value -> current.copy(apiUrl = value) }
        apiField("translation_api_model", R.string.translation_api_model_help,
            options.apiModel) { current, value -> current.copy(apiModel = value) }
        apiField("translation_api_key", R.string.translation_api_key_help,
            options.apiKey, secret = true) { current, value -> current.copy(apiKey = value) }
        val test = requirePreference<Preference>("translation_api_test").apply {
            setOnPreferenceClickListener {
                if (apiTest?.isActive != true) apiTest = scope?.launch {
                    isEnabled = false
                    setSummary(R.string.translation_api_testing)
                    try {
                        val selected = settings.read()
                        val result = withContext(Dispatchers.IO) {
                            ApiTranslator(selected).use {
                                val translated = it.translateSample(selected.sampleText())
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
        apiPreferences.forEach { it.isVisible = options.backend == TranslationBackend.LLM_API }
        sourceLanguage = requirePreference<ListPreference>("translation_source").apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> {
                "${it.entry}\n${getString(if (it.value == TranslationLanguages.AUTO_SOURCE)
                    R.string.translation_source_auto_help else R.string.translation_source_help)}"
            }
            setOnPreferenceChangeListener { _, value ->
                val updated = settings.read().copy(source = value.toString())
                if (!TranslationLanguages.validSource(updated)) false else {
                    settings.save(updated)
                    refreshLanguages()
                    refreshStatus()
                    true
                }
            }
        }
        targetLanguage = requirePreference<ListPreference>("translation_target").apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> {
                val current = settings.read()
                val name = it.entry?.toString().orEmpty()
                getString(if (current.backend == TranslationBackend.ML_KIT)
                    R.string.translation_target_mlkit_summary else R.string.translation_target_llm_summary,
                    name)
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
        refreshLanguages()
        requirePreference<EditTextPreference>("translation_ahead").apply {
            text = options.ahead.toString()
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val count = it.text?.toIntOrNull() ?: 2
                if (count == 0) getString(R.string.translation_current_only)
                else getString(R.string.translation_ahead_summary, count)
            }
            setOnBindEditTextListener { input ->
                input.inputType = InputType.TYPE_CLASS_NUMBER
                input.setSingleLine(true)
                input.setSelectAllOnFocus(true)
                val slider = AppCompatSeekBar(input.context).apply {
                    tag = "translation_ahead_slider"
                    max = 10
                    progress = input.text.toString().toIntOrNull()?.coerceIn(0, max) ?: 2
                    contentDescription = getString(R.string.translation_ahead)
                }
                (input.parent as ViewGroup).addView(slider, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt()))
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (input.text.toString().toIntOrNull() != progress) {
                            input.setText(progress.toString())
                            input.setSelection(input.length())
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
                input.doAfterTextChanged { text ->
                    text.toString().toIntOrNull()?.takeIf { it in 0..slider.max }?.let {
                        slider.progress = it
                    }
                }
            }
            setOnPreferenceChangeListener { _, value ->
                val count = value.toString().toIntOrNull()
                if (count == null || count !in 0..10) {
                    Toast.makeText(context, R.string.translation_ahead_range, Toast.LENGTH_SHORT).show()
                    false
                } else { settings.save(settings.read().copy(ahead = count)); true }
            }
        }
        requirePreference<SwitchPreference>("translation_inpaint").apply {
            isChecked = options.inpaint
            setOnPreferenceChangeListener { _, value -> settings.save(settings.read().copy(inpaint = value as Boolean)); true }
        }
        requirePreference<SwitchPreference>("translation_persist_downloaded").apply {
            isChecked = options.persistDownloaded
            setOnPreferenceChangeListener { _, value ->
                settings.save(settings.read().copy(persistDownloaded = value as Boolean)); true
            }
        }
        requirePreference<ListPreference>("translation_cache_size").apply {
            entries = TranslationSettings.CACHE_SIZES_MB.map { "$it MiB" }.toTypedArray()
            setEntryValues(TranslationSettings.CACHE_SIZES_MB.map { it.toString() }.toTypedArray())
            value = options.cacheSizeMb.toString()
            setOnPreferenceChangeListener { _, value ->
                val size = value.toString().toIntOrNull()
                if (size !in TranslationSettings.CACHE_SIZES_MB) false
                else {
                    settings.save(settings.read().copy(cacheSizeMb = requireNotNull(size)))
                    TranslationStorage.pruneCache(context)
                    true
                }
            }
        }
        requirePreference<Preference>("translation_clear").apply {
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
        }
    }

    private fun <T : Preference> requirePreference(key: String): T = requireNotNull(findPreference<T>(key))

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
        sourceLanguage.entries = TranslationLanguages.sources.map {
            if (it == TranslationLanguages.AUTO_SOURCE) getString(R.string.translation_source_auto)
            else TranslationLanguages.displayName(it, locale)
        }.toTypedArray()
        sourceLanguage.setEntryValues(TranslationLanguages.sources.toTypedArray())
        sourceLanguage.value = options.source
        targetLanguage.entries = if (!mlKit) resources.getStringArray(R.array.translation_llm_target_entries) else codes.map { code ->
            when (code) {
                "zh" -> getString(R.string.translation_target_zh)
                "en" -> getString(R.string.translation_target_en)
                "ko" -> getString(R.string.translation_target_ko)
                else -> TranslationLanguages.displayName(code, locale)
            }
        }.toTypedArray()
        targetLanguage.setEntryValues(codes.toTypedArray())
        targetLanguage.value = options.target
    }

    private fun refreshStatus() {
        val options = settings.read()
        nativeModel.summary = options.nativeModelName.takeIf { it.isNotBlank() }
            ?: getString(R.string.translation_native_not_selected)
        if (options.nativeModelId == NativeModelCatalog.models[1].sha256 &&
            (options.source != "ja" || options.target != "zh-CN")) {
            nativeModel.summary = "${nativeModel.summary}\n${getString(R.string.translation_native_pair_help)}"
        }
    }
    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSettingsTitle(R.string.settings_translation)
        refreshLanguages()
        refreshStatus()
    }

    override fun onDestroyView() {
        scope?.cancel()
        scope = null
        super.onDestroyView()
    }
}
