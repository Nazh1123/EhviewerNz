package com.hippo.ehviewer.client

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.network.EhSSLSocketFactory
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

/** Opt-in comparison using the saved account and searches, without changing settings. */
@RunWith(AndroidJUnit4::class)
class SearchConnectionDeviceTest {
    @Test fun compareFrontedSearchConnectionReuse() {
        assumeTrue("Provide testSearchNetwork=1 for the live network benchmark",
            InstrumentationRegistry.getArguments().getString("testSearchNetwork") == "1")
        assumeTrue("The regression affects domain fronting", Settings.getDF())
        val base = EhApplication.getOkHttpClient(InstrumentationRegistry.getInstrumentation().targetContext)
        val plans = BookmarkSubscriptionPlanner.plan(EhDB.getSubscribedQuickSearch()).take(3)
        assumeTrue("At least one subscribed search is needed", plans.isNotEmpty())
        val urls = plans.map { it.createBuilder().build() }
        // Each variant gets a cold pool and the same queries; alternate the order to
        // expose network variation rather than treating a single elapsed time as a guarantee.
        val measurements = listOf(true, false, false, true).map { fixed ->
            val handshakes = AtomicInteger()
            val factory = if (fixed) EhSSLSocketFactory() else object : EhSSLSocketFactory() {
                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                    val address = s.inetAddress
                    if (autoClose) s.close()
                    return (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(address, port)
                }
            }
            val client = base.newBuilder()
                .sslSocketFactory(factory, base.x509TrustManager!!)
                .connectionPool(ConnectionPool(8, 1, TimeUnit.MINUTES))
                .cache(null)
                .callTimeout(20, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun secureConnectStart(call: Call) { handshakes.incrementAndGet() }
                }).build()
            var successful = 0
            val start = SystemClock.elapsedRealtime()
            try {
                repeat(2) {
                    for (url in urls) {
                        try {
                            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                val body = response.body.string()
                                if (response.isSuccessful && body.contains("class=\"itg")) successful++
                            }
                        } catch (_: Exception) { /* Report only counts; URLs contain saved searches. */ }
                    }
                }
            } finally {
                client.connectionPool.evictAll()
            }
            Measurement(fixed, urls.size * 2, successful, handshakes.get(), SystemClock.elapsedRealtime() - start)
                .also { Log.i("SearchConnectionBenchmark", it.toString()) }
        }
        val fixed = measurements.filter { it.fixed }
        assertTrue("Fixed searches must return gallery HTML", fixed.all { it.successful == it.requests })
        assertTrue("Fixed searches must reuse TLS", fixed.all { it.handshakes < it.requests })
    }

    private data class Measurement(val fixed: Boolean, val requests: Int,
        val successful: Int, val handshakes: Int, val elapsedMs: Long)
}
