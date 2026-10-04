package com.hippo.ehviewer.translation.engine

import java.io.File
import java.security.MessageDigest

data class ModelSet(val ocr: String, val detectorNcnn: String, val aotInpainterNcnn: String) {
    companion object {
        fun resolve(files: List<Pair<String, String>>): ModelSet? {
            val parameters = files.filter { it.first.endsWith(".param", true) }
            fun path(key: String) = parameters.firstOrNull { it.first.contains(key, true) }?.second
            val ocr = parameters.sortedBy { it.first.contains("mixed", true) }
                .firstOrNull { it.first.contains("ocr", true) }?.second ?: return null
            return ModelSet(ocr, path("dbnet") ?: return null, path("aot") ?: return null)
        }
    }
}

object ModelChecksum {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(65536).use { stream ->
            val block = ByteArray(65536)
            var length = stream.read(block)
            while (length >= 0) {
                digest.update(block, 0, length)
                length = stream.read(block)
            }
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in digest.digest()) {
                append(hex[(byte.toInt() ushr 4) and 15]); append(hex[byte.toInt() and 15])
            }
        }
    }
}
