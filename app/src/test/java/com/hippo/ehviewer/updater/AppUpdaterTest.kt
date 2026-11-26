package com.hippo.ehviewer.updater

import com.alibaba.fastjson.JSON
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test fun historyContainsOnlyStableAppVersionsBetweenCurrentAndLatest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"tag_name":"2.0.2.10"},
                {"tag_name":"2.0.2.11"},
                {"tag_name":"manga-models-v1","name":"Translation models"},
                {"tag_name":"2.0.2.8.0"},
                {"tag_name":"2.0.2.7"},
                {"tag_name":"2.0.2.9-rc","prerelease":true},
                {"tag_name":"2.0.2.9","draft":true},
                {"tag_name":"2.0.2.9.1","body":"  - Nested\n\nDetails"},
                {"tag_name":"2.0.2.9","html_url":"https://example.com/releases/9","body":"  "}
            ]"""))

            val notes = AppUpdater.loadPreviousReleaseNotes(
                OkHttpClient(), server.url("/releases").toString(), "2.0.2.8", "2.0.2.10"
            )

            assertEquals(listOf("2.0.2.9.1", "2.0.2.9"), notes.map { it.version })
            assertEquals("  - Nested\n\nDetails", notes.first().markdown)
            assertEquals("<https://example.com/releases/9>", notes.last().markdown)
        }
    }

    @Test fun historySortsAcrossPagesAndReservesOneOfTenSlotsForLatest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .addHeader("Link", "<${server.url("/releases?page=2")}>; rel=\"next\"")
                .setBody((1..11).joinToString(",", "[", "]") { """{"tag_name":"2.0.$it"}""" }))
            server.enqueue(MockResponse().setBody("""[{"tag_name":"v2.0.12"}]"""))

            val notes = AppUpdater.loadPreviousReleaseNotes(
                OkHttpClient(), server.url("/releases").toString(), "2.0.1", "2.0.13"
            )

            assertEquals((12 downTo 4).map { "2.0.$it" }, notes.map { it.version })
            assertEquals("/releases?per_page=100&page=1", server.takeRequest().path)
            assertEquals("/releases?per_page=100&page=2", server.takeRequest().path)
        }
    }
}
