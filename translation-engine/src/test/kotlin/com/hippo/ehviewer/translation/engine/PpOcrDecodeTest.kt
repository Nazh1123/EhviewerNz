package com.hippo.ehviewer.translation.engine

import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test

class PpOcrDecodeTest {
    @Test fun blanksSeparateRepeatedCharactersAndDictionaryTokensMayContainMultipleCodepoints() {
        val alphabet = listOf("", "A", "한", "你好", " ")
        val ids = listOf(0, 1, 1, 0, 1, 4, 2, 2, 3, 0)
        val data = FloatArray(ids.size * alphabet.size) { .01f }
        ids.forEachIndexed { t, id -> data[t * alphabet.size + id] = .95f }
        val buffer = FloatBuffer.wrap(floatArrayOf(-1f) + data).apply { position(1) }
        val (text, confidence) = PpOcr.decode(buffer, ids.size, alphabet)
        assertEquals("AA 한你好", text)
        assertEquals(.95f, confidence, .001f)
        assertEquals(1, buffer.position())
    }
    @Test fun blankOutputHasZeroConfidence() {
        assertEquals("" to 0f, PpOcr.decode(FloatBuffer.wrap(floatArrayOf(1f, 0f)), 1, listOf("", "a")))
    }
}
