package com.hippo.ehviewer.translation.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SuperResolutionRuntimeTest {
    private open class Model(private val operation: (FloatArray) -> Unit) : SuperResolutionTileModel {
        var closed = false
        override fun upscale(input: FloatArray, width: Int, height: Int, output: FloatArray) {
            check(!closed)
            operation(output)
        }
        override fun close() { closed = true }
    }
    private class BitmapModel : Model({ error("Bitmap model must not use the legacy float path") }), SuperResolutionBitmapModel {
        override fun render(source: android.graphics.Bitmap, destination: android.graphics.Bitmap,
                            progress: SuperResolutionProgress): Unit = error("Use the render admission lambda")
    }
    private suspend fun infer(runtime: SuperResolutionRuntime) = FloatArray(12).also {
        runtime.upscale(FloatArray(768), 16, 16, it)
    }

    @Test fun cpuModeNeverInitializesVulkan() = runBlocking<Unit> {
        val cpu = Model { it.fill(0.25f) }
        SuperResolutionRuntime(SuperResolutionBackend.CPU, { error("GPU must not be queried") }) { gpu ->
            assertFalse(gpu); cpu
        }.use { runtime ->
            assertArrayEquals(FloatArray(12) { 0.25f }, infer(runtime), 0f)
            assertFalse(runtime.usingVulkan)
            assertFalse(runtime.fellBack)
        }
        assertTrue(cpu.closed)
    }

    @Test fun unavailableOrFailedGpuInitializationUsesCpuInAutoMode() = runBlocking<Unit> {
        for (available in listOf(false, true)) {
            var gpuAttempts = 0
            val cpu = Model { it.fill(0.5f) }
            SuperResolutionRuntime(SuperResolutionBackend.AUTO, { if (available) "GPU" else null }) { gpu ->
                if (gpu) { gpuAttempts++; error("Driver failed to load model") }
                cpu
            }.use { runtime ->
                infer(runtime)
                assertTrue(runtime.fellBack)
                assertFalse(runtime.usingVulkan)
                assertEquals(if (available) 1 else 0, gpuAttempts)
            }
            assertTrue(cpu.closed)
        }
    }

    @Test fun failedGpuTileClosesGpuBeforeCpuLoadAndRetriesOnlyOnce() = runBlocking<Unit> {
        val gpu = Model { error("vkSubmit failed") }
        val cpu = Model { it.fill(0.75f) }
        var cpuLoads = 0
        SuperResolutionRuntime(SuperResolutionBackend.AUTO, { "Adreno" }) { requestedGpu ->
            if (requestedGpu) gpu else {
                assertTrue("Release GPU memory before CPU allocations", gpu.closed)
                cpuLoads++; cpu
            }
        }.use { runtime ->
            assertTrue(runtime.usingVulkan)
            assertArrayEquals(FloatArray(12) { 0.75f }, infer(runtime), 0f)
            assertTrue(runtime.fellBack)
            assertFalse(runtime.usingVulkan)
            infer(runtime)
            assertEquals(1, cpuLoads)
        }
        assertTrue(cpu.closed)
    }

    @Test fun nonFiniteGpuResultsFallBackAndCpuFailuresAreNotHidden() = runBlocking<Unit> {
        val gpu = Model { it.fill(Float.NaN) }
        val cpu = Model { error("CPU model failed") }
        SuperResolutionRuntime(SuperResolutionBackend.AUTO, { "GPU" }) { if (it) gpu else cpu }.use { runtime ->
            val error = runCatching { infer(runtime) }.exceptionOrNull()
            assertEquals("CPU model failed", error?.message)
            assertTrue(gpu.closed)
            assertTrue(runtime.fellBack)
        }
        assertTrue(cpu.closed)
    }

    @Test fun explicitVulkanModeReportsGpuFailureWithoutPretendingToUseGpu() = runBlocking<Unit> {
        var cpuLoads = 0
        val gpu = Model { error("GPU tile failed") }
        SuperResolutionRuntime(SuperResolutionBackend.VULKAN, { "GPU" }) {
            if (!it) cpuLoads++
            gpu
        }.use { runtime ->
            assertEquals("GPU tile failed", runCatching { infer(runtime) }.exceptionOrNull()?.message)
            assertEquals(0, cpuLoads)
        }
    }

    @Test fun canceledPageNeverLoadsCpuFallback() = runBlocking<Unit> {
        var cancelRequest: () -> Unit = {}
        var cpuLoads = 0
        val gpu = Model { cancelRequest(); error("GPU failed after cancellation") }
        SuperResolutionRuntime(SuperResolutionBackend.AUTO, { "GPU" }) {
            if (!it) cpuLoads++
            gpu
        }.use { runtime ->
            val request = launch {
                val context = currentCoroutineContext()
                cancelRequest = { context.cancel() }
                infer(runtime)
            }
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(0, cpuLoads)
        }
        assertTrue(gpu.closed)
    }

    @Test fun failedBitmapPipelineReleasesGpuBeforeCpuAndSignalsFullPageRetry() = runBlocking<Unit> {
        val gpu = BitmapModel()
        val cpu = Model { it.fill(0.5f) }
        SuperResolutionRuntime(SuperResolutionBackend.AUTO, { "GPU" }) {
            if (it) gpu else { assertTrue(gpu.closed); cpu }
        }.use { runtime ->
            assertFalse(runtime.render { error("GPU download failed after a partial strip") })
            assertTrue(runtime.fellBack); assertFalse(runtime.usingVulkan)
            assertArrayEquals(FloatArray(12) { 0.5f }, infer(runtime), 0f)
            assertFalse(runtime.render { error("CPU model cannot enter the GPU bitmap path") })
        }
        assertTrue(cpu.closed)
    }

    @Test fun strictBitmapModeReportsFailuresAndSuccessfulPagesReuseTheSameModel() = runBlocking<Unit> {
        val gpu = BitmapModel()
        var cpuLoads = 0
        SuperResolutionRuntime(SuperResolutionBackend.VULKAN, { "GPU" }) {
            if (!it) cpuLoads++
            gpu
        }.use { runtime ->
            repeat(2) { assertTrue(runtime.render { assertSame(gpu, it) }) }
            assertEquals("GPU failed", runCatching { runtime.render { error("GPU failed") } }.exceptionOrNull()?.message)
            assertEquals(0, cpuLoads)
        }
        assertTrue(gpu.closed)
    }

    @Test fun canceledBitmapPipelineNeverAllocatesCpuFallback() = runBlocking<Unit> {
        val gpu = BitmapModel()
        var cpuLoads = 0
        SuperResolutionRuntime(SuperResolutionBackend.AUTO, { "GPU" }) {
            if (!it) cpuLoads++
            gpu
        }.use { runtime ->
            val task = launch {
                val request = currentCoroutineContext()
                runtime.render { request.cancel(); error("Native render canceled") }
            }
            task.join()
            assertTrue(task.isCancelled); assertEquals(0, cpuLoads)
        }
        assertTrue(gpu.closed)
    }

    @Test fun explicitCancellationExceptionsNeverBecomeGpuFailures() = runBlocking<Unit> {
        val gpu = BitmapModel()
        var cpuLoads = 0
        SuperResolutionRuntime(SuperResolutionBackend.AUTO, { "GPU" }) {
            if (!it) cpuLoads++
            gpu
        }.use { runtime ->
            val error = runCatching { runtime.render { throw CancellationException("Callback canceled") } }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(0, cpuLoads); assertTrue(runtime.usingVulkan); assertFalse(runtime.fellBack)
        }
    }
}
