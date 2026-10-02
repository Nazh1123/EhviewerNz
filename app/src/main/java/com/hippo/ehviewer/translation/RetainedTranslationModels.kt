package com.hippo.ehviewer.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Idle models do not own a scheduler turn or the inference lock. Native disposal
 * takes that lock so it cannot race a page that has resumed using the models. */
internal class RetainedTranslationModels<T : AutoCloseable>(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val idleRemaining: suspend () -> Long,
    private val canRetain: () -> Boolean,
    private val idleMillis: Long = 15_000,
) {
    @Volatile private var retained: T? = null
    private var expiry: Job? = null

    // Called by the worker while holding lock. The worker owns the returned value.
    fun take(): T? = retained.also { retained = null }

    fun park(models: T) {
        check(retained == null)
        retained = models
    }

    fun queued() {
        expiry?.cancel()
        expiry = null
    }

    fun idle() {
        queued()
        if (retained == null) return
        expiry = scope.launch {
            var remaining = idleMillis
            while (true) {
                delay(remaining)
                remaining = lock.withLock {
                    if (!canRetain()) {
                        take()?.close()
                        0L
                    } else idleRemaining().also { if (it <= 0) take()?.close() }
                }
                if (remaining <= 0) break
            }
        }
    }

    fun release() {
        queued()
        val expected = retained ?: return
        scope.launch {
            lock.withLock {
                if (retained === expected) take()?.close()
            }
        }
    }
}

/** Each image model is created once while retained, and can be reopened after
 * a background stage releases it. Each model has one stage owner; the page worker
 * joins all active stages before releasing the model set. */
internal class TranslationStageModel<T : AutoCloseable>(private val create: () -> T) : AutoCloseable {
    private var model: T? = null
    fun get(): T = model ?: create().also { model = it }
    override fun close() {
        val previous = model
        model = null
        previous?.close()
    }
}
