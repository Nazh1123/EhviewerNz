package com.hippo.ehviewer.translation

import java.io.File
import java.security.MessageDigest

class TranslationCache(private val dir: File, private val limit: Long = 256L * 1024 * 1024) {
    init { check(dir.isDirectory || dir.mkdirs()) }
    fun key(source: File, options: TranslationOptions): String = key(source, options.cacheIdentity())
    fun preparationKey(source: File, options: TranslationOptions): String = key(source, options.preparationIdentity())
    private fun key(source: File, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        source.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun image(key: String) = File(dir, "$key.png")
    fun skipped(key: String) = File(dir, "$key.skip")
    fun touch(file: File) { file.setLastModified(System.currentTimeMillis()) }
    fun invalidate(key: String) { extensions.forEach { File(dir, "$key.$it").delete() } }
    fun prune() {
        val files = dir.listFiles()?.filter { it.extension in extensions } ?: return
        var size = files.sumOf { it.length() }
        // Keep a partial preview and its text checkpoint in the same eviction unit.
        val groups = files.groupBy { it.nameWithoutExtension }.values.sortedBy { group -> group.maxOf { it.lastModified() } }
        for (group in groups) {
            if (size <= limit) break
            for (file in group) {
                val bytes = file.length()
                if (file.delete()) size -= bytes
            }
        }
    }
    fun clear() { dir.listFiles()?.filter { it.extension in extensions || it.extension == "part" }?.forEach { it.delete() } }
    private companion object { val extensions = setOf("png", "skip", "partial", "regions") }
}
