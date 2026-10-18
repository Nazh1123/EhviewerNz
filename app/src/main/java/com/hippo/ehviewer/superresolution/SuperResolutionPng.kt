package com.hippo.ehviewer.superresolution

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Streams tile cores into one PNG, with an 8 MiB pixel band and one decoded tile. */
internal object SuperResolutionPng {
    suspend fun write(page: EnhancedTilePage, output: OutputStream) {
        val png = DataOutputStream(output)
        png.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = java.io.ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(page.width); it.writeInt(page.height)
                it.write(byteArrayOf(8, 6, 0, 0, 0)) // 8-bit RGBA, non-interlaced.
            }
        }.toByteArray()
        chunk(png, "IHDR", header, header.size)
        val chunks = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                chunk(png, "IDAT", bytes, length, offset)
            }
        }
        val deflater = Deflater()
        try {
            val compressed = DeflaterOutputStream(chunks, deflater, 64 * 1024)
            val scanline = ByteArray(1 + page.width * 4)
            SuperResolutionBands.read(page) { pixels, height ->
                for (row in 0 until height) {
                    // PNG Sub filter improves compression without keeping a previous scanline.
                    var previous = 0
                    for (x in 0 until page.width) {
                        val color = pixels[row * page.width + x]
                        val offset = 1 + x * 4
                        scanline[offset] = ((color shr 16 and 255) - (previous shr 16 and 255)).toByte()
                        scanline[offset + 1] = ((color shr 8 and 255) - (previous shr 8 and 255)).toByte()
                        scanline[offset + 2] = ((color and 255) - (previous and 255)).toByte()
                        scanline[offset + 3] = ((color ushr 24) - (previous ushr 24)).toByte()
                        previous = color
                    }
                    scanline[0] = 1
                    compressed.write(scanline)
                }
            }
            compressed.finish()
        } finally { deflater.end() }
        chunk(png, "IEND", byteArrayOf(), 0)
        png.flush()
    }

    private fun chunk(output: DataOutputStream, type: String, bytes: ByteArray, length: Int, offset: Int = 0) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(length); output.write(name); output.write(bytes, offset, length)
        val crc = CRC32().apply { update(name); update(bytes, offset, length) }
        output.writeInt(crc.value.toInt())
    }
}
