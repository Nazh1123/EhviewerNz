package com.hippo.ehviewer.translation

import android.graphics.Bitmap

/** Turns an owned full render into a sparse overlay, retaining repaired background and glyphs. */
internal object TranslationOverlay {
    fun extract(original: Bitmap, rendered: Bitmap): Bitmap {
        require(original.width == rendered.width && original.height == rendered.height)
        require(original !== rendered)
        val width = rendered.width
        val source = IntArray(width)
        val output = IntArray(width)
        rendered.setHasAlpha(true)
        for (y in 0 until rendered.height) {
            original.getPixels(source, 0, width, 0, y, width, 1)
            rendered.getPixels(output, 0, width, 0, y, width, 1)
            for (x in 0 until width) if (output[x] == source[x]) output[x] = 0
            rendered.setPixels(output, 0, width, 0, y, width, 1)
        }
        return rendered
    }
}
