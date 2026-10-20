package com.hippo.ehviewer.superresolution

import android.content.Context
import androidx.preference.PreferenceManager

internal data class SuperResolutionOptions(
    val mode: String = SuperResolutionModels.DEFAULT_BACKEND,
    val ahead: Int = 2,
    val persist: Boolean = true,
    val cacheMiB: Int = 512,
    val halfArithmetic: Boolean = false,
    val batchTiles: Int = 1,
    val scale: SuperResolutionScale = SuperResolutionScale.X2,
    val quality: SuperResolutionQuality = SuperResolutionQuality.Q95,
) {
    // Changes to the model/padding/quantization require a new identity.
    val modelIdentity get() = "upconv7-64914665-v2:$mode" + if (mode == "auto_fast" && halfArithmetic) ":fp16-arithmetic" else ""
}

internal class SuperResolutionSettings(context: Context) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    fun read() = SuperResolutionOptions(
        mode = preferences.getString(SuperResolutionModels.BACKEND, SuperResolutionModels.DEFAULT_BACKEND)
            .takeIf { it in listOf("auto_fast", "auto", "cpu") } ?: SuperResolutionModels.DEFAULT_BACKEND,
        ahead = preferences.getString(AHEAD, "2")?.toIntOrNull()?.coerceIn(0, 10) ?: 2,
        persist = preferences.getBoolean(PERSIST, true),
        cacheMiB = preferences.getString(CACHE, "512")?.toIntOrNull()?.coerceIn(64, 8192) ?: 512,
        halfArithmetic = preferences.getBoolean("sr_validated_fp16_arithmetic", false),
        batchTiles = preferences.getInt("sr_validated_gpu_batch", 1).coerceIn(1, 4),
        scale = SuperResolutionScale.fromValue(preferences.getString(SCALE, "2")),
        quality = SuperResolutionQuality.fromValue(preferences.getString(QUALITY, "95")),
    )
    var automatic: Boolean
        get() = preferences.getBoolean(AUTO_ENABLE, false)
        set(value) { preferences.edit().putBoolean(AUTO_ENABLE, value).apply() }

    companion object {
        // The old enabled key was also written by reader clicks; do not inherit that state.
        const val AUTO_ENABLE = "super_resolution_auto_enable_on_entry"
        const val AHEAD = "super_resolution_ahead"
        const val PERSIST = "super_resolution_persist"
        const val CACHE = "super_resolution_cache_mib"
        const val SCALE = "super_resolution_scale"
        const val QUALITY = "super_resolution_quality"
    }
}
