package com.hippo.ehviewer.updater

import android.app.Activity
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.alibaba.fastjson.JSON
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Date
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

internal data class ReleaseNotes(val version: String, val markdown: String, val url: String)

class AppUpdater private constructor() {

    companion object {
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/Nazh1123/Ehviewer_CN_SXJ/releases"
        private const val MAX_RELEASE_NOTES = 10

        const val VERSION: String = "version"
        const val VERSION_CODE: String = "versionCode"
        const val FILE_DOWNLOAD_URL: String = "fileDownloadUrl"
        const val RELEASE_URL: String = "releaseUrl"
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
                    val release = client.newCall(releaseRequest("$GITHUB_RELEASES_API/latest")).execute().use {
                        JSON.parseObject(it.readReleaseBody())
                    }

                    Settings.putUpdateTime(Date().time)
                    val latestVersion = releaseVersion(release)
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

                    val previousReleases = try {
                        loadPreviousReleaseNotes(client, GITHUB_RELEASES_API, currentVersion, latestVersion)
                    } catch (e: Exception) {
                        Analytics.recordException(e)
                        emptyList()
                    }
                    UpdateDialog(activity).showUpdateDialog(
                        createDialogData(release, latestVersion, releaseUrl(release)),
                        previousReleases
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

        private fun releaseRequest(url: String): Request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "EhViewerNz/${BuildConfig.VERSION_NAME}")
            .build()

        private fun Response.readReleaseBody(): String {
            check(isSuccessful) { "GitHub returned HTTP ${code()}" }
            return body()?.string() ?: error("GitHub returned an empty response")
        }

        private fun releaseVersion(release: JSONObject): String? =
            extractVersion(release.getString("tag_name")) ?: extractVersion(release.getString("name"))

        private fun extractVersion(value: String?): String? {
            return value?.let { numericVersion.find(it)?.value }?.takeIf { version ->
                version.split('.').all { it.toIntOrNull() != null }
            }
        }

        private fun releaseUrl(release: JSONObject): String =
            release.getString("html_url")?.takeIf { it.isNotBlank() } ?: UpdateDialog.GITHUB_RELEASE_URL

        internal fun loadPreviousReleaseNotes(
            client: OkHttpClient,
            releasesApi: String,
            currentVersion: String,
            latestVersion: String
        ): List<ReleaseNotes> {
            val notes = mutableListOf<ReleaseNotes>()
            var page = 1
            // The API pages follow release creation order, which can differ from version order.
            do {
                val request = releaseRequest("$releasesApi?per_page=100&page=$page")
                val hasNextPage = client.newCall(request).execute().use { response ->
                    for (release in JSON.parseArray(response.readReleaseBody()).filterIsInstance<JSONObject>()) {
                        if (release.getBooleanValue("draft") || release.getBooleanValue("prerelease")) continue
                        val version = releaseVersion(release) ?: continue
                        if (compareVersion(version, currentVersion) <= 0 || compareVersion(version, latestVersion) >= 0) {
                            continue
                        }
                        val url = releaseUrl(release)
                        val markdown = release.getString("body")?.takeIf { it.isNotBlank() } ?: "<$url>"
                        notes.add(ReleaseNotes(version, markdown, url))
                    }
                    response.headers("Link").any { it.contains("rel=\"next\"") }
                }
                page++
            } while (hasNextPage)

            return notes.sortedWith { first, second -> compareVersion(second.version, first.version) }
                .take(MAX_RELEASE_NOTES - 1)
        }

        internal fun createDialogData(
            release: JSONObject,
            version: String,
            releaseUrl: String
        ): JSONObject {
            // Markdown needs its blank lines and indentation intact.
            val notes = release.getString("body")?.takeIf { it.isNotBlank() } ?: releaseUrl

            val updateContent = JSONObject()
            updateContent[TITLE] = release.getString("name")
                ?.takeIf { it.isNotBlank() }
                ?: "EhViewerNz $version"
            updateContent[CONTENT] = notes
            updateContent[RELEASE_URL] = releaseUrl
            updateContent[FILE_DOWNLOAD_URL] = findApkDownloadUrl(release)

            val data = JSONObject()
            data[VERSION] = version
            data[MUST_UPDATE] = false
            data[UPDATE_CONTENT] = updateContent
            return data
        }

        internal fun findApkDownloadUrl(release: JSONObject): String? {
            val assets = release.getJSONArray("assets") ?: return null
            // Source archives are not APKs. If multiple APKs appear in a future release,
            // keep the Release option available instead of guessing an architecture.
            val apks = assets.filterIsInstance<JSONObject>().filter {
                it.getString("name")?.endsWith(".apk", ignoreCase = true) == true
            }
            return apks.singleOrNull()?.getString("browser_download_url")
                ?.takeIf { it.startsWith("https://") }
        }
    }
}
