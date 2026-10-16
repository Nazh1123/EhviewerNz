package com.hippo.ehviewer.translation

import java.io.File
import java.security.MessageDigest

class TranslationCache(private val dir: File, private val limit: Long = 256L * 1024 * 1024,
                       private val filesRemoved: (List<File>) -> Unit = {}) {
    init { check(dir.isDirectory || dir.mkdirs()) }
    // Invalidate renders from bubble grouping while retaining reuse across translation settings.
    @Suppress("UNUSED_PARAMETER")
    fun key(source: File, options: TranslationOptions): String = key(source, "ehnz-overlay-content-v2-legacy-grouping-tiles")
    fun preparationKey(source: File, options: TranslationOptions): String = key(source, options.preparationIdentity())
    internal fun key(source: File, identity: String): String = keys(source, listOf(identity)).single()
    internal fun keys(source: File, identities: List<String>): List<String> {
        val digests = identities.map { identity ->
            MessageDigest.getInstance("SHA-256").apply {
                update(identity.toByteArray(Charsets.UTF_8))
                update(0.toByte())
            }
        }
        source.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digests.forEach { it.update(buffer, 0, n) } }
        }
        return digests.map { digest -> digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } }
    }
    fun image(key: String) = File(dir, "${key}_tl.png")
    fun skipped(key: String) = File(dir, "${key}_tl.skip")
    fun touch(file: File) { file.setLastModified(System.currentTimeMillis()) }
    fun invalidate(key: String) {
        remove(extensions.map { File(dir, "${key}_tl.$it") })
    }
    private fun remove(files: List<File>) {
        val removed = files.filter { it.delete() }
        if (removed.isNotEmpty()) filesRemoved(removed)
    }
    fun prune() {
        val files = dir.listFiles()?.filter { it.extension in extensions } ?: return
        var size = files.sumOf { it.length() }
        // Keep a partial preview and its text checkpoint in the same eviction unit.
        val groups = files.groupBy { it.nameWithoutExtension }.values.sortedBy { group -> group.maxOf { it.lastModified() } }
        val removed = mutableListOf<File>()
        for (group in groups) {
            if (size <= limit) break
            for (file in group) {
                val bytes = file.length()
                if (file.delete()) { size -= bytes; removed += file }
            }
        }
        if (removed.isNotEmpty()) filesRemoved(removed)
    }
    fun clear() { remove(dir.listFiles().orEmpty().filter { it.extension in extensions || it.extension == "part" }) }
    private companion object { val extensions = setOf("png", "skip", "partial", "regions") }
}
