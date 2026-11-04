package com.hippo.ehviewer.download

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local estimates, partitioned by account. Each minute's bytes expire after 168 hours. */
class ArchiveQuotaLedger(private val preferences: SharedPreferences) {
    data class Snapshot(val usedBytes: Long, val refreshInMillis: Long)

    @Synchronized
    fun quota(account: String): Long = preferences.getLong("quota:$account", 0)

    @Synchronized
    fun hasQuota(account: String): Boolean = preferences.contains("quota:$account")

    /** Cache zero as well as donor levels; failed requests leave the previous value intact. */
    fun loadQuota(account: String, forceRefresh: Boolean, fetch: () -> Long): Long {
        if (!forceRefresh && hasQuota(account)) return quota(account)
        // Network I/O must not hold the ledger lock and block download progress recording.
        val value = fetch()
        setQuota(account, value)
        return value
    }

    @Synchronized
    fun setQuota(account: String, bytes: Long) {
        preferences.edit().putLong("quota:$account", bytes.coerceAtLeast(0)).apply()
    }

    @Synchronized
    fun snapshot(account: String, now: Long): Snapshot {
        val buckets = readBuckets(account, now)
        var used = 0L
        var next = Long.MAX_VALUE
        for (i in 0 until buckets.length()) {
            val bucket = buckets.getJSONArray(i)
            used += bucket.getLong(1)
            next = minOf(next, bucket.getLong(0) + WINDOW_MILLIS - now)
        }
        return Snapshot(used, if (used == 0L) 0 else next.coerceAtLeast(0))
    }

    @Synchronized
    fun begin(taskId: Long, account: String, eligible: Boolean) {
        if (!eligible || account.isEmpty() || quota(account) <= 0) return
        preferences.edit().putString("task:$taskId", JSONObject()
            .put("account", account).put("high", 0L).toString()).apply()
    }

    /** High-water marks survive pause, restart, ignored Range headers and manual resets. */
    @Synchronized
    fun recordProgress(taskId: Long, downloaded: Long, now: Long) {
        val task = preferences.getString("task:$taskId", null)?.let { JSONObject(it) } ?: return
        val previous = task.getLong("high")
        if (downloaded <= previous) return
        val account = task.getString("account")
        val buckets = readBuckets(account, now)
        // Round UP: never restore bytes early, at most one minute late.
        val minute = ((now + BUCKET_MILLIS - 1) / BUCKET_MILLIS) * BUCKET_MILLIS
        val last = if (buckets.length() > 0) buckets.getJSONArray(buckets.length() - 1) else null
        if (last != null && last.getLong(0) == minute) {
            last.put(1, last.getLong(1) + downloaded - previous)
        } else {
            buckets.put(JSONArray().put(minute).put(downloaded - previous))
        }
        task.put("high", downloaded)
        preferences.edit().putString("usage:$account", buckets.toString())
            .putString("task:$taskId", task.toString()).apply()
    }

    @Synchronized
    fun finish(taskId: Long) {
        preferences.edit().remove("task:$taskId").apply()
    }

    @Synchronized
    fun reset(account: String) {
        // Keep task high-water marks: active downloads count only bytes received after reset.
        preferences.edit().remove("usage:$account").apply()
    }

    private fun readBuckets(account: String, now: Long): JSONArray {
        val stored = JSONArray(preferences.getString("usage:$account", "[]"))
        val live = JSONArray()
        for (i in 0 until stored.length()) {
            val bucket = stored.getJSONArray(i)
            if (bucket.getLong(0) + WINDOW_MILLIS > now) live.put(bucket)
        }
        return live
    }

    companion object {
        const val WINDOW_MILLIS = 168L * 60 * 60 * 1000
        private const val BUCKET_MILLIS = 60_000L
    }
}
