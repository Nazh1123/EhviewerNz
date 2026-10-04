package com.hippo.ehviewer.translation.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*
import kotlin.random.Random

class ImageAlgorithmsTest {
    @Test fun dilationMatchesNeighborhoodOracleForEdgesAndLargeRadii() {
        val random = Random(931)
        for (width in listOf(1, 2, 7, 23)) for (height in listOf(1, 3, 19)) {
            for (radius in listOf(0, 1, 4, 40)) repeat(4) {
                val input = ByteArray(width * height) { if (random.nextInt(8) == 0) 1 else 0 }
                val snapshot = input.copyOf()
                val expected = ByteArray(input.size) { index ->
                    val x = index % width; val y = index / width
                    var hit = false
                    for (yy in max(0, y - radius)..min(height - 1, y + radius))
                        for (xx in max(0, x - radius)..min(width - 1, x + radius))
                            if (input[yy * width + xx].toInt() != 0) hit = true
                    if (hit) 1 else 0
                }
                assertArrayEquals(expected, RemovalMask.dilate(input, width, height, radius))
                assertArrayEquals(snapshot, input)
            }
        }
    }

    @Test fun boundsRecoverRotatedRectangleAndIgnoreInteriorPoints() {
        for (angle in listOf(-0.6f, 0f, 0.4f, 1.2f)) {
            val original = OrientedBox(70f, 50f, 80f, 20f, angle)
            val recovered = Geometry.bounds(original.corners() + listOf(Pt(70f, 50f)))!!
            assertEquals(70f, recovered.cx, 0.001f)
            assertEquals(50f, recovered.cy, 0.001f)
            assertEquals(1600f, recovered.width * recovered.height, 0.01f)
            val canonical = Geometry.canonical(recovered)
            assertTrue(canonical.radians in -PI.toFloat() / 4..PI.toFloat() / 4)
        }
    }

    private fun line(x: Float, y: Float, width: Float, height: Float, text: String, vertical: Boolean = false) =
        TextLine(OrientedBox(x + width / 2, y + height / 2, width, height, 0f).corners(), 1f).apply {
            this.text = text; direction = if (vertical) "v" else "h"
        }

    @Test fun nearbyLinesJoinInReadingOrderButSeparateBubblesStayIndependent() {
        val lines = listOf(line(10f, 55f, 80f, 30f, "second bubble"),
            line(10f, 8f, 80f, 30f, "first bubble"))
        assertEquals(2, Grouping.group(lines).size)
        val horizontal = Grouping.group(listOf(line(10f, 32f, 100f, 20f, "world"),
            line(10f, 10f, 100f, 20f, "Hello")), " ").single()
        assertEquals("Hello world", horizontal.sourceText)
        val vertical = Grouping.group(listOf(line(10f, 10f, 20f, 100f, "左", true),
            line(32f, 10f, 20f, 100f, "右", true))).single()
        assertEquals("右左", vertical.sourceText)
        assertEquals("v", vertical.direction)
        assertTrue(Grouping.group(listOf(TextLine(emptyList(), 1f))).isEmpty())
    }

    @Test fun ctcCollapseKeepsBlankSeparatedDuplicatesSpacesAndConfidence() {
        val ids = intArrayOf(1, 1, 0, 1, 2, 0, 3, 3)
        val probabilities = FloatArray(ids.size) { ln(0.8f) }
        val (text, confidence) = Ocr.decode(ids, probabilities, listOf("<PAD>", "哈", "<SP>", "！"))
        assertEquals("哈哈 ！", text)
        assertEquals(0.8f, confidence, 0.00001f)
        assertEquals("", Ocr.decode(IntArray(4), FloatArray(4), listOf("<PAD>")).first)
    }

    @Test fun detectorSeparatesComponentsRejectsNoiseAndKeepsClampedCoordinates() {
        val width = 64; val height = 40
        val logits = FloatArray(width * height) { -10f }
        for (y in 5..12) for (x in 3..25) logits[y * width + x] = 8f
        for (y in 22..33) for (x in 40..47) logits[y * width + x] = 8f
        logits[1] = 10f
        val lines = Detector.components(logits, width, height, 0.5f, 128, 80)
        assertEquals(2, lines.size)
        assertEquals(setOf("h", "v"), lines.map { it.direction }.toSet())
        assertTrue(lines.all { it.score > 0.99f && it.quad.all { p -> p.x in 0f..127f && p.y in 0f..79f } })
        val weak = FloatArray(16 * 16) { 0.2f }
        assertTrue(Detector.components(weak, 16, 16, 1f, 16, 16).isEmpty())
    }

    @Test fun modelBundleResolutionIgnoresUnrelatedRolesAndPrefersPortableOcrGraph() {
        val files = listOf("cartoonseg.ncnn.param", "ocr_48px_ctc_mixed.ncnn.param",
            "dbnet_detect.ncnn.param", "ocr_48px_ctc.ncnn.param", "mit_aot_fixed512.ncnn.param")
            .map { it to "/models/$it" }
        assertEquals("/models/ocr_48px_ctc.ncnn.param", ModelSet.resolve(files)!!.ocr)
        assertNull(ModelSet.resolve(files.filterNot { it.first.contains("dbnet") }))
    }

    @Test fun checksumMatchesKnownSha256WithoutLoadingInferenceLibraries() {
        val file = File.createTempFile("model-checksum", ".bin")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelChecksum.sha256(file))
        } finally { file.delete() }
    }
}
