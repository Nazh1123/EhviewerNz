package com.hippo.ehviewer.gallery

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import kotlinx.coroutines.*

/** Shared by both submenus; starting either imports both result types for this gallery. */
internal object GalleryResultImportDialog {
    private val running = mutableSetOf<String>() // Main thread only.

    fun show(activity: AppCompatActivity, provider: GalleryProvider2, info: GalleryInfo?) {
        val identity = GalleryResultIndex.identity(provider, info)
        if (!running.add(identity)) {
            Toast.makeText(activity, R.string.result_import_running, Toast.LENGTH_SHORT).show()
            return
        }
        var task: Job? = null
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.result_import)
            .setMessage(R.string.result_import_running).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> task?.cancel() }.show()
        task = activity.lifecycleScope.launch {
            try {
                val report = GalleryResultImporter.run(activity.applicationContext, provider, info)
                if (!activity.isDestroyed) AlertDialog.Builder(activity).setTitle(R.string.result_import)
                    .setMessage(activity.getString(R.string.result_import_report,
                        report.translated, report.enhanced, report.ignored, report.failed))
                    .setPositiveButton(android.R.string.ok, null).show()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.util.Log.w("ResultImporter", "Import failed", error)
                if (!activity.isDestroyed) Toast.makeText(activity, R.string.result_import_failed, Toast.LENGTH_LONG).show()
            } finally {
                running.remove(identity)
                dialog.dismiss()
            }
        }
    }
}
