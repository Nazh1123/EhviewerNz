package com.hippo.ehviewer.translation.engine

import org.junit.Assert.*
import org.junit.Test

class RemovalBackgroundTest {
    @Test fun flatColorsRemainExactAndMaskedLettersDoNotAffectClassification() {
        for (color in listOf(0xfffafafa.toInt(), 0xff080808.toInt(), 0xffb9dcec.toInt())) {
            val pixels = IntArray(64 * 64) { color }
            val mask = ByteArray(pixels.size)
            for (y in 20..40) for (x in 20..40) { pixels[y * 64 + x] = color.inv(); mask[y * 64 + x] = 1 }
            val sample = RemovalBackground.sample(pixels, mask, 64, intArrayOf(0, 0, 64, 64))
            assertTrue(sample.flat)
            assertEquals(color, sample.color)
        }
    }

    @Test fun gradientsTextureAndInsufficientBackgroundRequireAi() {
        val box = intArrayOf(0, 0, 64, 64)
        val gradient = IntArray(4096) { 0xff000000.toInt() or ((it % 64 * 4) * 0x010101) }
        assertFalse(RemovalBackground.sample(gradient, ByteArray(4096), 64, box).flat)
        val texture = IntArray(4096) { if (it % 7 == 0) 0xff555555.toInt() else -1 }
        assertFalse(RemovalBackground.sample(texture, ByteArray(4096), 64, box).flat)
        assertFalse(RemovalBackground.sample(IntArray(4096) { -1 }, ByteArray(4096) { 1 }, 64, box).flat)
    }
}
