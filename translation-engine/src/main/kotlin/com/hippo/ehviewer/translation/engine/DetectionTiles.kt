package com.hippo.ehviewer.translation.engine

import android.graphics.*
import kotlin.math.*

/** Keep DBNet's input scale useful on long strips; only one tile and model workspace live at a time. */
object DetectionTiles {
    internal data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)
    internal data class Candidate(val line: TextLine, val tiles: Set<Int>, val offset: Int,
                                  val startClipped: Boolean, val endClipped: Boolean) {
        val bounds = Geometry.bounds(line.quad)?.let(Geometry::canonical)
        val polygonArea = area(line.quad)
        val left = line.quad.minOf(Pt::x); val top = line.quad.minOf(Pt::y)
        val right = line.quad.maxOf(Pt::x); val bottom = line.quad.maxOf(Pt::y)
    }

    internal fun plan(width: Int, height: Int, cfg: DetectorConfig): List<Tile> {
        require(width > 0 && height > 0 && cfg.inputSize > 0)
        val vertical = height >= width
        val short = min(width, height); val long = max(width, height)
        if (!cfg.tileLongImages || long <= cfg.inputSize || long.toFloat() / short <= 2.5f)
            return listOf(Tile(0, 0, width, height))
        val length = max(cfg.inputSize, (short.toLong() * 3 / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).coerceAtMost(long)
        val overlap = max(32, length / 4).coerceAtMost(length - 1)
        val result = ArrayList<Tile>()
        var start = 0
        while (true) {
            result.add(if (vertical) Tile(0, start, width, length) else Tile(start, 0, length, height))
            if (start == long - length) break
            start = min(start + length - overlap, long - length)
        }
        return result
    }

    fun detect(page: Bitmap, cfg: DetectorConfig, infer: (Bitmap) -> Detection,
               checkRelevant: () -> Unit = {}): Detection {
        checkRelevant()
        val tiles = plan(page.width, page.height, cfg)
        if (tiles.size == 1) return infer(page)
        val vertical = page.height >= page.width
        val mask = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.BLACK)
        val canvas = Canvas(mask)
        val union = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN) }
        val candidates = ArrayList<Candidate>()
        try {
            for ((index, tile) in tiles.withIndex()) {
                checkRelevant()
                EngineTrace.log("detect.tile ${index + 1}/${tiles.size} ${tile.x},${tile.y} ${tile.width}x${tile.height}")
                val crop = Bitmap.createBitmap(page, tile.x, tile.y, tile.width, tile.height)
                try {
                    val detected = infer(crop)
                    try {
                        checkRelevant()
                        require(detected.textMask.width == tile.width && detected.textMask.height == tile.height)
                        canvas.drawBitmap(detected.textMask, tile.x.toFloat(), tile.y.toFloat(), union)
                        for (line in detected.lines) {
                            if (line.quad.size < 3) continue
                            val first = line.quad.minOf { if (vertical) it.y else it.x }
                            val last = line.quad.maxOf { if (vertical) it.y else it.x }
                            val offset = if (vertical) tile.y else tile.x
                            val length = if (vertical) tile.height else tile.width
                            val whole = if (vertical) page.height else page.width
                            val shifted = TextLine(line.quad.map { Pt(it.x + tile.x, it.y + tile.y) }, line.score).apply {
                                direction = line.direction; text = line.text
                            }
                            candidates.add(Candidate(shifted, setOf(index), offset,
                                offset > 0 && first <= 2f, offset + length < whole && last >= length - 2f))
                        }
                    } finally { if (detected.textMask !== crop && detected.textMask !== page) detected.textMask.recycle() }
                } finally { if (crop !== page) crop.recycle() }
            }
            checkRelevant()
            return Detection(merge(candidates, vertical), mask)
        } catch (error: Throwable) { mask.recycle(); throw error }
    }

    internal fun merge(candidates: List<Candidate>, vertical: Boolean): List<TextLine> {
        val kept = ArrayList<Candidate>()
        fun box(candidate: Candidate) = candidate.bounds
        fun compatible(a: Candidate, b: Candidate): Boolean {
            if (a.tiles.any { it in b.tiles }) return false
            if (min(a.right, b.right) <= max(a.left, b.left) || min(a.bottom, b.bottom) <= max(a.top, b.top)) return false
            val first = box(a) ?: return false; val second = box(b) ?: return false
            val difference = abs(first.radians - second.radians)
            return min(difference, PI.toFloat() / 2 - difference) < .25f
        }
        fun duplicate(a: Candidate, b: Candidate): Boolean {
            if (!compatible(a, b)) return false
            val area = min(a.polygonArea, b.polygonArea)
            return area > 0 && intersection(a.line.quad, b.line.quad) / area >= .7f
        }
        fun seam(a: Candidate, b: Candidate): Boolean {
            val direction = if (vertical) "v" else "h"
            if (!compatible(a, b) || a.line.direction != direction || b.line.direction != direction) return false
            val before = if (a.offset < b.offset) a else b
            val after = if (a.offset < b.offset) b else a
            if (!before.endClipped || !after.startClipped) return false
            val first = box(a)!!; val second = box(b)!!
            if (abs(first.radians) > .2f || abs(second.radians) > .2f) return false
            val crossDistance = if (vertical) abs(first.cx - second.cx) else abs(first.cy - second.cy)
            val thickness = if (vertical) min(first.width, second.width) else min(first.height, second.height)
            val area = min(a.polygonArea, b.polygonArea)
            return crossDistance <= thickness * .25f && area > 0 && intersection(a.line.quad, b.line.quad) / area >= .12f
        }
        fun joined(a: Candidate, b: Candidate): Candidate {
            val quad = checkNotNull(Geometry.bounds(a.line.quad + b.line.quad)).let(Geometry::canonical).corners()
            val line = TextLine(quad, max(a.line.score, b.line.score)).apply { direction = a.line.direction }
            val before = if (a.offset < b.offset) a else b; val after = if (a.offset < b.offset) b else a
            return Candidate(line, a.tiles + b.tiles, min(a.offset, b.offset), before.startClipped, after.endClipped)
        }
        // Full observations precede truncated ones; an overlap fragment cannot replace a complete line.
        val ordered = candidates.sortedWith(compareBy<Candidate> { it.startClipped || it.endClipped }
            .thenByDescending { it.polygonArea }.thenByDescending { it.line.score })
        for (candidate in ordered) {
            if (kept.any { duplicate(it, candidate) }) continue
            var current = candidate
            while (true) {
                val index = kept.indexOfFirst { seam(it, current) }
                if (index < 0) break
                current = joined(kept.removeAt(index), current)
            }
            kept.add(current)
        }
        return kept.map { it.line }.sortedWith(compareBy<TextLine> { it.quad.minOf(Pt::y) }.thenBy { it.quad.minOf(Pt::x) })
    }

    private fun signedArea(points: List<Pt>): Float = points.indices.sumOf { index ->
        val next = points[(index + 1) % points.size]
        (points[index].x.toDouble() * next.y - next.x.toDouble() * points[index].y)
    }.toFloat() / 2
    private fun area(points: List<Pt>) = if (points.size < 3) 0f else abs(signedArea(points))

    /** Convex quad intersection, preserving rotated text instead of comparing axis-aligned boxes. */
    private fun intersection(first: List<Pt>, clip: List<Pt>): Float {
        var polygon = first
        val sign = if (signedArea(clip) >= 0) 1f else -1f
        for (edge in clip.indices) {
            if (polygon.isEmpty()) return 0f
            val a = clip[edge]; val b = clip[(edge + 1) % clip.size]
            fun distance(point: Pt) = sign * ((b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x))
            val output = ArrayList<Pt>()
            var previous = polygon.last(); var previousDistance = distance(previous)
            for (point in polygon) {
                val d = distance(point)
                if ((d >= 0) != (previousDistance >= 0)) {
                    val t = previousDistance / (previousDistance - d)
                    output.add(Pt(previous.x + t * (point.x - previous.x), previous.y + t * (point.y - previous.y)))
                }
                if (d >= 0) output.add(point)
                previous = point; previousDistance = d
            }
            polygon = output
        }
        return area(polygon)
    }
}
