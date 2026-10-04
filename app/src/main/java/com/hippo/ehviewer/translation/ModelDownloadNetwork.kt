package com.hippo.ehviewer.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.hippo.ehviewer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun notifyModelDownloadNetwork(context: Context) {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    notifyModelDownloadNetwork(context, manager.getNetworkCapabilities(manager.activeNetwork))
}

internal suspend fun notifyModelDownloadNetwork(context: Context, capabilities: NetworkCapabilities?) {
    val mobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    if (mobile && !wifi) withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, R.string.translation_mobile_download, Toast.LENGTH_LONG).show()
    }
}
