package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceCategory
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.superresolution.SuperResolutionModels
import com.hippo.ehviewer.superresolution.SuperResolutionMemoryBudget
import com.hippo.ehviewer.superresolution.SuperResolutionSettings
import com.hippo.ehviewer.superresolution.SuperResolutionScale
import com.hippo.ehviewer.superresolution.SuperResolutionStore
import com.hippo.ehviewer.translation.TranslationModels
import com.hippo.ehviewer.translation.notifyModelDownloadNetwork
import com.hippo.ehviewer.ui.SettingsActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class SuperResolutionFragment : BasePreferenceFragmentCompat() {
    private lateinit var models: SuperResolutionModels
    private var mode = TranslationModels.ImportMode.COPY
    private var scope: CoroutineScope? = null
    private var work: Job? = null
    private lateinit var automatic: SwitchPreferenceCompat
    private lateinit var info: Preference
    private lateinit var acceleration: ListPreference
    private val actions = mutableListOf<Preference>()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val selectedMode = mode
            operate(getString(if (selectedMode == TranslationModels.ImportMode.REFERENCE)
                R.string.translation_native_referencing else R.string.translation_image_importing)) {
                models.store.import(uris, selectedMode, ::progress)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        models = SuperResolutionModels(requireContext().applicationContext)
        mode = if (savedInstanceState?.getBoolean("reference") == true)
            TranslationModels.ImportMode.REFERENCE else TranslationModels.ImportMode.COPY
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        info = Preference(requireContext()).apply {
            setTitle(R.string.sr_title)
            setSummary(R.string.sr_description)
            isSelectable = false
        }
        screen.addPreference(info)
        fun category(title: Int) = PreferenceCategory(requireContext()).apply {
            setTitle(title)
            screen.addPreference(this)
        }
        val reading = category(R.string.sr_category_reading)
        automatic = SwitchPreferenceCompat(requireContext()).apply {
            key = SuperResolutionSettings.AUTO_ENABLE
            setTitle(R.string.sr_automatic)
            setSummary(R.string.sr_automatic_help)
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, value ->
                if (value == true && !models.store.ready()) {
                    AlertDialog.Builder(requireContext()).setMessage(R.string.sr_models_required)
                        .setPositiveButton(android.R.string.ok, null).show()
                    false
                } else true
            }
        }
        reading.addPreference(automatic)
        reading.addPreference(ListPreference(requireContext()).apply {
            key = SuperResolutionSettings.SCALE
            setTitle(R.string.sr_scale)
            entries = arrayOf(getString(R.string.sr_scale_auto), "2×", "4×", "8×")
            entryValues = SuperResolutionScale.entries.map { it.value }.toTypedArray()
            setDefaultValue("2")
            summaryProvider = Preference.SummaryProvider<ListPreference> {
                "${it.entry ?: "2×"}\n${getString(R.string.sr_scale_help)}"
            }
        })
        acceleration = ListPreference(requireContext()).apply {
            key = SuperResolutionModels.BACKEND
            setTitle(R.string.sr_backend)
            entries = arrayOf(getString(R.string.sr_backend_auto), getString(R.string.sr_backend_fast), getString(R.string.sr_backend_cpu))
            entryValues = arrayOf("auto", "auto_fast", "cpu")
            setDefaultValue(SuperResolutionModels.DEFAULT_BACKEND)
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }
        reading.addPreference(acceleration)
        reading.addPreference(ListPreference(requireContext()).apply {
            key = SuperResolutionSettings.AHEAD
            setTitle(R.string.sr_ahead)
            entries = (0..10).map(Int::toString).toTypedArray()
            entryValues = entries
            setDefaultValue("2")
            summaryProvider = Preference.SummaryProvider<ListPreference> {
                getString(R.string.sr_ahead_help, it.value?.toIntOrNull() ?: 2)
            }
        })
        val storage = category(R.string.sr_category_storage)
        storage.addPreference(SwitchPreferenceCompat(requireContext()).apply {
            key = SuperResolutionSettings.PERSIST
            setTitle(R.string.sr_persist)
            setSummary(R.string.sr_persist_help)
            setDefaultValue(true)
        })
        storage.addPreference(ListPreference(requireContext()).apply {
            key = SuperResolutionSettings.CACHE
            setTitle(R.string.sr_cache_limit)
            entryValues = arrayOf("64", "128", "256", "512", "1024", "2048", "4096", "8192")
            entries = entryValues.map { "$it MiB" }.toTypedArray()
            setDefaultValue("512")
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        })
        action(storage, R.string.sr_clear_cache) {
            operate(getString(R.string.sr_clear_cache)) { SuperResolutionStore.clear(requireContext().applicationContext) }
        }
        action(storage, R.string.sr_clear_saved) {
            AlertDialog.Builder(requireContext()).setMessage(R.string.sr_clear_saved_help)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    operate(getString(R.string.sr_clear_saved)) { SuperResolutionStore.clear(requireContext().applicationContext, persistent = true) }
                }.show()
        }
        val modelSettings = category(R.string.sr_category_models)
        action(modelSettings, R.string.sr_download) {
            operate(getString(R.string.sr_download)) {
                notifyModelDownloadNetwork(requireContext().applicationContext)
                models.download(::progress)
            }
        }
        action(modelSettings, R.string.sr_import) {
            AlertDialog.Builder(requireContext()).setTitle(R.string.translation_image_import_mode)
                .setItems(arrayOf(getString(R.string.translation_model_import_copy),
                    getString(R.string.translation_model_import_reference))) { _, which ->
                    mode = if (which == 0) TranslationModels.ImportMode.COPY else TranslationModels.ImportMode.REFERENCE
                    picker.launch(arrayOf("*/*"))
                }.show()
        }
        action(modelSettings, R.string.sr_remove) {
            operate(getString(R.string.sr_remove)) { models.store.delete() }
        }
        action(modelSettings, R.string.sr_license) {
            val license = requireContext().assets.open("super-resolution-notices.txt")
                .bufferedReader().use { it.readText() } + "\n\n" +
                requireContext().assets.open("super-resolution-vulkan-notices.txt")
                    .bufferedReader().use { it.readText() }
            AlertDialog.Builder(requireContext()).setTitle(R.string.sr_license).setMessage(license)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        action(modelSettings, android.R.string.cancel) { work?.cancel() }
        refresh()
    }

    private fun action(screen: PreferenceGroup, title: Int, block: () -> Unit) {
        val preference = Preference(requireContext()).apply {
            setTitle(title)
            setOnPreferenceClickListener { block(); true }
        }
        actions += preference
        screen.addPreference(preference)
    }

    private fun refresh() {
        info.summary = getString(R.string.sr_description) + "\n" +
            getString(if (models.store.ready()) R.string.sr_models_ready else R.string.sr_models_required) + "\n" +
            getString(R.string.sr_memory_budget, SuperResolutionMemoryBudget.current(requireContext()) /
                SuperResolutionMemoryBudget.MIB)
    }

    private fun progress(name: String, bytes: Long, total: Long) {
        scope?.launch { info.summary = "$name: ${bytes * 100 / total.coerceAtLeast(1)}%" }
    }

    private fun operate(title: String, block: suspend () -> Unit) {
        if (work?.isActive == true) return
        val operationScope = scope ?: return
        work = operationScope.launch {
            automatic.isEnabled = false
            acceleration.isEnabled = false
            actions.dropLast(1).forEach { it.isEnabled = false }
            info.summary = title
            try {
                withContext(Dispatchers.IO) { SuperResolutionModels.lock.withLock { block() } }
                refresh()
            } catch (error: CancellationException) {
                if (scope === operationScope && operationScope.isActive) refresh()
                throw error
            } catch (error: Exception) {
                info.summary = getString(R.string.sr_failed, error.message.orEmpty())
            } finally {
                if (scope === operationScope) {
                    automatic.isEnabled = true
                    acceleration.isEnabled = true
                    actions.forEach { it.isEnabled = true }
                }
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setSettingsTitle(R.string.sr_title)
        if (work?.isActive != true) refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("reference", mode == TranslationModels.ImportMode.REFERENCE)
    }
    override fun onDestroyView() {
        scope?.cancel()
        scope = null
        work = null
        super.onDestroyView()
    }
}
