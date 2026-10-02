package com.hippo.network

import android.app.Application
import android.content.Context
import com.hippo.ehviewer.Settings
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class EhSSLSocketFactoryTest {
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Settings::class.java, "sSettingsPre",
            context.getSharedPreferences("tls-test", Context.MODE_PRIVATE))
        Settings.putDF(true)
    }

    @Test fun frontedSearchAndMetadataRequestsReuseTheirTlsConnection() {
        verifyConnectionReuse(true, false)
    }

    @Test fun olderAndroidFrontedRequestsReuseTheirTlsConnection() {
        verifyConnectionReuse(true, true)
    }

    @Test fun ordinaryTlsStillReusesConnections() {
        verifyConnectionReuse(false, false)
        verifyConnectionReuse(false, true)
    }

    private fun verifyConnectionReuse(fronting: Boolean, lowSdk: Boolean) {
        Settings.putDF(fronting)
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        val handshakes = AtomicInteger()
        val factory: SSLSocketFactory = if (lowSdk) EhSSLSocketFactoryLowSDK(clientTls.sslSocketFactory())
            else EhSSLSocketFactory(clientTls.sslSocketFactory())
        val client = OkHttpClient.Builder()
            .sslSocketFactory(factory, clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1))
            .callTimeout(5, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun secureConnectStart(call: Call) { handshakes.incrementAndGet() }
            }).build()
        try {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.enqueue(MockResponse().setBody("search"))
                server.enqueue(MockResponse().setBody("metadata"))
                client.newCall(Request.Builder().url(server.url("/search")).build()).execute().use {
                    assertEquals("search", it.body.string())
                }
                client.newCall(Request.Builder().url(server.url("/api.php")).build()).execute().use {
                    assertEquals("metadata", it.body.string())
                }
                assertEquals("The second request must reuse TLS instead of reconnecting", 1, handshakes.get())
                assertEquals(0, server.takeRequest().sequenceNumber)
                assertEquals(1, server.takeRequest().sequenceNumber)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
