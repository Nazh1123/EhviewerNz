package com.hippo.ehviewer.translation.engine

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class BubbleBoundariesTest {
    @Test fun closedWhiteAndDarkBubblesYieldOnlyInteriorRectanglesAndOpenContoursFallback() {
        for (background in listOf(-1, 0xff151515.toInt())) {
            val width = 80; val height = 80
            val pixels = IntArray(width * height) { 0xff686868.toInt() }
            for (y in 0 until height) for (x in 0 until width)
                if ((x - 40) * (x - 40) / 900f + (y - 40) * (y - 40) / 625f < 1) pixels[y * width + x] = background
            val mask = IntArray(pixels.size) { 0xff000000.toInt() }
            val focus = BubbleBox(25f, 33f, 55f, 47f)
            for (y in 37..42) for (x in 30..48) { pixels[y * width + x] = 0xffcc4477.toInt(); mask[y * width + x] = -1 }
            val result = BubbleBoundaries.find(pixels, mask, width, height, focus, listOf(focus))!!
            val box = result.layout
            assertTrue(box.width > focus.width)
            for (y in box.top.toInt()..box.bottom.toInt()) for (x in box.left.toInt()..box.right.toInt())
                assertTrue("Rectangle leaked outside bubble", result.cells[y * width + x])
            for (y in 0..40) for (x in 38..42) pixels[y * width + x] = background
            assertNull(BubbleBoundaries.find(pixels, mask, width, height, focus, listOf(focus)))
        }
    }
    @Test fun largestInscribedRectangleMatchesBruteForceIncludingConcavities() {
        val width = 8; val height = 6; val random = Random(642)
        repeat(50) {
            val cells = BooleanArray(width * height) { random.nextInt(5) > 0 }
            val anchorX = 4; val anchorY = 3
            var area = 0
            for (top in 0..anchorY) for (bottom in anchorY + 1..height)
                for (left in 0..anchorX) for (right in anchorX + 1..width) {
                    if ((top until bottom).all { y -> (left until right).all { x -> cells[y * width + x] } })
                        area = maxOf(area, (bottom - top) * (right - left))
                }
            val result = BubbleBoundaries.inscribed(cells, width, height, anchorX, anchorY)
            assertEquals(area.toFloat(), result?.let { it.width * it.height } ?: 0f, .001f)
        }
    }
    @Test fun distinctBoundariesPreventMergingAndSameBoundaryJoinsDistantDialogue() {
        fun boundary() = BubbleBoundary(BubbleBox(0f, 0f, 100f, 100f), BooleanArray(10000) { true }, 100, 100, 0f, 0f, 1f, 1f)
        fun line(y: Float, text: String, bubble: BubbleBoundary?) = TextLine(
            listOf(Pt(10f, y), Pt(90f, y), Pt(90f, y + 20), Pt(10f, y + 20)), 1f).apply { this.text = text; this.bubble = bubble }
        val first = boundary(); val second = boundary()
        assertEquals(2, Grouping.group(listOf(line(10f, "one", first), line(31f, "two", second)), " ").size)
        assertEquals("one two", Grouping.group(listOf(line(60f, "two", first), line(10f, "one", first)), " ").single().sourceText)
        assertEquals(2, Grouping.group(listOf(line(10f, "one", first), line(31f, "outside", null)), " ").size)
    }
    @Test fun wordsOnOneBaselineKeepLeftToRightOrderDespiteUnequalBoxHeights() {
        val boundary = BubbleBoundary(BubbleBox(0f, 0f, 200f, 100f), BooleanArray(20000) { true }, 200, 100, 0f, 0f, 1f, 1f)
        fun word(x: Float, y: Float, text: String) = TextLine(listOf(Pt(x, y), Pt(x + 45, y),
            Pt(x + 45, y + 20), Pt(x, y + 20)), 1f).apply { this.text = text; bubble = boundary }
        assertEquals("HELLO WORLD next row", Grouping.group(listOf(word(70f, 12f, "WORLD"), word(10f, 14f, "HELLO"),
            word(70f, 46f, "row"), word(10f, 46f, "next")), " ").single().sourceText)
    }
}
