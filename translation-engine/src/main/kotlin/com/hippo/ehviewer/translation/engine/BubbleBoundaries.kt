package com.hippo.ehviewer.translation.engine

import android.graphics.*
import kotlin.math.*

data class BubbleBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A bounded connected interior, with a rectangle entirely inside it. */
class BubbleBoundary internal constructor(
    val layout: BubbleBox, private val cells: BooleanArray, private val width: Int, private val height: Int,
    private val originX: Float, private val originY: Float, private val scaleX: Float, private val scaleY: Float,
) {
    internal val byteCount get() = cells.size.toLong() + 128
    internal fun contains(x: Float, y: Float): Boolean {
        val gx = floor((x - originX) / scaleX).toInt(); val gy = floor((y - originY) / scaleY).toInt()
        return gx in 0 until width && gy in 0 until height && cells[gy * width + gx]
    }
    internal fun contains(box: BubbleBox): Boolean {
        // Detector boxes include padding: test the text edges and center, not expanded corners.
        for (fy in listOf(.15f, .5f, .85f)) for (fx in listOf(.15f, .5f, .85f)) {
            if (!contains(box.left + box.width * fx, box.top + box.height * fy)) return false
        }
        return true
    }
}

/** Model-free constraints: trust only closed interiors, leaving uncertain/open/artwork regions alone. */
object BubbleBoundaries {
    fun attach(page: Bitmap, textMask: Bitmap, lines: List<TextLine>, checkRelevant: () -> Unit = {}) {
        require(page.width == textMask.width && page.height == textMask.height)
        val boundaries = ArrayList<BubbleBoundary>()
        val boxes = lines.map { line -> BubbleBox(line.quad.minOf(Pt::x), line.quad.minOf(Pt::y),
            line.quad.maxOf(Pt::x), line.quad.maxOf(Pt::y)) }
        for ((index, line) in lines.withIndex()) {
            checkRelevant()
            line.bubble = null
            if (line.text.isBlank()) continue
            val focus = boxes[index]
            val cached = boundaries.firstOrNull { it.contains(focus) }
            if (cached != null) { line.bubble = cached; continue }
            val side = max(192f, max(focus.width, focus.height) * 4)
            val cx = (focus.left + focus.right) / 2; val cy = (focus.top + focus.bottom) / 2
            val area = Rect(max(0, floor(cx - side / 2).toInt()), max(0, floor(cy - side / 2).toInt()),
                min(page.width, ceil(cx + side / 2).toInt()), min(page.height, ceil(cy + side / 2).toInt()))
            if (area.width() < 16 || area.height() < 16) continue
            val scale = min(1f, 512f / max(area.width(), area.height()))
            val width = max(1, (area.width() * scale).roundToInt()); val height = max(1, (area.height() * scale).roundToInt())
            val grid = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height); val mask = IntArray(pixels.size)
            try {
                Canvas(grid).drawBitmap(page, area, Rect(0, 0, width, height), null)
                grid.getPixels(pixels, 0, width, 0, 0, width, height)
                Canvas(grid).drawBitmap(textMask, area, Rect(0, 0, width, height), null)
                grid.getPixels(mask, 0, width, 0, 0, width, height)
            } finally { grid.recycle() }
            val sx = area.width().toFloat() / width; val sy = area.height().toFloat() / height
            fun local(box: BubbleBox) = BubbleBox((box.left - area.left) / sx, (box.top - area.top) / sy,
                (box.right - area.left) / sx, (box.bottom - area.top) / sy)
            val found = find(pixels, mask, width, height, local(focus),
                boxes.indices.filter { lines[it].text.isNotBlank() }.map { local(boxes[it]) }) ?: continue
            if (found.cells.count { it }.toLong() * sx * sy > page.width.toLong() * page.height * .25) continue
            val rect = found.layout
            val boundary = BubbleBoundary(BubbleBox(area.left + rect.left * sx, area.top + rect.top * sy,
                area.left + rect.right * sx, area.top + rect.bottom * sy), found.cells, width, height,
                area.left.toFloat(), area.top.toFloat(), sx, sy)
            if (boundary.contains(focus)) { boundaries.add(boundary); line.bubble = boundary }
        }
    }

    internal data class Interior(val cells: BooleanArray, val layout: BubbleBox)
    internal fun find(pixels: IntArray, mask: IntArray, width: Int, height: Int, focus: BubbleBox,
                      textBoxes: List<BubbleBox>): Interior? {
        require(width > 0 && height > 0 && pixels.size == width * height && mask.size == pixels.size)
        fun bounds(box: BubbleBox) = intArrayOf(floor(box.left).toInt().coerceIn(0, width),
            floor(box.top).toInt().coerceIn(0, height), ceil(box.right).toInt().coerceIn(0, width),
            ceil(box.bottom).toInt().coerceIn(0, height))
        val focusBounds = bounds(focus)
        val histogram = IntArray(4096)
        fun bucket(color: Int) = ((color ushr 12) and 0xf00) or ((color ushr 8) and 0xf0) or ((color ushr 4) and 15)
        for (y in focusBounds[1] until focusBounds[3]) for (x in focusBounds[0] until focusBounds[2]) {
            val i = y * width + x
            if ((mask[i] and 255) < 128) histogram[bucket(pixels[i])]++
        }
        val dominant = histogram.indices.maxByOrNull { histogram[it] } ?: return null
        if (histogram[dominant] < 16) return null
        var seed = -1; var distance = Float.POSITIVE_INFINITY
        val cx = (focus.left + focus.right) / 2; val cy = (focus.top + focus.bottom) / 2
        for (y in focusBounds[1] until focusBounds[3]) for (x in focusBounds[0] until focusBounds[2]) {
            val i = y * width + x
            val d = (x - cx).pow(2) + (y - cy).pow(2)
            if ((mask[i] and 255) < 128 && bucket(pixels[i]) == dominant && d < distance) { seed = i; distance = d }
        }
        if (seed < 0) return null
        val background = pixels[seed]
        val knownText = BooleanArray(pixels.size)
        for (box in textBoxes) {
            val b = bounds(box)
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) knownText[y * width + x] = true
        }
        fun matches(i: Int): Boolean {
            if (knownText[i] && (mask[i] and 255) >= 128) return true
            for (channel in 0..2) if (abs(((pixels[i] ushr (channel * 8)) and 255) -
                    ((background ushr (channel * 8)) and 255)) > 18) return false
            return true
        }
        val cells = BooleanArray(pixels.size); val queue = IntArray(pixels.size)
        cells[seed] = true; queue[0] = seed
        var head = 0; var tail = 1
        var left = width; var top = height; var right = 0; var bottom = 0
        fun enqueue(n: Int) { if (!cells[n] && matches(n)) { cells[n] = true; queue[tail++] = n } }
        while (head < tail) {
            val i = queue[head++]; val x = i % width; val y = i / width
            if (x == 0 || x == width - 1 || y == 0 || y == height - 1) return null
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            enqueue(i - 1); enqueue(i + 1); enqueue(i - width); enqueue(i + width)
        }
        // Reject tiny glyph counters and sparse irregular artwork rather than inventing a bubble.
        if (tail < 64 || tail < focus.width * focus.height * 1.2f ||
            tail < (right - left + 1) * (bottom - top + 1) * .5f) return null
        for (box in textBoxes) {
            val b = bounds(box)
            if (b[2] - b[0] < 2 || b[3] - b[1] < 2) continue
            if (!listOf(b[1] * width + b[0], b[1] * width + b[2] - 1,
                    (b[3] - 1) * width + b[0], (b[3] - 1) * width + b[2] - 1).all { cells[it] }) continue
            // Restore glyph counters/missed strokes only inside fully enclosed OCR boxes.
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) cells[y * width + x] = true
        }
        val rectangle = inscribed(cells, width, height, cx.toInt(), cy.toInt()) ?: return null
        val pad = max(2f, min(rectangle.width, rectangle.height) * .04f)
        val layout = BubbleBox(rectangle.left + pad, rectangle.top + pad, rectangle.right - pad, rectangle.bottom - pad)
        return if (layout.width >= 12 && layout.height >= 12) Interior(cells, layout) else null
    }

    /** Largest all-interior rectangle containing the text anchor, in O(width * height). */
    internal fun inscribed(cells: BooleanArray, width: Int, height: Int, anchorX: Int, anchorY: Int): BubbleBox? {
        val heights = IntArray(width); val stack = IntArray(width + 1)
        var best: BubbleBox? = null; var bestArea = 0
        for (y in 0 until height) {
            for (x in 0 until width) heights[x] = if (cells[y * width + x]) heights[x] + 1 else 0
            var count = 0
            for (x in 0..width) {
                val h = if (x < width) heights[x] else 0
                while (count > 0 && heights[stack[count - 1]] > h) {
                    val tall = heights[stack[--count]]
                    val left = if (count == 0) 0 else stack[count - 1] + 1
                    val top = y + 1 - tall; val area = (x - left) * tall
                    if (area > bestArea && anchorX in left until x && anchorY in top..y) {
                        bestArea = area; best = BubbleBox(left.toFloat(), top.toFloat(), x.toFloat(), (y + 1).toFloat())
                    }
                }
                if (x < width) stack[count++] = x
            }
        }
        return best
    }
}
