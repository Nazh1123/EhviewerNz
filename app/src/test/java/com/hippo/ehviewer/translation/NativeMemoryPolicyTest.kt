package com.hippo.ehviewer.translation

import org.junit.Assert.*
import org.junit.Test

class NativeMemoryPolicyTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun smallerPhonesReleaseModelsBetweenStagesEvenInForeground() {
        assertFalse(NativeMemoryPolicy.canRetain(false, false, 4 * gib, 2 * gib, gib / 8))
        assertFalse(NativeMemoryPolicy.canRetain(true, false, 8 * gib, 4 * gib, gib / 8))
        assertTrue(NativeMemoryPolicy.canRetain(false, false, 6 * gib, 2 * gib, gib / 8))
    }

    @Test fun largePhonesAlsoStopRetentionWhenSystemMemoryIsUnderPressure() {
        assertFalse(NativeMemoryPolicy.canRetain(false, true, 12 * gib, 4 * gib, gib / 8))
        assertFalse(NativeMemoryPolicy.canRetain(false, false, 12 * gib, gib / 4, gib / 8))
        assertFalse(NativeMemoryPolicy.canRetain(false, false, 12 * gib, gib, gib * 3 / 4))
        assertFalse(NativeMemoryPolicy.canRetain(false, false, 0, 0, 0))
        assertTrue(NativeMemoryPolicy.canRetain(false, false, 12 * gib, 2 * gib, gib / 2))
    }
}
