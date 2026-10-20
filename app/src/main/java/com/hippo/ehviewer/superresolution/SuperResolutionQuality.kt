package com.hippo.ehviewer.superresolution

internal enum class SuperResolutionQuality(val value: String, val webpQuality: Int?) {
    Q80("80", 80),
    Q85("85", 85),
    Q90("90", 90),
    Q95("95", 95),
    Q100("100", 100),
    LOSSLESS("lossless", null);

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: Q95
    }
}
