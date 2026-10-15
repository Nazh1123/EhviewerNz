package com.hippo.ehviewer.superresolution

import com.hippo.ehviewer.translation.engine.SuperResolution
import org.junit.Assert.*
import org.junit.Test

class SuperResolutionMemoryBudgetTest {
    private val mib = SuperResolutionMemoryBudget.MIB

    @Test fun ordinaryMangaPagesFitOn256And512MiBHeaps() {
        val medium = SuperResolutionMemoryBudget.calculate(256 * mib, 48 * mib, 2048 * mib, false)
        val large = SuperResolutionMemoryBudget.calculate(512 * mib, 80 * mib, 4096 * mib, false)
        assertEquals(85 * mib, medium)
        assertEquals(170 * mib, large)
        for ((w, h) in listOf(1280 to 1920, 1600 to 2400, 1920 to 2560))
            assertTrue("$w x $h should fit a 256 MiB heap", SuperResolution.accepts(w, h, medium))
        assertTrue(SuperResolution.accepts(2048, 3072, large))
        assertTrue(SuperResolution.accepts(2560, 4096, large))
        assertTrue(SuperResolution.accepts(1024, 10000, large))
    }

    @Test fun pressureShrinksAdmissionWithoutAssumingUnlimitedSystemRam() {
        val pressured = SuperResolutionMemoryBudget.calculate(512 * mib, 440 * mib, 2048 * mib, false)
        assertEquals(5 * mib, pressured)
        assertFalse(SuperResolution.accepts(1280, 1920, pressured))
        assertEquals(0L, SuperResolutionMemoryBudget.calculate(512 * mib, 460 * mib, 2048 * mib, false))
        assertEquals(0L, SuperResolutionMemoryBudget.calculate(512 * mib, 40 * mib, 4096 * mib, true))
        assertEquals(0L, SuperResolutionMemoryBudget.calculate(512 * mib, 40 * mib, 64 * mib, false))
        assertEquals(21 * mib, SuperResolutionMemoryBudget.calculate(512 * mib, 40 * mib, 128 * mib, false))
    }

    @Test fun hardCeilingAndDimensionChecksPreventOverflow() {
        assertEquals(256 * mib, SuperResolutionMemoryBudget.calculate(2048 * mib, 40 * mib, 8192 * mib, false))
        assertFalse(SuperResolution.accepts(8192, 8192, Long.MAX_VALUE))
        assertFalse(SuperResolution.accepts(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(SuperResolution.accepts(1280, 1920, -1))
        assertFalse(SuperResolution.accepts(1, 1, 0))
    }
}
