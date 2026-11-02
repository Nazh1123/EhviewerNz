package com.hippo.ehviewer.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.R

/** Transparent bridge used only to request installation permission and launch the system installer. */
class AppUpdateActivity : ComponentActivity() {
    private var attempted = false
    private val downloads by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val downloadId by lazy { intent.getLongExtra(AppUpdateDownload.EXTRA_DOWNLOAD_ID, -1) }

    private val permissionResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (packageManager.canRequestPackageInstalls()) launchInstaller()
        else {
            Toast.makeText(this, R.string.app_update_install_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val installerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attempted = savedInstanceState?.getBoolean("attempted") ?: false
        val state = AppUpdateDownload.pending(this)
        if (downloadId == -1L || downloadId != state.getLong("id", -1) || !state.getBoolean("ready", false)) {
            finish()
            return
        }
        state.edit().putBoolean("autoInstall", false).apply()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || attempted) return
        attempted = true
        try {
            if (packageManager.canRequestPackageInstalls()) launchInstaller()
            else permissionResult.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:$packageName".toUri()))
        } catch (e: Exception) {
            installFailed(e)
        }
    }

    private fun launchInstaller() {
        try {
            val uri = downloads.getUriForDownloadedFile(downloadId)
                ?: throw IllegalStateException("Downloaded APK is unavailable")
            installerResult.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            installFailed(e)
        }
    }

    private fun installFailed(e: Exception) {
        Analytics.recordException(e)
        Toast.makeText(this, R.string.app_update_install_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("attempted", attempted)
        super.onSaveInstanceState(outState)
    }
}
