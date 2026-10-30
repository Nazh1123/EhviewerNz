package com.hippo.ehviewer.gallery

import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Readers/writers of unrelated manifests proceed independently. Bulk cleanup owns the outer gate. */
internal object ResultIndexLocks {
    private val gate = ReentrantReadWriteLock(true)
    private val registry = mutableMapOf<String, WeakReference<State>>()
    class State {
        val lock = ReentrantLock()
        val deleted = LongArray(2)
    }
    fun states(keys: List<String>): List<State> = synchronized(registry) {
        registry.entries.removeAll { it.value.get() == null }
        keys.distinct().sorted().map { key ->
            registry[key]?.get() ?: State().also { registry[key] = WeakReference(it) }
        }
    }
    fun <T> withStates(states: List<State>, block: () -> T): T = gate.read {
        fun acquire(index: Int): T = if (index == states.size) block()
            else states[index].lock.withLock { acquire(index + 1) }
        acquire(0)
    }
    fun <T> exclusive(block: () -> T): T = gate.write(block)
}
