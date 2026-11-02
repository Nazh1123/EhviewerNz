package com.hippo.ehviewer.superresolution

internal enum class SuperResolutionScale(val value: String, private val factor: Int) {
    AUTO("auto", 0), X2("2", 2), X4("4", 4), X8("8", 8);

    fun resolve(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        if (this != AUTO) return factor
        val shortSide = minOf(width, height).toLong()
        return listOf(2, 4, 8).firstOrNull { shortSide * it >= 1200 } ?: 8
    }

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: X2
    }
}
