package com.hippo.ehviewer.ui.dialog

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R
import com.hippo.ehviewer.updater.AppUpdateDownload
import com.hippo.ehviewer.updater.AppUpdater

class UpdateDialog(private val activity: Activity) {
    companion object {
        const val GITHUB_RELEASE_URL = "https://github.com/Nazh1123/Ehviewer_CN_SXJ/releases"
        const val GITHUB_README_URL =
            "https://github.com/Nazh1123/EhviewerNz/blob/BiLi_PC_Gamer/README.md"
        const val GITHUB_SUPPORT_URL =
            "https://github.com/Nazh1123/EhviewerNz/blob/BiLi_PC_Gamer/feedauthor/support.md"
    }

    private fun isActivityAlive() = !(activity.isFinishing || activity.isDestroyed)

    fun showCheckFailDialog() {
        ContextCompat.getMainExecutor(activity).execute {
            if (!isActivityAlive()) return@execute
            AlertDialog.Builder(activity)
                .setIcon(R.mipmap.ic_launcher)
                .setTitle(R.string.update_fail)
                .setMessage(R.string.update_fail_info)
                .setPositiveButton(R.string.app_update_open_release) { _, _ ->
                    openPage(GITHUB_RELEASE_URL)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    fun showUpdateDialog(tempUpdateData: JSONObject) {
        try {
            val mustUpdate = tempUpdateData.getBooleanValue(AppUpdater.MUST_UPDATE)
            val content = tempUpdateData.getJSONObject(AppUpdater.UPDATE_CONTENT)
            val releaseUrl = content.getString(AppUpdater.RELEASE_URL)
                ?.takeIf { it.isNotBlank() } ?: GITHUB_RELEASE_URL
            val downloadUrl = content.getString(AppUpdater.FILE_DOWNLOAD_URL)
            val notes = when (val value = content[AppUpdater.CONTENT]) {
                is String -> value
                is JSONArray -> value.joinToString("\n")
                else -> releaseUrl
            }.ifBlank { releaseUrl }
            ContextCompat.getMainExecutor(activity).execute {
                if (!isActivityAlive()) return@execute
                val builder = AlertDialog.Builder(activity)
                val dialog = builder
                    .setIcon(R.mipmap.ic_launcher)
                    .setTitle(content.getString(AppUpdater.TITLE))
                    .setView(UpdateNotesView(builder.context, notes, releaseUrl, ::openPage))
                    .setPositiveButton(R.string.update, null)
                    .setNeutralButton(R.string.update_dialog_sponsor, null)
                    .apply {
                        if (mustUpdate) setCancelable(false)
                        else setNegativeButton(R.string.cancel, null)
                    }
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        showUpdateMethods(releaseUrl, downloadUrl) { dialog.dismiss() }
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        openPage(GITHUB_SUPPORT_URL)
                    }
                }
                dialog.show()
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    private fun showUpdateMethods(releaseUrl: String, downloadUrl: String?, onSelected: () -> Unit) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.app_update_method)
            .setMessage(if (downloadUrl.isNullOrBlank()) R.string.app_update_no_apk
                else R.string.app_update_method_description)
            .setNeutralButton(R.string.app_update_open_release) { _, _ ->
                openPage(releaseUrl)
                onSelected()
            }
            .setPositiveButton(R.string.app_update_direct_download) { _, _ ->
                AppUpdateDownload.start(activity, downloadUrl!!, releaseUrl)
                onSelected()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !downloadUrl.isNullOrBlank()
        }
        dialog.show()
    }

    private fun openPage(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Analytics.recordException(e)
            Toast.makeText(activity, R.string.error_cant_find_activity, Toast.LENGTH_LONG).show()
        }
    }
}
