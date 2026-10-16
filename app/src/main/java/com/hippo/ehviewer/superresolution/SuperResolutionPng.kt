package com.hippo.ehviewer.superresolution

import android.graphics.BitmapFactory
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
            val bandHeight = minOf(page.outputEdge, (BAND_BYTES / 4 / page.width).coerceAtLeast(1))
            val pixels = IntArray(page.width * bandHeight)
            val scanline = ByteArray(1 + page.width * 4)
            for (tileRow in 0 until page.rows) {
                val top = tileRow * page.outputEdge
                val bottom = minOf(page.height, top + page.outputEdge)
                var y = top
                while (y < bottom) {
                    currentCoroutineContext().ensureActive()
                    val height = minOf(bandHeight, bottom - y)
                    for (column in 0 until page.columns) {
                        currentCoroutineContext().ensureActive()
                        val index = tileRow * page.columns + column
                        val core = page.core(index)
                        val region = page.region(index)
                        val bitmap = checkNotNull(BitmapFactory.decodeFile(page.file(index).path)) { "Missing SR tile" }
                        try {
                            check(bitmap.width == region.width() && bitmap.height == region.height()) { "Invalid SR tile" }
                            // Discard overlapping gutters; each output pixel is written exactly once.
                            bitmap.getPixels(pixels, core.left, page.width, core.left - region.left,
                                y - region.top, core.width(), height)
                        } finally { bitmap.recycle() }
                    }
                    for (row in 0 until height) {
                        currentCoroutineContext().ensureActive()
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
                    y += height
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

    private const val BAND_BYTES = 8 * 1024 * 1024
}
