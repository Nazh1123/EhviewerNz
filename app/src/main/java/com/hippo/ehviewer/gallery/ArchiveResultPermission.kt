package com.hippo.ehviewer.gallery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.GalleryActivity
import kotlinx.coroutines.*

/** Requests a directory grant only from a visible activity, before a persistent task starts. */
object ArchiveResultPermission {
    @JvmStatic fun withAccess(activity: FragmentActivity, provider: GalleryProvider2,
                              required: Boolean, ready: Runnable) {
        if (!required || provider !is ArchiveGalleryProvider) { ready.run(); return }
        request(activity, provider.archiveUri, ready)
    }

    @JvmStatic fun request(activity: FragmentActivity, archive: Uri, ready: Runnable) {
        activity.lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { ArchiveResultStorage.canWrite(activity, archive) }) {
                ready.run()
                return@launch
            }
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || activity.isFinishing) return@launch
            val tag = "archive-parent-permission"
            val existing = manager.findFragmentByTag(tag) as? ArchiveResultPermissionFragment
            if (existing != null) {
                if (existing.arguments?.getString("archive") == archive.toString()) existing.callbacks.add(ready)
                return@launch
            }
            val fragment = ArchiveResultPermissionFragment().apply {
                arguments = Bundle().apply { putString("archive", archive.toString()) }
                callbacks.add(ready)
            }
            manager.beginTransaction().add(fragment, tag).commitNow()
        }
    }
}

/** Restores the picker request across recreation; processing callbacks belong to the old activity. */
class ArchiveResultPermissionFragment : Fragment() {
    internal val callbacks = mutableListOf<Runnable>()
    private var explanation: AlertDialog? = null
    private var pickerLaunched = false
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val archive = Uri.parse(requireArguments().getString("archive"))
        val tree = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || tree == null) { finish(false); return@registerForActivityResult }
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ArchiveResultStorage.bind(requireContext(), archive, tree, result.data?.flags ?: 0)
            }
            if (!success) Toast.makeText(context, R.string.archive_results_wrong_parent, Toast.LENGTH_LONG).show()
            finish(success)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerLaunched = savedInstanceState?.getBoolean("picker") == true
        if (!pickerLaunched) showExplanation()
    }
    private fun showExplanation() {
        explanation = AlertDialog.Builder(requireContext()).setTitle(R.string.archive_results_directory)
            .setMessage(R.string.archive_results_permission_message)
            .setPositiveButton(R.string.archive_results_choose_parent) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(requireArguments().getString("archive")))
                try { pickerLaunched = true; picker.launch(intent) }
                catch (_: RuntimeException) {
                    Toast.makeText(context, R.string.archive_results_wrong_parent, Toast.LENGTH_LONG).show()
                    finish(false)
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }.show()
    }
    private fun finish(success: Boolean) {
        if (success) {
            (activity as? GalleryActivity)?.onArchiveResultDirectoryChanged()
            callbacks.toList().forEach { it.run() }
        }
        callbacks.clear()
        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker", pickerLaunched)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { explanation?.dismiss(); explanation = null; super.onDestroy() }
}
