package com.hippo.ehviewer.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.dialog.UpdateDialog
import java.util.UUID

/** DownloadManager survives recreation; installation is only launched while this UI is resumed. */
class AppUpdateActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val downloads by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val pending by lazy { getSharedPreferences("app_update_download", Context.MODE_PRIVATE) }
    private val downloadUrl by lazy { intent.getStringExtra(AppUpdater.FILE_DOWNLOAD_URL).orEmpty() }
    private var downloadId = -1L
    private var downloadComplete = false
    private var installAttempted = false
    private var failed = false
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button

    private val permissionResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (packageManager.canRequestPackageInstalls()) launchInstaller()
        else message.setText(R.string.app_update_install_permission)
    }

    private val installerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Keep the APK available if the user cancels installation.
        message.setText(R.string.app_update_ready)
    }

    private val checkProgress = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            try {
                downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (cursor == null || !cursor.moveToFirst()) {
                        showFailure()
                        return
                    }
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloadComplete = true
                            progress.visibility = View.GONE
                            message.setText(R.string.app_update_ready)
                            action.setText(R.string.app_update_install)
                            action.visibility = View.VISIBLE
                            if (!installAttempted) requestInstall()
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            showFailure()
                            return
                        }
                        else -> {
                            val total = cursor.getLong(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val received = cursor.getLong(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            progress.isIndeterminate = total <= 0
                            val percent = if (total > 0) (received * 100 / total).toInt() else 0
                            progress.progress = percent
                            val paused = cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED
                            message.text = when {
                                paused -> getString(R.string.app_update_waiting_network)
                                total > 0 -> getString(R.string.app_update_progress, percent)
                                else -> getString(R.string.app_update_downloading)
                            }
                        }
                    }
                }
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
                Analytics.recordException(e)
                showFailure()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setTitle(R.string.app_update_direct_download)
        setContentView(R.layout.activity_app_update)
        message = findViewById(R.id.app_update_message)
        progress = findViewById(R.id.app_update_progress)
        action = findViewById(R.id.app_update_action)
        installAttempted = savedInstanceState?.getBoolean("installAttempted") ?: false
        action.setOnClickListener {
            if (failed) {
                startDownload()
                handler.post(checkProgress)
            } else requestInstall()
        }
        findViewById<Button>(R.id.app_update_cancel).setOnClickListener { cancelAndFinish() }
        findViewById<Button>(R.id.app_update_release).setOnClickListener {
            try {
                val url = intent.getStringExtra(AppUpdater.RELEASE_URL)
                    ?: UpdateDialog.GITHUB_RELEASE_URL
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (e: Exception) {
                Analytics.recordException(e)
                Toast.makeText(this, R.string.error_cant_find_activity, Toast.LENGTH_LONG).show()
            }
        }
        if (!downloadUrl.startsWith("https://")) {
            showFailure()
            action.visibility = View.GONE
            return
        }
        // Reuse a pending/completed task when reopening the same release, including after death.
        if (pending.getString("url", null) == downloadUrl) {
            downloadId = pending.getLong("id", -1)
        }
        if (downloadId == -1L) startDownload()
    }

    private fun startDownload() {
        handler.removeCallbacks(checkProgress)
        try {
            val previousId = pending.getLong("id", -1)
            if (previousId != -1L) downloads.remove(previousId)
            pending.edit().clear().apply()
            downloadId = -1L
            downloadComplete = false
            installAttempted = false
            failed = false
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            action.visibility = View.GONE
            message.setText(R.string.app_update_downloading)
            val request = DownloadManager.Request(downloadUrl.toUri())
                .setTitle(getString(R.string.app_update_download_title))
                .setMimeType("application/vnd.android.package-archive")
                .addRequestHeader("User-Agent", "EhViewerNz/${BuildConfig.VERSION_NAME}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                    "updates/${UUID.randomUUID()}.apk")
            downloadId = downloads.enqueue(request)
            pending.edit().putLong("id", downloadId).putString("url", downloadUrl).apply()
        } catch (e: Exception) {
            Analytics.recordException(e)
            showFailure()
        }
    }

    private fun showFailure() {
        failed = true
        progress.visibility = View.GONE
        message.setText(R.string.app_update_download_failed)
        action.setText(R.string.app_update_retry)
        action.visibility = View.VISIBLE
    }

    private fun requestInstall() {
        installAttempted = true
        try {
            if (packageManager.canRequestPackageInstalls()) launchInstaller()
            else {
                message.setText(R.string.app_update_install_permission)
                permissionResult.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri()))
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
            message.setText(R.string.app_update_install_failed)
        }
    }

    private fun launchInstaller() {
        try {
            // DownloadManager provides a content URI with a temporary read grant for the installer.
            val uri = downloads.getUriForDownloadedFile(downloadId)
                ?: throw IllegalStateException("Downloaded APK is unavailable")
            installerResult.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Analytics.recordException(e)
            message.setText(R.string.app_update_install_failed)
        }
    }

    override fun onResume() {
        super.onResume()
        if (downloadId != -1L && !failed && !downloadComplete) handler.post(checkProgress)
    }

    override fun onPause() {
        handler.removeCallbacks(checkProgress)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("installAttempted", installAttempted)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = cancelAndFinish()

    private fun cancelAndFinish() {
        handler.removeCallbacks(checkProgress)
        if (!downloadComplete && downloadId != -1L) {
            try {
                downloads.remove(downloadId)
                pending.edit().clear().apply()
            } catch (e: Exception) {
                Analytics.recordException(e)
            }
        }
        finish()
    }
}
