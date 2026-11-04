package com.hippo.ehviewer.client.parser

import org.jsoup.Jsoup
import java.util.Locale

/** Read only the account's current level, never the table advertising all donation levels. */
object ArchiveQuotaParser {
    @JvmStatic
    fun parseDonationQuota(body: String): Long? {
        val current = Jsoup.parse(body).getElementById("dlvl") ?: return null
        val description = current.text().lowercase(Locale.ROOT)
        if ("did not donate" in description || "have not donated" in description) return 0
        val level = current.selectFirst(".tdon1") ?: current
        val text = (level.text() + " " + level.select("img").joinToString(" ") {
            it.attr("alt") + " " + it.attr("title")
        }).lowercase(Locale.ROOT)
        val gb = when {
            "catgirl" in text -> 100
            Regex("septua\\s*star").containsMatchIn(text) -> 80
            Regex("quint\\s*star").containsMatchIn(text) -> 60
            Regex("tri\\s*star").containsMatchIn(text) -> 40
            "gold" in text -> 20
            "silver" in text -> 10
            "bronze" in text -> 5
            else -> return null
        }
        // Donation quotas are expressed in decimal GB: https://ehwiki.org/wiki/Donation
        return gb * 1_000_000_000L
    }

    @JvmStatic
    fun isFree(cost: String?): Boolean = cost != null &&
        Regex("(?i)^(free!?|0(?:[.,]0+)?\\s*(?:GP|credits?)?)$").matches(cost.trim())
}
