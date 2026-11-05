package com.hippo.ehviewer.download

import android.content.Context
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhEngine
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Locale
import java.text.DecimalFormat

object ArchiveQuota {
    @Volatile private var instance: ArchiveQuotaLedger? = null

    @JvmStatic
    fun ledger(context: Context): ArchiveQuotaLedger = instance ?: synchronized(this) {
        instance ?: ArchiveQuotaLedger(context.applicationContext.getSharedPreferences(
            "archive_quota", Context.MODE_PRIVATE)).also { instance = it }
    }

    @JvmStatic
    fun account(context: Context): String = EhApplication.getEhCookieStore(context)
        .getCookies(HttpUrl.parse(EhUrl.HOST_E)!!)
        .firstOrNull { it.name() == EhCookieStore.KEY_IPD_MEMBER_ID }?.value().orEmpty()

    @JvmStatic
    fun loadQuota(context: Context, accountId: String, forceRefresh: Boolean,
                  task: EhClient.Task?, client: OkHttpClient): Long {
        if (accountId.isEmpty() || accountId != account(context)) throw IOException("Account changed")
        return ledger(context).loadQuota(accountId, forceRefresh) {
            val value = EhEngine.getArchiveQuota(task, client)
            if (accountId != account(context)) throw IOException("Account changed")
            value
        }
    }

    @JvmStatic
    fun format(context: Context, account: String): String {
        val ledger = ledger(context)
        val state = ledger.snapshot(account, System.currentTimeMillis())
        val quota = ledger.quota(account)
        val remaining = String.format(Locale.getDefault(), "%.1f",
            (quota - state.usedBytes).coerceAtLeast(0L) / 1_000_000_000.0)
        val total = DecimalFormat("0.#").format(quota / 1_000_000_000.0)
        return if (state.usedBytes == 0L) {
            context.getString(com.hippo.ehviewer.R.string.archive_quota_initial, remaining, total)
        } else {
            val hours = String.format(Locale.getDefault(), "%.1f", state.refreshInMillis / 3_600_000.0)
            context.getString(com.hippo.ehviewer.R.string.archive_quota_usage, remaining, total, hours)
        }
    }
}
