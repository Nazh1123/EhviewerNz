package com.hippo.ehviewer.client

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.parser.GalleryListParser
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Read-only, explicit opt-in: no unread state, checkpoints, or bookmarks are changed. */
@RunWith(AndroidJUnit4::class)
class SubscriptionNetworkDeviceTest {
    private fun phase(value: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "subscription-probe-phase.txt")
            .writeText("${SystemClock.elapsedRealtime()} $value")
    }

    @Test fun refreshesStalledBodyOnDevice() {
        assumeTrue("Pass testSubscriptionNetwork=1 to run transport diagnostics",
            InstrumentationRegistry.getArguments().getString("testSubscriptionNetwork") == "1")
        phase("controlled:start")
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 8000
        val workers = Executors.newCachedThreadPool()
        val originalClosed = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val html = "<html><div class=\"itg\">refreshed</div></html>"
        workers.submit {
            repeat(2) {
                val socket = server.accept()
                val number = requests.incrementAndGet()
                workers.submit {
                    socket.use {
                        socket.soTimeout = 8000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.flush()
                        if (number == 1) {
                            try { input.read() } finally { originalClosed.countDown() }
                        } else {
                            output.write(html.toByteArray())
                            output.flush()
                        }
                    }
                }
            }
        }
        val started = SystemClock.elapsedRealtime()
        try {
            val call = SubscriptionPageCall(client, Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/search").build())
            call.execute().use { assertEquals(html, requireNotNull(it.body()).string()) }
            phase("controlled:response")
            val elapsed = SystemClock.elapsedRealtime() - started
            assertEquals(2, requests.get())
            assertFalse("Internal cancellation is not user cancellation", call.isCanceled)
            assertTrue("Original socket must close when the refresh wins", originalClosed.await(2, TimeUnit.SECONDS))
            assertTrue("Must refresh after the production delay", elapsed >= 2500)
            assertTrue("Must finish before the stalled original", elapsed < 7000)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "subscription-refresh-device-report.json").writeText(
                JSONObject().put("elapsedMs", elapsed).put("requests", requests.get())
                    .put("originalClosed", true).put("success", true).toString(2))
        } finally {
            server.close()
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
            phase("controlled:evict")
            client.connectionPool().evictAll()
            phase("controlled:done")
        }
    }

    @Test fun fetchSavedSubscriptionsWithBoundedRefresh() {
        assumeTrue("Pass testSubscriptionNetwork=1 to access the live site",
            InstrumentationRegistry.getArguments().getString("testSubscriptionNetwork") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        phase("live:plans")
        val plans = BookmarkSubscriptionPlanner.plan(EhDB.getSubscribedQuickSearch())
        assumeTrue(plans.isNotEmpty())
        val requests = AtomicInteger()
        val handshakes = AtomicInteger()
        val acquisitions = AtomicInteger()
        val connections = ConcurrentHashMap.newKeySet<Int>()
        val stopped = AtomicBoolean()
        phase("live:client")
        val client = EhApplication.getOkHttpClient(context).newBuilder().cache(null)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { requests.incrementAndGet() }
                override fun secureConnectStart(call: Call) { handshakes.incrementAndGet() }
                override fun connectionAcquired(call: Call, connection: Connection) {
                    acquisitions.incrementAndGet()
                    connections.add(System.identityHashCode(connection))
                }
            }).build()
        val executor = Executors.newFixedThreadPool(8)
        val started = SystemClock.elapsedRealtime()
        val records = JSONArray()
        phase("live:requests")
        try {
            val tasks = plans.mapIndexed { index, plan -> Callable {
                val row = JSONObject().put("source", index)
                val start = SystemClock.elapsedRealtime()
                if (!stopped.get()) {
                    try {
                        val request = EhRequestBuilder(plan.createBuilder().buildWithoutRatingFilter(), EhUrl.getReferer()).build()
                        SubscriptionPageCall(client, request).execute().use { response ->
                            val body = requireNotNull(response.body()).string()
                            val lower = body.lowercase()
                            val limited = response.code() in listOf(429, 509) ||
                                listOf("temporarily banned", "excessive pageload", "too many requests", "please wait a bit longer").any(lower::contains)
                            if (limited) stopped.set(true)
                            row.put("status", response.code()).put("limited", limited)
                            if (response.isSuccessful && !limited) {
                                val parsed = GalleryListParser.parseMergedUploaderSearch(body, plan.createBuilder().mode)
                                row.put("success", parsed.customErrorString == null)
                                    .put("galleries", parsed.rawResultCount)
                            }
                        }
                    } catch (error: Exception) {
                        row.put("error", error.javaClass.simpleName)
                    }
                }
                row.put("elapsedMs", SystemClock.elapsedRealtime() - start)
            } }
            executor.invokeAll(tasks, 60, TimeUnit.SECONDS).forEach { future ->
                records.put(if (future.isCancelled) JSONObject().put("error", "budget_exceeded") else future.get())
            }
            phase("live:responses")
        } finally {
            executor.shutdownNow()
            val report = JSONObject().put("sources", plans.size).put("httpCalls", requests.get())
                .put("tlsHandshakes", handshakes.get()).put("connections", connections.size)
                .put("connectionAcquisitions", acquisitions.get())
                .put("reusedAcquisitions", acquisitions.get() - connections.size)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("records", records)
            File(context.getExternalFilesDir(null), "subscription-network-report.json").writeText(report.toString(2))
        }
        assertFalse("Site reported a limit", stopped.get())
        assertEquals(plans.size, (0 until records.length()).count { records.getJSONObject(it).optBoolean("success") })
        phase("live:done")
    }
}
