package com.hippo.ehviewer.client

import android.app.Application
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.exception.ParseException
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ArchiveQuotaRequestTest {
    private var donationRequests = 0
    private var archiveRequests = 0
    @Before fun setup() {
        Settings.initialize(RuntimeEnvironment.getApplication())
        Settings.putGallerySite(EhUrl.SITE_EX)
    }

    private fun client(donation: String?): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val text = if (request.url().encodedPath() == "/bitcoin.php") {
                donationRequests++
                assertEquals("e-hentai.org", request.url().host())
                donation ?: throw IOException("Account page unavailable")
            } else {
                archiveRequests++
                javaClass.getResource("/com/hippo/ehviewer/client/parser/ArchiveDownloadDialog.html")!!
                    .readText()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(MediaType.parse("text/html"), text)).build()
        }.build()

    @Test fun openingArchiveChoicesDoesNotRequestDonationStatus() {
        val data = EhEngine.getArchiver(null, client(null),
            "https://exhentai.org/archiver.php?gid=1", 1, "token")
        assertEquals(0, donationRequests)
        assertEquals(1, archiveRequests)
        assertEquals("Free!", data.originalCost)
        assertEquals("18.46 MiB", data.originalSize)
        assertEquals("2.17 MiB", data.resampleSize)
        assertTrue(data.originalUrl.contains("archiver.php"))
    }

    @Test fun refreshingDonationStatusDoesNotRefetchArchiveChoices() {
        val quota = EhEngine.getArchiveQuota(null,
            client("<table><tr id=dlvl><td>Bronze Star</td></tr></table>"))
        assertEquals(5_000_000_000L, quota)
        assertEquals(1, donationRequests)
        assertEquals(0, archiveRequests)
    }

    @Test(expected = IOException::class)
    fun unavailableAccountPageIsReportedAsARefreshFailure() {
        EhEngine.getArchiveQuota(null, client(null))
    }

    @Test(expected = ParseException::class)
    fun unknownAccountPageIsNotTreatedAsAnOrdinaryUser() {
        EhEngine.getArchiveQuota(null, client("<h1>Please sign in</h1>"))
    }
}
