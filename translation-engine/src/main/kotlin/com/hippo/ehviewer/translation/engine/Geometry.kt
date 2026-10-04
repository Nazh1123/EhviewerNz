package com.hippo.ehviewer.translation.engine

import kotlin.math.*

internal data class OrientedBox(val cx: Float, val cy: Float, val width: Float, val height: Float, val radians: Float) {
    fun corners(padding: Float = 0f): List<Pt> {
        val ux = cos(radians); val uy = sin(radians)
        val hw = width / 2 + padding; val hh = height / 2 + padding
        return listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (x, y) ->
            Pt(cx + x * ux - y * uy, cy + x * uy + y * ux)
        }
    }
}

internal object Geometry {
    private fun cross(a: Pt, b: Pt, c: Pt) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    fun bounds(points: List<Pt>): OrientedBox? {
        if (points.size < 3) return null
        val ordered = points.distinct().sortedWith(compareBy(Pt::x, Pt::y))
        if (ordered.size < 3) return null
        val hull = ArrayList<Pt>(ordered.size * 2)
        for (point in ordered) {
            while (hull.size > 1 && cross(hull[hull.lastIndex - 1], hull.last(), point) <= 0) hull.removeAt(hull.lastIndex)
            hull.add(point)
        }
        val lower = hull.size
        for (i in ordered.lastIndex - 1 downTo 0) {
            val point = ordered[i]
            while (hull.size > lower && cross(hull[hull.lastIndex - 1], hull.last(), point) <= 0) hull.removeAt(hull.lastIndex)
            hull.add(point)
        }
        hull.removeAt(hull.lastIndex)
        var best: OrientedBox? = null
        for (edge in hull.indices) {
            val next = hull[(edge + 1) % hull.size]
            val angle = atan2(next.y - hull[edge].y, next.x - hull[edge].x)
            val c = cos(angle); val s = sin(angle)
            var left = Float.POSITIVE_INFINITY; var top = left
            var right = Float.NEGATIVE_INFINITY; var bottom = right
            for (point in hull) {
                val x = point.x * c + point.y * s; val y = -point.x * s + point.y * c
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            }
            val w = right - left; val h = bottom - top
            if (best == null || w * h < best.width * best.height) {
                val x = (left + right) / 2; val y = (top + bottom) / 2
                best = OrientedBox(x * c - y * s, x * s + y * c, w, h, angle)
            }
        }
        return best
    }

    fun canonical(box: OrientedBox): OrientedBox {
        var angle = box.radians
        var w = box.width; var h = box.height
        while (angle < -PI / 4) { angle += (PI / 2).toFloat(); val t = w; w = h; h = t }
        while (angle >= PI / 4) { angle -= (PI / 2).toFloat(); val t = w; w = h; h = t }
        return box.copy(width = w, height = h, radians = angle)
    }
}
