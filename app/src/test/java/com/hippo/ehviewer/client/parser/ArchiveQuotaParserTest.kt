package com.hippo.ehviewer.client.parser

import org.junit.Assert.*
import org.junit.Test

class ArchiveQuotaParserTest {
    @Test fun recognizesCurrentDonationLevelInsteadOfAdvertisedLevels() {
        for ((level, quota) in listOf("Bronze Star" to 5, "Silver Star" to 10,
            "Gold Star" to 20, "Tri Star" to 40, "Quintstar" to 60,
            "Septua Star" to 80, "Honorary Catgirl Lv.7" to 100)) {
            val html = "<table id=tdon><tr id=dlvl><td>$level</td></tr>" +
                "<tr><td>Honorary Catgirl 100 GB</td></tr></table>"
            assertEquals(quota * 1_000_000_000L, ArchiveQuotaParser.parseDonationQuota(html))
        }
    }

    @Test fun ordinaryUsersFreeArchivesAndUnavailablePagesNeverImplyDonation() {
        assertEquals(0L, ArchiveQuotaParser.parseDonationQuota(
            "<table><tr id=dlvl><td>You did not donate anything yet, but we still love you.</td></tr></table>"))
        assertEquals(0L, ArchiveQuotaParser.parseDonationQuota(
            "<table><tr id=dlvl><td class=tdon1>None</td>" +
                "<td class=tdon2>You did not donate anything yet, but we still love you.</td></tr></table>"))
        assertNull(ArchiveQuotaParser.parseDonationQuota("<p>Download Cost: Free!</p>"))
        assertNull(ArchiveQuotaParser.parseDonationQuota("<table id=tdon><tr><td>Bronze Star</td></tr></table>"))
        assertNull(ArchiveQuotaParser.parseDonationQuota("<h1>Please log in</h1>"))
        assertNull(ArchiveQuotaParser.parseDonationQuota("<div id=dlvl>Unknown new level</div>"))
    }

    @Test fun currentLevelDescriptionCannotPromoteTheAccount() {
        assertEquals(5_000_000_000L, ArchiveQuotaParser.parseDonationQuota(
            "<table><tr id=dlvl><td class=tdon1>Bronze Star</td>" +
                "<td class=tdon2>Donate more for a Gold Star</td></tr></table>"))
    }

    @Test fun distinguishesFreeArchivesFromGpCosts() {
        for (cost in listOf("Free!", "Free", "  free! ", "0 GP", "0 Credits"))
            assertTrue(cost, ArchiveQuotaParser.isFree(cost))
        for (cost in listOf(null, "", "2,000 GP", "10 Credits", "Free quota exhausted"))
            assertFalse(cost, ArchiveQuotaParser.isFree(cost))
    }
}
