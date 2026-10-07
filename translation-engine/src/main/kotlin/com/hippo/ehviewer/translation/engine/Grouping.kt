package com.hippo.ehviewer.translation.engine

import kotlin.math.*

object Grouping {
    fun group(lines: List<TextLine>, sourceSeparator: String = ""): List<TextRegion> {
        if (lines.isEmpty()) return emptyList()
        val boxes = lines.map { Geometry.bounds(it.quad)?.let(Geometry::canonical) }
        val parent = IntArray(lines.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) { parent[current] = parent[parent[current]]; current = parent[current] }
            return current
        }
        for (direction in listOf("h", "v")) {
            val indices = lines.indices.filter { lines[it].direction == direction && boxes[it] != null }
                .sortedBy { if (direction == "v") boxes[it]!!.cx else boxes[it]!!.cy }
            val maxThickness = indices.maxOfOrNull { if (direction == "v") boxes[it]!!.width else boxes[it]!!.height } ?: continue
            for (a in indices.indices) {
                val i = indices[a]; val first = boxes[i]!!
                for (b in a + 1 until indices.size) {
                    val j = indices[b]; val second = boxes[j]!!
                    val separation = if (direction == "v") second.cx - first.cx else second.cy - first.cy
                    if (separation > 2.2f * maxThickness) break
                    val thickness = if (direction == "v") min(first.width, second.width) else min(first.height, second.height)
                    val otherThickness = if (direction == "v") max(first.width, second.width) else max(first.height, second.height)
                    if (thickness <= 0 || otherThickness > thickness * 2.2f || abs(first.radians - second.radians) > 0.3f) continue
                    val firstLength = if (direction == "v") first.height else first.width
                    val secondLength = if (direction == "v") second.height else second.width
                    val flowOffset = if (direction == "v") abs(first.cy - second.cy) else abs(first.cx - second.cx)
                    val overlap = (firstLength + secondLength) / 2 - flowOffset
                    // Spaced horizontal text can cross the old gap cutoff by less than a pixel.
                    // Keep the stricter CJK/vertical rule while joining adjacent English/Korean lines.
                    val gapRatio = if (direction == "h" && sourceSeparator == " ") 0.5f else 0.45f
                    if (separation <= (thickness + otherThickness) / 2 + thickness * gapRatio &&
                        overlap >= min(firstLength, secondLength) * 0.5f) parent[root(j)] = root(i)
                }
            }
        }
        return lines.indices.filter { boxes[it] != null }.groupBy(::root).values.map { members ->
            val vertical = members.count { lines[it].direction == "v" } * 2 > members.size
            val ordered = members.sortedBy { if (vertical) -boxes[it]!!.cx else boxes[it]!!.cy }.map(lines::get)
            val box = Geometry.bounds(ordered.flatMap(TextLine::quad))?.let(Geometry::canonical)
            TextRegion(ordered, if (vertical) "v" else "h", box?.radians?.times(180f / PI.toFloat()) ?: 0f,
                box?.cx ?: 0f, box?.cy ?: 0f, box?.width ?: 0f, box?.height ?: 0f, sourceSeparator)
        }
    }
}
