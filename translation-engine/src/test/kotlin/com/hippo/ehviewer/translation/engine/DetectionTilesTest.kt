package com.hippo.ehviewer.translation.engine

import org.junit.Assert.*
import org.junit.Test

class DetectionTilesTest {
    @Test fun overlappingWindowsCoverEveryPixelAndNormalPagesStaySinglePass() {
        for ((width, height) in listOf(1000 to 20000, 16000 to 500, 64 to 4096)) {
            val tiles = DetectionTiles.plan(width, height, DetectorConfig())
            val vertical = height > width
            val coverage = IntArray(maxOf(width, height))
            for (tile in tiles) {
                assertTrue(tile.x >= 0 && tile.y >= 0 && tile.x + tile.width <= width && tile.y + tile.height <= height)
                val first = if (vertical) tile.y else tile.x
                val length = if (vertical) tile.height else tile.width
                assertTrue(length <= maxOf(1024, minOf(width, height) * 3 / 2))
                for (i in first until first + length) coverage[i]++
            }
            assertTrue(tiles.size > 1); assertTrue(coverage.all { it > 0 }); assertTrue(coverage.any { it > 1 })
        }
        assertEquals(1, DetectionTiles.plan(1000, 1500, DetectorConfig()).size)
        assertEquals(1, DetectionTiles.plan(64, 1000, DetectorConfig()).size)
        assertEquals(1, DetectionTiles.plan(1000, 20000, DetectorConfig(tileLongImages = false)).size)
    }
    private fun candidate(tile: Int, x: Float, y: Float, width: Float, height: Float,
                          start: Boolean = false, end: Boolean = false, offset: Int = tile * 750, angle: Float = 0f) =
        DetectionTiles.Candidate(TextLine(OrientedBox(x + width / 2, y + height / 2, width, height, angle).corners(), .9f).apply {
            direction = if (height > width) "v" else "h"
        }, setOf(tile), offset, start, end)

    @Test fun completeOverlapObservationWinsOverTruncatedFragmentWithoutDroppingAdjacentLines() {
        val lines = DetectionTiles.merge(listOf(candidate(0, 100f, 930f, 100f, 69f, end = true),
            candidate(1, 100f, 930f, 100f, 110f), candidate(1, 100f, 1080f, 100f, 70f)), true)
        // Clipping may change the aspect ratio and therefore DBNet's provisional direction.
        val complete = candidate(1, 100f, 930f, 100f, 110f)
        complete.line.direction = "h"
        val merged = DetectionTiles.merge(listOf(candidate(0, 100f, 930f, 100f, 69f, end = true), complete,
            candidate(1, 100f, 1080f, 100f, 70f)), true)
        assertEquals(2, lines.size)
        assertEquals(2, merged.size)
        assertEquals(1040f, merged.first().quad.maxOf(Pt::y), .01f)
    }
    @Test fun seamFragmentsOfOneLongColumnJoinButNeighboringColumnsStaySeparate() {
        val merged = DetectionTiles.merge(listOf(candidate(0, 100f, 100f, 30f, 899f, end = true),
            candidate(1, 100f, 750f, 30f, 999f, start = true, end = true),
            candidate(2, 100f, 1500f, 30f, 700f, start = true),
            candidate(1, 160f, 750f, 30f, 999f, start = true, end = true)), true)
        assertEquals(2, merged.size)
        val long = merged.single { it.quad.minOf(Pt::x) < 150 }
        assertEquals(100f, long.quad.minOf(Pt::y), .01f); assertEquals(2200f, long.quad.maxOf(Pt::y), .01f)
    }
    @Test fun rotatedDuplicatesUsePolygonIntersectionAndSameTileBoxesAreNotSuppressed() {
        val a = candidate(0, 100f, 100f, 180f, 25f, angle = .3f)
        val b = candidate(1, 102f, 101f, 180f, 25f, angle = .3f)
        val neighbor = candidate(1, 103f, 144f, 180f, 25f, angle = .3f)
        assertEquals(2, DetectionTiles.merge(listOf(a, b, neighbor), true).size)
        assertEquals(2, DetectionTiles.merge(listOf(a, a.copy(line = a.line)), true).size)
        // Canonical boxes swap axes when rounding crosses 45 degrees.
        assertEquals(1, DetectionTiles.merge(listOf(candidate(0, 100f, 100f, 180f, 25f, angle = .79f),
            candidate(1, 100f, 100f, 180f, 25f, angle = .78f)), true).size)
    }
}
