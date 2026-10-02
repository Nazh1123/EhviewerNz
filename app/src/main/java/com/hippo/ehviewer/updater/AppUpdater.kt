package com.hippo.ehviewer.updater

import android.app.Activity
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.dialog.UpdateDialog
import com.hippo.util.AppHelper.Companion.compareVersion
import com.hippo.util.ExceptionUtils
import com.hippo.util.IoThreadPoolExecutor
import okhttp3.Request
import java.util.Date
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

class AppUpdater private constructor() {

    companion object {
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/Nazh1123/Ehviewer_CN_SXJ/releases/latest"

        const val VERSION: String = "version"
        const val VERSION_CODE: String = "versionCode"
        const val FILE_DOWNLOAD_URL: String = "fileDownloadUrl"
        const val MUST_UPDATE: String = "mustUpdate"
        const val UPDATE_CONTENT: String = "updateContent"
        const val TITLE: String = "title"
        const val CONTENT: String = "content"

        private val lock: Lock = ReentrantLock()
        private val numericVersion = Regex("\\d+(?:\\.\\d+)+")

        @JvmStatic
        fun update(activity: Activity, manualChecking: Boolean) {
            if (!manualChecking && !Settings.getIsUpdateTime()) {
                return
            }

            IoThreadPoolExecutor.instance.execute {
                if (!lock.tryLock()) {
                    return@execute
                }
                try {
                    val client = EhApplication.getOkHttpClient(EhApplication.getInstance())
                    val request = Request.Builder()
                        .url(GITHUB_LATEST_RELEASE_API)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "EhViewerNz/${BuildConfig.VERSION_NAME}")
                        .build()
                    val release = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("GitHub returned HTTP ${response.code()}")
                        }
                        val body = response.body()?.string()
                            ?: throw IllegalStateException("GitHub returned an empty response")
                        JSON.parseObject(body)
                    }

                    Settings.putUpdateTime(Date().time)
                    val latestVersion = extractVersion(release.getString("tag_name"))
                        ?: extractVersion(release.getString("name"))
                        ?: throw IllegalStateException("Release version is missing")
                    val currentVersion = extractVersion(BuildConfig.VERSION_NAME)
                        ?: BuildConfig.VERSION_NAME

                    if (compareVersion(currentVersion, latestVersion) >= 0) {
                        if (manualChecking) {
                            ContextCompat.getMainExecutor(activity).execute {
                                Toast.makeText(
                                    activity,
                                    R.string.update_to_date,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        return@execute
                    }

                    val releaseUrl = release.getString("html_url")
                        ?: UpdateDialog.GITHUB_RELEASE_URL
                    UpdateDialog(activity).showUpdateDialog(
                        createDialogData(release, latestVersion, releaseUrl)
                    )
                } catch (t: Throwable) {
                    ExceptionUtils.throwIfFatal(t)
                    Analytics.recordException(t)
                    if (manualChecking) {
                        UpdateDialog(activity).showCheckFailDialog()
                    }
                } finally {
                    lock.unlock()
                }
            }
        }

        private fun extractVersion(value: String?): String? {
            return value?.let { numericVersion.find(it)?.value }
        }

        private fun createDialogData(
            release: JSONObject,
            version: String,
            releaseUrl: String
        ): JSONObject {
            val lines = JSONArray()
            release.getString("body")
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { lines.add(it) }
            if (lines.isEmpty()) {
                lines.add(releaseUrl)
            }

            val updateContent = JSONObject()
            updateContent[TITLE] = release.getString("name")
                ?.takeIf { it.isNotBlank() }
                ?: "EhViewerNz $version"
            updateContent[CONTENT] = lines
            updateContent[FILE_DOWNLOAD_URL] = releaseUrl

            val data = JSONObject()
            data[VERSION] = version
            data[MUST_UPDATE] = false
            data[UPDATE_CONTENT] = updateContent
            return data
        }
    }
}
