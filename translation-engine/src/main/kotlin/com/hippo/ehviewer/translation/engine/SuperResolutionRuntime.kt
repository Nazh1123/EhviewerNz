package com.hippo.ehviewer.translation.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

enum class SuperResolutionBackend { AUTO, CPU, VULKAN }

internal interface SuperResolutionTileModel : AutoCloseable {
    fun upscale(input: FloatArray, width: Int, height: Int, output: FloatArray)
}

/** Only super-resolution opts into Vulkan; existing translation nets stay on the CPU. */
internal class SuperResolutionRuntime(
    private val requested: SuperResolutionBackend,
    gpuDevice: () -> String?,
    private val createModel: (Boolean) -> SuperResolutionTileModel,
) : AutoCloseable {
    var usingVulkan = false
        private set
    var fellBack = false
        private set
    var deviceName: String? = null
        private set
    private var model: SuperResolutionTileModel

    init {
        model = if (requested == SuperResolutionBackend.CPU) createModel(false) else {
            try {
                deviceName = checkNotNull(gpuDevice()) { "Vulkan device unavailable" }
                createModel(true).also { usingVulkan = true }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requested == SuperResolutionBackend.VULKAN) throw error
                fellBack = true
                createModel(false)
            } catch (error: LinkageError) {
                if (requested == SuperResolutionBackend.VULKAN) throw error
                fellBack = true
                createModel(false)
            }
        }
    }

    suspend fun upscale(input: FloatArray, width: Int, height: Int, output: FloatArray) {
        currentCoroutineContext().ensureActive()
        try {
            model.upscale(input, width, height, output)
            check(output.all { it.isFinite() }) { "Invalid super-resolution output" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            if (!usingVulkan || requested != SuperResolutionBackend.AUTO) throw error
            currentCoroutineContext().ensureActive()
            // Waits for the native read lease/GPU completion before allocating the CPU model.
            model.close()
            usingVulkan = false
            fellBack = true
            model = createModel(false)
            model.upscale(input, width, height, output)
            check(output.all { it.isFinite() }) { "Invalid super-resolution output" }
        }
    }

    /** Returns false for the legacy path or after releasing a failed GPU pipeline. */
    suspend fun render(action: (SuperResolutionBitmapModel) -> Unit): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bitmapModel = model as? SuperResolutionBitmapModel ?: return false
        try {
            action(bitmapModel)
            context.ensureActive()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            context.ensureActive()
            if (!usingVulkan || requested != SuperResolutionBackend.AUTO) throw error
            model.close()
            usingVulkan = false
            fellBack = true
            model = createModel(false)
            return false // CPU overwrites the entire partially rendered destination.
        }
    }

    override fun close() = model.close()
}
