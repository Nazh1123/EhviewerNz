package com.hippo.ehviewer.translation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import li.joye.yakuyomi.engine.NativePrefixCache

object TranslationRuntime {
    // The scheduler orders gallery workers only. This also excludes model imports,
    // settings-page inference and cache mutations while a worker owns resources.
    val lock = Mutex()
    // Outlives a reader scope so disabling/closing can finish native disposal safely.
    internal val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val preparedPages = PreparedPageCache()
    private var prefixCache: NativePrefixCache? = null
    @Volatile internal var memoryEpoch = 0
        private set

    // Fixed-prefix snapshots also survive expiry of the foreground model lease.
    @Synchronized internal fun nativePrefixCache(): NativePrefixCache =
        prefixCache ?: NativePrefixCache().also { prefixCache = it }

    /** Caller owns lock. Complete disposal before another model can be loaded. */
    internal suspend fun closeIdleModels(except: GalleryTranslationSession? = null) = withContext(NonCancellable) {
        val models = withContext(Dispatchers.Main.immediate) { TranslationTasks.takeIdleModels(except) }
        withContext(Dispatchers.IO) { models.forEach { it.close() } }
    }

    suspend fun <T> withModelMaintenance(block: suspend () -> T): T = lock.withLock {
        closeIdleModels()
        currentCoroutineContext().ensureActive()
        trimMemory()
        block()
    }

    @JvmStatic fun trimMemory() {
        synchronized(this) { memoryEpoch++ }
        preparedPages.clear()
        synchronized(this) { prefixCache?.clear() }
        modelScope.launch(Dispatchers.Main) { TranslationTasks.releaseIdleModels() }
    }
}
