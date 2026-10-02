package com.hippo.ehviewer.translation

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiEndpointNetworkDeviceTest {
    @Test fun httpApiHostsArePermittedByAndroidNetworkPolicy() {
        val policy = NetworkSecurityPolicy.getInstance()
        listOf("127.0.0.1", "localhost", "::1", "192.168.1.100", "10.0.0.5", "172.16.0.5",
            "llm-pc.local", "fd00::1234", "example.com").forEach { host ->
            assertTrue("HTTP should be permitted for $host", policy.isCleartextTrafficPermitted(host))
        }
    }

    /** Explicit opt-in endpoint for LAN/remote smoke tests; leaves saved settings untouched. */
    @Test fun translatesWithExplicitApiEndpoint() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("testApiUrl")
        assumeTrue("Provide testApiUrl to call an API", !endpoint.isNullOrBlank())
        val options = TranslationOptions(backend = TranslationBackend.LLM_API, apiUrl = endpoint!!,
            apiModel = args.getString("testApiModel") ?: "", apiKey = args.getString("testApiKey") ?: "")
        val source = listOf("こんにちは。", "明日は学校へ行きます。")
        ApiTranslator(options).use { translator ->
            val translations = translator.translate(source)
            assertEquals(source.size, translations.size)
            translations.forEachIndexed { index, text ->
                assertTrue(text.isNotBlank())
                assertNotEquals(source[index], text)
                assertFalse(text.contains("<think>"))
            }
        }
    }
}
