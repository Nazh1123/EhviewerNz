package com.hippo.ehviewer.translation

import android.app.Application
import android.net.NetworkCapabilities
import android.os.Looper
import com.hippo.ehviewer.R
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "zh-rCN", manifest = Config.NONE)
class TranslationDownloadNetworkTest {
    private fun capabilities(vararg transports: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { result ->
            transports.forEach { shadowOf(result).addTransportType(it) }
        }

    private fun notify(capabilities: NetworkCapabilities?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val job = scope.launch { notifyModelDownloadNetwork(RuntimeEnvironment.getApplication(), capabilities) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Notification must finish without waiting for permission", job.isCompleted)
            assertFalse(job.isCancelled)
        } finally { scope.cancel() }
    }

    @Test fun cellularDownloadShowsNoticeAndContinues() {
        notify(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals("正在使用移动网络下载", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test fun wifiEthernetAndMissingNetworkDoNotRaiseAMobileNoticeOrBlockDownloads() {
        for (network in listOf(capabilities(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities(NetworkCapabilities.TRANSPORT_ETHERNET),
            capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_WIFI), null)) {
            notify(network)
            assertEquals(0, ShadowToast.shownToastCount())
        }
        assertEquals("正在使用移动网络下载", RuntimeEnvironment.getApplication().getString(R.string.translation_mobile_download))
    }

    @Test fun googleModelDownloadConditionsAllowMobileData() {
        assertFalse(OfflineTranslator.modelDownloadConditions().isWifiRequired)
    }
}
