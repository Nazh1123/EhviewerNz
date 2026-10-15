package com.hippo.ehviewer.superresolution

import org.junit.Assert.*
import org.junit.Test

class SuperResolutionSessionCacheTest {
    private class Model : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun successivePagesReuseTheModelUntilTheBackendChanges() {
        val cache = SuperResolutionSessionCache<Model>()
        val gpu = cache.acquire("gpu32") { Model() }
        assertSame(gpu, cache.acquire("gpu32") { error("Must reuse shaders and weights") })
        val cpu = cache.acquire("cpu") { assertEquals(1, gpu.closes); Model() }
        assertNotSame(gpu, cpu)
        cache.close(); cache.close()
        assertEquals(1, gpu.closes); assertEquals(1, cpu.closes)
    }

    @Test fun retiredSessionCannotRecreateAClosedModelOrCloseANewerSession() {
        val retired = SuperResolutionSessionCache<Model>()
        val old = retired.acquire("gpu16") { Model() }
        val current = SuperResolutionSessionCache<Model>()
        val new = current.acquire("gpu16") { Model() }
        retired.close()
        assertEquals(1, old.closes); assertEquals(0, new.closes)
        assertTrue(runCatching { retired.acquire("gpu16") { error("Must not load") } }.exceptionOrNull() is IllegalStateException)
        assertSame(new, current.acquire("gpu16") { error("Must reuse") })
        current.close()
    }

    @Test fun failedLoadLeavesNoStaleEntryAndCanBeRetried() {
        val cache = SuperResolutionSessionCache<Model>()
        val old = cache.acquire("gpu32") { Model() }
        assertEquals("load failed", runCatching { cache.acquire("gpu16") { error("load failed") } }.exceptionOrNull()?.message)
        assertEquals(1, old.closes)
        val retry = cache.acquire("gpu16") { Model() }
        cache.clear(); assertEquals(1, retry.closes)
        cache.close(); assertEquals(1, retry.closes)
    }
}
