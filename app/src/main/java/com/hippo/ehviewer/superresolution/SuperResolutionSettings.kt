package com.hippo.ehviewer.superresolution

import android.content.Context
import androidx.preference.PreferenceManager

internal data class SuperResolutionOptions(
    val mode: String = SuperResolutionModels.DEFAULT_BACKEND,
    val ahead: Int = 2,
    val persist: Boolean = true,
    val cacheMiB: Int = 512,
) {
    // Changes to the model/padding/quantization require a new identity.
    val modelIdentity get() = "upconv7-64914665-v2:$mode"
}

internal class SuperResolutionSettings(context: Context) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    fun read() = SuperResolutionOptions(
        mode = preferences.getString(SuperResolutionModels.BACKEND, SuperResolutionModels.DEFAULT_BACKEND)
            .takeIf { it in listOf("auto_fast", "auto", "cpu") } ?: SuperResolutionModels.DEFAULT_BACKEND,
        ahead = preferences.getString(AHEAD, "2")?.toIntOrNull()?.coerceIn(0, 10) ?: 2,
        persist = preferences.getBoolean(PERSIST, true),
        cacheMiB = preferences.getString(CACHE, "512")?.toIntOrNull()?.coerceIn(64, 8192) ?: 512,
    )
    var automatic: Boolean
        get() = preferences.getBoolean(SuperResolutionModels.ENABLED, false)
        set(value) { preferences.edit().putBoolean(SuperResolutionModels.ENABLED, value).apply() }

    companion object {
        const val AHEAD = "super_resolution_ahead"
        const val PERSIST = "super_resolution_persist"
        const val CACHE = "super_resolution_cache_mib"
    }
}
