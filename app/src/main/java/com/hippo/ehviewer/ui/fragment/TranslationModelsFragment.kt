package com.hippo.ehviewer.ui.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.translation.*
import com.hippo.ehviewer.ui.SettingsActivity
import kotlinx.coroutines.*

/** The Settings activity supplies navigation only; this page owns its cards and theme colors. */
class TranslationModelsFragment : Fragment() {
    private lateinit var models: TranslationModels
    private lateinit var store: NativeModelStore
    private lateinit var palette: ModelPalette
    private lateinit var cards: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var countText: TextView
    private lateinit var storageText: TextView
    private lateinit var taskPanel: MaterialCardView
    private lateinit var taskTitle: TextView
    private lateinit var taskText: TextView
    private lateinit var taskBar: ProgressBar
    private lateinit var taskAction: MaterialButton
    private var installed: List<NativeModelStore.Installed> = emptyList()
    private var languages: Set<String>? = null
    private var mangaReady: Boolean? = null
    private var section = 0
    private var languageTarget = "zh"
    private var languageSource = TranslationLanguages.DEFAULT_SOURCE
    private var scope: CoroutineScope? = null
    private var work: Job? = null
    private var status: Job? = null
    private var downloader: NativeModelDownloader? = null
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    private val importNative = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operate(getString(R.string.translation_native_importing)) { locked { store.import(uri) } }
    }
    private val importManga = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) operate(getString(R.string.translation_model_import_manga)) { locked { models.import(uris, ::reportProgress) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = TranslationModels(requireContext().applicationContext)
        store = NativeModelStore(requireContext().applicationContext)
        section = savedInstanceState?.getInt("section", 0)?.coerceIn(0, 2) ?: 0
        languageTarget = savedInstanceState?.getString("language_target")
            ?: TranslationSettings(requireContext()).read().withBackend(TranslationBackend.ML_KIT).target
        if (languageTarget !in TranslationLanguages.mlKitTargets) languageTarget = "zh"
        languageSource = TranslationSettings(requireContext()).read().source
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        palette = ModelPalette.forTheme(Settings.getTheme())
        val root = LinearLayout(context).apply {
            tag = "translation_model_page"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(context.modelDp(16), context.modelDp(16), context.modelDp(16), context.modelDp(12))
        }
        val overview = LinearLayout(context)
        fun metric(label: Int): TextView {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.modelDp(14), context.modelDp(10), context.modelDp(14), context.modelDp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(palette.surface); cornerRadius = context.modelDp(8).toFloat()
                }
            }
            val number = modelText(context, "—", 22f, palette.text, true)
            box.addView(number)
            box.addView(modelText(context, getString(label), 12f, palette.secondary))
            overview.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (overview.childCount > 0) marginStart = context.modelDp(8)
            })
            return number
        }
        countText = metric(R.string.translation_model_local_count)
        storageText = metric(R.string.translation_model_local_storage)
        root.addView(overview)
        root.addView(modelText(context, getString(R.string.translation_model_short_intro), 12f, palette.secondary),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.modelDp(10) })
        tabs = LinearLayout(context)
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.modelDp(12)
        })
        scroll = NestedScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        cards = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, context.modelDp(8), 0, context.modelDp(8)) }
        scroll.addView(cards)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        taskPanel = MaterialCardView(context).apply {
            tag = "translation_model_progress"
            radius = context.modelDp(8).toFloat(); cardElevation = 0f
            setCardBackgroundColor(palette.tonal)
            strokeColor = palette.border; strokeWidth = context.modelDp(1); visibility = View.GONE
        }
        val taskBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.modelDp(14), context.modelDp(10), context.modelDp(14), context.modelDp(10))
        }
        taskTitle = modelText(context, "", 14f, palette.text, true)
        taskText = modelText(context, "", 12f, palette.secondary).apply { maxLines = 3 }
        taskBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "translation_model_progress_bar"; max = 100
            progressTintList = ColorStateList.valueOf(palette.accent)
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(palette.border)
        }
        val taskHeader = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        taskHeader.addView(taskTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        taskAction = modelButton(context, palette, getString(android.R.string.cancel)) { cancelOrDismiss() }.apply { tag = "translation_model_task_action" }
        taskHeader.addView(taskAction)
        taskBody.addView(taskHeader); taskBody.addView(taskText)
        taskBody.addView(taskBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.modelDp(8)).apply { topMargin = context.modelDp(8) })
        taskPanel.addView(taskBody)
        root.addView(taskPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.modelDp(8) })
        render()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); refresh()
    }

    private fun size(bytes: Long) = Formatter.formatFileSize(requireContext(), bytes)
    private fun action(label: Int, kind: ModelButtonKind = ModelButtonKind.SECONDARY, run: () -> Unit) = ModelCardAction(label, kind, run)
    private fun card(key: String, title: String, description: String, icon: Int, status: String, active: Boolean,
                     metadata: List<String>, actions: List<ModelCardAction>, details: String? = null) {
        cards.addView(TranslationModelCard(requireContext(), palette, key, title, description, icon, status, active, metadata, actions,
            details?.let { { showDialog(AlertDialog.Builder(requireContext()).setTitle(title).setMessage(it).setPositiveButton(android.R.string.ok, null)) } }),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = requireContext().modelDp(12) })
    }

    private fun render() {
        val context = requireContext()
        countText.text = (installed.size + if (mangaReady == true) 1 else 0).toString()
        storageText.text = size(models.storedBytes() + installed.sumOf { it.size })
        tabs.removeAllViews()
        listOf(R.string.translation_model_tab_manga, R.string.translation_model_tab_native, R.string.translation_model_tab_language).forEachIndexed { index, title ->
            tabs.addView(modelButton(context, palette, getString(title)) {
                section = index; render(); scroll.scrollTo(0, 0)
            }.apply {
                tag = "translation_model_tab_$index"; isSelected = index == section; contentDescription = getString(title)
                strokeWidth = 0
                setPadding(context.modelDp(4), 0, context.modelDp(4), 0)
                setTextColor(if (isSelected) palette.accent else palette.secondary)
                backgroundTintList = ColorStateList.valueOf(if (isSelected) palette.tonal else palette.background)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = context.modelDp(4)
            })
        }
        cards.removeAllViews()
        when (section) { 0 -> renderManga(); 1 -> renderNative(); 2 -> renderLanguages() }
        setActionsEnabled(cards, !busy)
    }

    private fun renderManga() {
        val ready = mangaReady == true
        card("translation_manga_bundle", getString(R.string.translation_model_manga_card_title), getString(R.string.translation_model_manga_card_description),
            R.drawable.v_translate_x24, getString(if (mangaReady == null) R.string.translation_checking_models else if (ready) R.string.translation_model_installed else R.string.translation_model_missing),
            ready, listOf("${size(models.storedBytes())} / ${size(models.totalBytes)}", getString(R.string.translation_model_file_count, models.requiredNames.size)),
            listOf(action(if (ready) R.string.translation_model_check_download else R.string.translation_model_download, ModelButtonKind.PRIMARY) {
                operate(getString(R.string.translation_model_manga_title)) {
                    notifyModelDownloadNetwork(requireContext().applicationContext); locked { models.download(::reportProgress) }
                }
            }, action(R.string.translation_model_import) {
                showDialog(AlertDialog.Builder(requireContext()).setTitle(R.string.translation_model_import_manga)
                    .setMessage(getString(R.string.translation_model_import_manga_help, models.requiredNames.joinToString("\n")))
                    .setPositiveButton(R.string.translation_model_choose_files) { _, _ -> importManga.launch(arrayOf("*/*")) }
                    .setNegativeButton(android.R.string.cancel, null))
            }) + if (models.storedBytes() > 0) listOf(action(R.string.translation_model_delete, ModelButtonKind.DELETE) {
                confirmDelete(getString(R.string.translation_model_manga_title)) { locked { models.delete() } }
            }) else emptyList(), getString(R.string.translation_model_manga_help) + "\n\n" + models.requiredNames.joinToString("\n"))
        cards.addView(modelText(requireContext(), getString(R.string.translation_model_manga_footnote), 13f, palette.secondary))
    }

    private fun renderNative() {
        val selected = TranslationSettings(requireContext()).read()
        for (catalog in NativeModelCatalog.models) {
            val local = installed.firstOrNull { it.id == catalog.sha256 }
            addNativeCard(catalog.sha256, catalog.name, local?.size ?: catalog.size, local != null, selected.nativeModelId == catalog.sha256, catalog)
        }
        for (local in installed.filter { item -> NativeModelCatalog.models.none { it.sha256 == item.id } })
            addNativeCard(local.id, local.name, local.size, true, selected.nativeModelId == local.id, null)
        card("translation_native_import", getString(R.string.translation_model_import_card_title), getString(R.string.translation_model_import_card_description),
            R.drawable.v_folders_import_dark_x24, "GGUF", false, emptyList(), listOf(action(R.string.translation_model_choose_file) { importNative.launch(arrayOf("*/*")) }),
            getString(R.string.translation_native_model_help))
    }

    private fun addNativeCard(id: String, name: String, bytes: Long, available: Boolean, selected: Boolean, catalog: NativeDownloadModel?) {
        val description = getString(when (catalog) {
            NativeModelCatalog.models[0] -> R.string.translation_model_hy_card_description
            NativeModelCatalog.models[1] -> R.string.translation_model_manga_llm_card_description
            else -> R.string.translation_model_imported_description
        })
        val actions = if (available) {
            listOf(action(if (selected) R.string.translation_model_selected_button else R.string.translation_model_use, ModelButtonKind.PRIMARY) {
                if (!selected) operate(name) { locked { store.select(id) } }
            }) +
                listOf(action(R.string.translation_model_delete, ModelButtonKind.DELETE) { confirmDelete(name) { locked { store.delete(id) } } })
        } else if (catalog != null) listOf(action(R.string.translation_model_download, ModelButtonKind.PRIMARY) {
            operate(name) {
                notifyModelDownloadNetwork(requireContext().applicationContext)
                NativeModelDownloader(store).use {
                    downloader = it
                    it.downloadAndImport(catalog, { copied, total -> reportProgress(name, copied, total) }, { reportStage(getString(R.string.translation_native_download_validating)) })
                }
            }
        }) else emptyList()
        card("translation_native_$id", name, description, R.drawable.v_download_box_outline_dark_x24,
            getString(if (selected && available) R.string.translation_model_current_badge else if (available) R.string.translation_model_installed else R.string.translation_model_not_downloaded),
            selected && available, listOf(size(bytes)) + if (catalog == null) emptyList() else listOf("1.8B · Q4_K_M"), actions,
            name + "\n\n" + getString(if (catalog == null) R.string.translation_native_model_help else if (catalog == NativeModelCatalog.models[0]) R.string.translation_model_hy_help else R.string.translation_model_manga_llm_help))
    }

    private fun renderLanguages() {
        val locale = resources.configuration.locales[0]
        val name = TranslationLanguages.displayName(languageTarget, locale)
        val source = TranslationLanguages.mlKitSource(languageSource)
        val required = OfflineTranslator.requiredLanguages(source, languageTarget)
        val ready = if (required.isEmpty()) true else languages?.let { OfflineTranslator.isReady(source, languageTarget, it) }
        card("translation_language_download", getString(R.string.translation_model_language_card_title),
            "${if (languageSource == TranslationLanguages.AUTO_SOURCE) getString(R.string.translation_source_auto)
                else TranslationLanguages.displayName(languageSource, locale)} → $name", R.drawable.v_translate_x24,
            getString(if (ready == null) R.string.translation_model_status_unknown else if (ready) R.string.translation_model_installed else R.string.translation_model_missing),
            ready == true, listOf(getString(R.string.translation_model_language_size)), listOf(
                action(R.string.translation_model_download, ModelButtonKind.PRIMARY) {
                    val target = languageTarget
                    operate(getString(R.string.translation_model_language_download), false) {
                        notifyModelDownloadNetwork(requireContext().applicationContext); locked { OfflineTranslator(target, source).use { it.prepare() } }
                    }
                }, action(R.string.translation_model_choose_language) { chooseLanguage() }, action(R.string.translation_model_refresh) { refresh() }
            ), getString(R.string.translation_model_language_help) + if (source == TranslationLanguages.AUTO_SOURCE)
                "\n\n${getString(R.string.translation_source_auto_mlkit_help)}" else "")
        if (languages.isNullOrEmpty()) cards.addView(modelText(requireContext(), getString(if (languages == null)
            R.string.translation_model_language_unknown else R.string.translation_model_language_empty), 13f, palette.secondary))
        for (language in languages.orEmpty().sorted()) {
            val label = TranslationLanguages.displayName(language, resources.configuration.locales[0])
            card("translation_language_$language", label, getString(if (language == source)
                R.string.translation_model_language_source_short else R.string.translation_model_language_target_short), R.drawable.v_check_dark_x24,
                getString(R.string.translation_model_installed), false, emptyList(), listOf(action(R.string.translation_model_delete, ModelButtonKind.DELETE) {
                    confirmDelete(label) { locked { OfflineTranslator.deleteLanguage(language) } }
                }))
        }
    }

    private fun chooseLanguage() {
        val codes = TranslationLanguages.mlKitTargets
        val adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_single_choice,
            codes.map { TranslationLanguages.displayName(it, resources.configuration.locales[0]) }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(palette.text) }
        }
        showDialog(AlertDialog.Builder(requireContext()).setTitle(R.string.translation_model_language_target)
            .setSingleChoiceItems(adapter, codes.indexOf(languageTarget)) { dialog, which ->
                languageTarget = codes[which]; dialog.dismiss(); render()
            }.setNegativeButton(android.R.string.cancel, null))
    }

    private fun showDialog(builder: AlertDialog.Builder, destructive: Boolean = false) {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(if (destructive) palette.danger else palette.accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.accent)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(palette.accent)
        }
        dialog.show()
    }

    private fun refresh() {
        if (busy) return
        status?.cancel()
        status = scope?.launch {
            installed = withContext(Dispatchers.IO) { store.installed() }
            mangaReady = withContext(Dispatchers.IO) { models.ready() }
            if (isAdded && view != null) render()
            languages = try { withContext(Dispatchers.IO) { OfflineTranslator.downloadedLanguages() } }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            if (isAdded && view != null) render()
        }
    }

    private suspend fun locked(block: suspend () -> Unit) = TranslationRuntime.withModelMaintenance(block)
    private fun confirmDelete(name: String, delete: suspend () -> Unit) = showDialog(AlertDialog.Builder(requireContext())
        .setTitle(R.string.translation_model_delete).setMessage(getString(R.string.translation_model_delete_confirm, name))
        .setPositiveButton(R.string.translation_model_delete) { _, _ -> operate(name, false, delete) }.setNegativeButton(android.R.string.cancel, null), true)

    private fun setActionsEnabled(group: ViewGroup, enabled: Boolean) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is MaterialButton) child.isEnabled = enabled else if (child is ViewGroup) setActionsEnabled(child, enabled)
        }
    }

    private fun cancelOrDismiss() {
        if (busy) { work?.cancel(); models.cancel(); downloader?.cancel() } else taskPanel.visibility = View.GONE
    }

    private fun operate(title: String, cancellable: Boolean = true, block: suspend () -> Unit) {
        if (busy || scope == null) return
        status?.cancel(); busy = true; setActionsEnabled(cards, false)
        taskTitle.text = title; taskText.setText(R.string.translation_model_waiting)
        taskBar.visibility = View.VISIBLE; taskBar.isIndeterminate = true; taskPanel.visibility = View.VISIBLE
        taskAction.setText(android.R.string.cancel); taskAction.visibility = if (cancellable) View.VISIBLE else View.GONE
        work = scope?.launch {
            try {
                withContext(Dispatchers.IO) { block() }; taskText.setText(R.string.translation_model_complete)
            } catch (cancel: CancellationException) {
                if (isAdded && view != null) taskText.setText(R.string.translation_model_cancelled)
                throw cancel
            } catch (error: Exception) {
                taskText.text = getString(R.string.translation_model_operation_failed, error.message.orEmpty())
            } catch (_: LinkageError) { taskText.setText(R.string.translation_native_unavailable) }
            finally {
                downloader = null; busy = false
                if (isAdded && view != null) {
                    taskBar.visibility = View.GONE; taskAction.setText(android.R.string.ok); taskAction.visibility = View.VISIBLE
                    setActionsEnabled(cards, true); refresh()
                }
            }
        }
    }

    private fun reportProgress(name: String, copied: Long, total: Long) {
        handler.post { if (busy && view != null) {
            taskText.text = "$name\n${size(copied)} / ${size(total)}"
            taskBar.isIndeterminate = total <= 0
            taskBar.progress = if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else 0
        } }
    }
    private fun reportStage(message: String) { handler.post { if (busy && view != null) { taskText.text = message; taskBar.isIndeterminate = true } } }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("language_target", languageTarget); outState.putInt("section", section)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); (activity as? SettingsActivity)?.setSettingsTitle(R.string.translation_model_management) }
    override fun onDestroyView() {
        models.cancel(); downloader?.cancel(); scope?.cancel(); scope = null; handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
