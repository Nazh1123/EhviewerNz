package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.hippo.ehviewer.R
import com.hippo.ehviewer.superresolution.SuperResolutionModels
import com.hippo.ehviewer.superresolution.SuperResolutionMemoryBudget
import com.hippo.ehviewer.superresolution.SuperResolutionSettings
import com.hippo.ehviewer.superresolution.SuperResolutionStore
import com.hippo.ehviewer.translation.TranslationModels
import com.hippo.ehviewer.translation.notifyModelDownloadNetwork
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.preference.ListPreference
import com.hippo.preference.SwitchPreference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class SuperResolutionFragment : BasePreferenceFragmentCompat() {
    private lateinit var models: SuperResolutionModels
    private var mode = TranslationModels.ImportMode.COPY
    private var scope: CoroutineScope? = null
    private var work: Job? = null
    private lateinit var automatic: SwitchPreference
    private lateinit var info: Preference
    private lateinit var acceleration: ListPreference
    private lateinit var download: Preference
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
        val options = SuperResolutionSettings(requireContext()).read()
        actions.clear()
        setPreferencesFromResource(R.xml.super_resolution_settings, rootKey)
        info = requirePreference("super_resolution_info")
        automatic = requirePreference<SwitchPreference>(SuperResolutionSettings.AUTO_ENABLE).apply {
            setOnPreferenceChangeListener { _, value ->
                if (value == true && !models.store.ready()) {
                    AlertDialog.Builder(requireContext()).setMessage(R.string.sr_models_required)
                        .setPositiveButton(android.R.string.ok, null).show()
                    false
                } else true
            }
        }
        requirePreference<ListPreference>(SuperResolutionSettings.SCALE).value = options.scale.value
        acceleration = requirePreference<ListPreference>(SuperResolutionModels.BACKEND).apply {
            value = options.mode
        }
        requirePreference<ListPreference>(SuperResolutionSettings.AHEAD).value = options.ahead.toString()
        requirePreference<ListPreference>(SuperResolutionSettings.CACHE).value = options.cacheMiB.toString()
        requirePreference<ListPreference>(SuperResolutionSettings.QUALITY).value = options.quality.value
        action("super_resolution_clear_cache") {
            operate(getString(R.string.sr_clear_cache)) { SuperResolutionStore.clear(requireContext().applicationContext) }
        }
        download = action("super_resolution_download") {
            operate(getString(R.string.sr_download)) {
                notifyModelDownloadNetwork(requireContext().applicationContext)
                models.download(::progress)
            }
        }
        action("super_resolution_import") {
            AlertDialog.Builder(requireContext()).setTitle(R.string.translation_image_import_mode)
                .setItems(arrayOf(getString(R.string.translation_model_import_copy),
                    getString(R.string.translation_model_import_reference))) { _, which ->
                    mode = if (which == 0) TranslationModels.ImportMode.COPY else TranslationModels.ImportMode.REFERENCE
                    picker.launch(arrayOf("*/*"))
                }.show()
        }
        action("super_resolution_remove") {
            operate(getString(R.string.sr_remove)) { models.store.delete() }
        }
        action("super_resolution_license") {
            val license = requireContext().assets.open("super-resolution-notices.txt")
                .bufferedReader().use { it.readText() } + "\n\n" +
                requireContext().assets.open("super-resolution-vulkan-notices.txt")
                    .bufferedReader().use { it.readText() }
            AlertDialog.Builder(requireContext()).setTitle(R.string.sr_license).setMessage(license)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        refresh()
    }

    private fun <T : Preference> requirePreference(key: String): T =
        requireNotNull(findPreference<T>(key)) { "Missing preference: $key" }

    private fun action(key: String, block: () -> Unit): Preference {
        val preference = requirePreference<Preference>(key).apply {
            setOnPreferenceClickListener { block(); true }
        }
        actions += preference
        return preference
    }

    private fun refresh() {
        refreshDownloadState(work?.isActive == true)
        info.summary = getString(R.string.sr_info_summary,
            getString(R.string.sr_description),
            getString(if (models.store.ready()) R.string.sr_models_ready else R.string.sr_models_required),
            getString(R.string.sr_memory_budget, SuperResolutionMemoryBudget.current(requireContext()) /
                SuperResolutionMemoryBudget.MIB))
    }

    private fun refreshDownloadState(busy: Boolean) {
        val ready = models.store.ready()
        download.setTitle(if (ready) R.string.sr_downloaded else R.string.sr_download)
        download.isEnabled = !busy && !ready
    }

    private fun progress(name: String, bytes: Long, total: Long) {
        scope?.launch {
            info.summary = getString(R.string.sr_model_progress, name, bytes * 100 / total.coerceAtLeast(1))
        }
    }

    private fun operate(title: String, block: suspend () -> Unit) {
        if (work?.isActive == true) return
        val operationScope = scope ?: return
        work = operationScope.launch {
            automatic.isEnabled = false
            acceleration.isEnabled = false
            actions.forEach { it.isEnabled = false }
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
                    refreshDownloadState(busy = false)
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
