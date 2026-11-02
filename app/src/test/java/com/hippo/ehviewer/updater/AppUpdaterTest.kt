package com.hippo.ehviewer.updater

import com.alibaba.fastjson.JSON
import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    @Test fun selectsApkInsteadOfSourceArchivesAndKeepsReleaseSeparate() {
        val release = JSON.parseObject("""{
            "assets": [
                {"name":"source.zip","browser_download_url":"https://example.com/source.zip"},
                {"name":"EhViewer.APK","browser_download_url":"https://example.com/app.apk"},
                {"name":"source.tar.gz","browser_download_url":"https://example.com/source.tar.gz"}
            ], "name":"Version 3", "body":"First change\n\nSecond change"
        }""")
        val data = AppUpdater.createDialogData(release, "3.0", "https://example.com/releases/3")
        val content = data.getJSONObject(AppUpdater.UPDATE_CONTENT)
        assertEquals("https://example.com/app.apk", content.getString(AppUpdater.FILE_DOWNLOAD_URL))
        assertEquals("https://example.com/releases/3", content.getString(AppUpdater.RELEASE_URL))
        assertEquals("First change\n\nSecond change", content.getString(AppUpdater.CONTENT))
    }

    @Test fun missingAmbiguousOrUnsafeApkDoesNotBecomeDirectDownload() {
        for (assets in listOf(
            "[]",
            """[{"name":"source.zip"}]""",
            """[{"name":"app.apk"}]""",
            """[{"name":"app.apk","browser_download_url":"http://example.com/app.apk"}]""",
            """[{"name":"arm.apk"},{"name":"x86.apk"}]"""
        )) {
            assertNull(AppUpdater.findApkDownloadUrl(JSON.parseObject("{\"assets\":$assets}")))
        }
        assertNull(AppUpdater.findApkDownloadUrl(JSON.parseObject("{}")))
    }

    @Test fun preservesMarkdownIndentationBlankLinesAndCodeExactly() {
        val markdown = "## Changes\n\n- Parent\n  - Child\n\n```kotlin\n    val x = 1\n```\n"
        val release = JSON.parseObject("{}")
        release["body"] = markdown
        val content = AppUpdater.createDialogData(release, "3.0", "https://example.com/releases/3")
            .getJSONObject(AppUpdater.UPDATE_CONTENT)
        assertEquals(markdown, content.getString(AppUpdater.CONTENT))
    }

    @Test fun emptyReleaseNotesFallBackToReleaseLink() {
        for (body in listOf(null, "", "  \n\t")) {
            val release = JSON.parseObject("{}")
            release["body"] = body
            val content = AppUpdater.createDialogData(release, "3.0", "https://example.com/releases/3")
                .getJSONObject(AppUpdater.UPDATE_CONTENT)
            assertEquals("https://example.com/releases/3", content.getString(AppUpdater.CONTENT))
        }
    }
}
