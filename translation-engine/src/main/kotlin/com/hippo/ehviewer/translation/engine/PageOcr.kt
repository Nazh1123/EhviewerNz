package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap

interface PageOcr : AutoCloseable {
    suspend fun recognize(page: Bitmap, lines: List<TextLine>)
    fun warmUp() {}
}
