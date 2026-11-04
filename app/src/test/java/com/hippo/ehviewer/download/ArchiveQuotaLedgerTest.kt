package com.hippo.ehviewer.download

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ArchiveQuotaLedgerTest {
    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("quota_test", Context.MODE_PRIVATE)
    private lateinit var ledger: ArchiveQuotaLedger
    private val start = 1_800_000_000_000L // A whole minute.

    @Before fun setup() {
        preferences.edit().clear().commit()
        ledger = ArchiveQuotaLedger(preferences)
        ledger.setQuota("a", 5_000_000_000)
    }

    @Test fun eachDownloadExpiresSeparatelyAfter168Hours() {
        ledger.begin(1, "a", true)
        ledger.recordProgress(1, 400, start)
        ledger.recordProgress(1, 1000, start + 3_600_000)
        assertEquals(1000, ledger.snapshot("a", start + 3_600_000).usedBytes)
        assertEquals(167 * 3_600_000L, ledger.snapshot("a", start + 3_600_000).refreshInMillis)
        val firstExpiry = start + ArchiveQuotaLedger.WINDOW_MILLIS
        assertEquals(1000, ledger.snapshot("a", firstExpiry - 1).usedBytes)
        assertEquals(600, ledger.snapshot("a", firstExpiry).usedBytes)
        assertEquals(3_600_000, ledger.snapshot("a", firstExpiry).refreshInMillis)
        assertEquals(ArchiveQuotaLedger.Snapshot(0, 0), ledger.snapshot("a", firstExpiry + 3_600_000))
    }

    @Test fun minuteRoundingNeverRestoresBytesEarly() {
        ledger.begin(1, "a", true)
        ledger.recordProgress(1, 100, start + 123)
        assertEquals(100, ledger.snapshot("a", start + 123 + ArchiveQuotaLedger.WINDOW_MILLIS).usedBytes)
        assertEquals(0, ledger.snapshot("a", start + 60_000 + ArchiveQuotaLedger.WINDOW_MILLIS).usedBytes)
    }

    @Test fun resumeAndServerRestartDoNotCountTheSameProgressTwice() {
        ledger.begin(1, "a", true)
        ledger.recordProgress(1, 500, start)
        ledger = ArchiveQuotaLedger(preferences) // Simulate process recreation.
        ledger.recordProgress(1, 500, start + 1000)
        ledger.recordProgress(1, 200, start + 2000) // Server ignored Range.
        ledger.recordProgress(1, 700, start + 3000)
        assertEquals(700, ledger.snapshot("a", start + 3000).usedBytes)
    }

    @Test fun resettingActiveDownloadKeepsOnlyFutureBytesAndStartsANewClock() {
        ledger.begin(1, "a", true)
        ledger.recordProgress(1, 400, start)
        ledger.reset("a")
        assertEquals(ArchiveQuotaLedger.Snapshot(0, 0), ledger.snapshot("a", start))
        assertEquals(5_000_000_000, ledger.quota("a"))
        ledger.recordProgress(1, 400, start + 3_600_000)
        assertEquals(0, ledger.snapshot("a", start + 3_600_000).usedBytes)
        ledger.recordProgress(1, 500, start + 3_600_000)
        assertEquals(100, ledger.snapshot("a", start + 3_600_000).usedBytes)
        assertEquals(ArchiveQuotaLedger.WINDOW_MILLIS,
            ledger.snapshot("a", start + 3_600_000).refreshInMillis)
    }

    @Test fun paidDownloadsAndAccountsWithoutDonationQuotaAreExcluded() {
        ledger.begin(1, "a", false)
        ledger.begin(2, "b", true)
        ledger.recordProgress(1, 500, start)
        ledger.recordProgress(2, 500, start)
        assertEquals(0, ledger.snapshot("a", start).usedBytes)
        assertEquals(0, ledger.snapshot("b", start).usedBytes)
    }

    @Test fun concurrentTasksAndAccountsStayIndependentAndFinishingKeepsUsage() {
        ledger.setQuota("b", 10_000_000_000)
        ledger.begin(1, "a", true)
        ledger.begin(2, "a", true)
        ledger.begin(3, "b", true)
        ledger.recordProgress(1, 400, start)
        ledger.recordProgress(2, 300, start)
        ledger.recordProgress(3, 200, start)
        ledger.finish(1) // Cancellation/failure also keeps already downloaded bytes.
        ledger.recordProgress(1, 1000, start)
        assertEquals(700, ledger.snapshot("a", start).usedBytes)
        assertEquals(200, ledger.snapshot("b", start).usedBytes)
        ledger.reset("a")
        assertEquals(200, ledger.snapshot("b", start).usedBytes)
    }

    @Test fun displayShowsRemainingQuotaAndResetRestoresTheFullAmount() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("archive_quota", Context.MODE_PRIVATE).edit().clear().commit()
            val displayLedger = ArchiveQuota.ledger(context)
            displayLedger.setQuota("display", 5_000_000_000)
            assertTrue(ArchiveQuota.format(context, "display").startsWith("5.0 / 5 GB"))
            displayLedger.begin(101, "display", true)
            displayLedger.recordProgress(101, 2_400_000_000, System.currentTimeMillis())
            assertTrue(ArchiveQuota.format(context, "display").startsWith("2.6 / 5 GB"))
            displayLedger.recordProgress(101, 6_000_000_000, System.currentTimeMillis())
            assertTrue(ArchiveQuota.format(context, "display").startsWith("0.0 / 5 GB"))
            displayLedger.reset("display")
            assertTrue(ArchiveQuota.format(context, "display").startsWith("5.0 / 5 GB"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test fun cachedDonationLevelsAndOrdinaryAccountsSurviveRecreationWithoutFetching() {
        var requests = 0
        assertEquals(5_000_000_000, ledger.loadQuota("a", false) { requests++; 100 })
        assertEquals(0, ledger.loadQuota("b", false) { requests++; 0 })
        ledger = ArchiveQuotaLedger(preferences)
        assertTrue(ledger.hasQuota("b"))
        assertEquals(0, ledger.loadQuota("b", false) { requests++; 500 })
        assertEquals(5_000_000_000, ledger.loadQuota("a", false) { requests++; 100 })
        assertEquals(1, requests)
        assertFalse(ledger.hasQuota("unknown"))
    }

    @Test fun manualRefreshUpdatesTheLevelAndRemainingBytesWithoutResettingUsage() {
        ledger.begin(1, "a", true)
        ledger.recordProgress(1, 2_400_000_000, start)
        var requests = 0
        assertEquals(10_000_000_000, ledger.loadQuota("a", true) { requests++; 10_000_000_000 })
        assertEquals(1, requests)
        val updated = ledger.snapshot("a", start + 3_600_000)
        assertEquals(7_600_000_000, ledger.quota("a") - updated.usedBytes)
        assertEquals(167 * 3_600_000L, updated.refreshInMillis)
        assertEquals(0, ledger.snapshot("a", start + ArchiveQuotaLedger.WINDOW_MILLIS).usedBytes)
    }

    @Test fun failedManualRefreshRetainsTheCachedLevelAndUsage() {
        ledger.begin(1, "a", true)
        ledger.recordProgress(1, 400, start)
        try {
            ledger.loadQuota("a", true) { throw IOException("offline") }
            fail("Refresh must report the failure")
        } catch (_: IOException) {
            assertEquals(5_000_000_000, ledger.quota("a"))
            assertEquals(400, ledger.snapshot("a", start).usedBytes)
        }
        try {
            ledger.loadQuota("unknown", false) { throw IOException("offline") }
            fail("Initial lookup must report the failure")
        } catch (_: IOException) {
            assertFalse(ledger.hasQuota("unknown"))
        }
    }
}
