package com.hippo.ehviewer.translation.engine

import android.graphics.*
import kotlin.math.*

object Renderer {
    internal data class Glyph(val text: String, val x: Float, val y: Float, val scaleX: Float = 1f, val rotation: Float = 0f)
    internal class Block(val region: TextRegion, val size: Float, internal val glyphs: List<Glyph>, val face: Typeface)
    class Layout internal constructor(internal val blocks: List<Block>, internal val config: RenderConfig)

    fun prepareLayout(regions: List<TextRegion>, cfg: RenderConfig = RenderConfig(), tf: Typeface? = null): Layout {
        val face = tf ?: Typeface.DEFAULT
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
        val blocks = regions.mapNotNull { region ->
            val text = region.translatedText.ifBlank { region.sourceText }
            if (text.isBlank() || region.x1 - region.x0 < 8 || region.y1 - region.y0 < 8) return@mapNotNull null
            val vertical = when (cfg.orientation) {
                TextOrientation.AUTO -> region.direction == "v"
                TextOrientation.VERTICAL -> true
                TextOrientation.HORIZONTAL -> false
            }
            val width = (if (region.boxW > 0) region.boxW else region.x1 - region.x0) * 1.3f
            val height = (if (region.boxH > 0) region.boxH else region.y1 - region.y0) * 1.5f
            var low = cfg.fontSizeMin; var high = cfg.fontSizeMax
            while (low < high) {
                val size = (low + high + 1) / 2
                measure.textSize = size.toFloat()
                val fit = if (vertical) {
                    val cells = verticalCells(text, cfg.tateChuYoko).size
                    val rows = max(1, (height / size).toInt() - 3)
                    ceil(cells.toFloat() / rows) * size <= width
                } else wrap(text, measure, width, 3).size * size * 1.15f <= height
                if (fit) low = size else high = size - 1
            }
            val size = low * cfg.fontScale
            measure.textSize = size
            val centerX = (region.x0 + region.x1) / 2; val centerY = (region.y0 + region.y1) / 2
            val metrics = measure.fontMetrics
            val baselineOffset = -(metrics.ascent + metrics.descent) / 2
            val glyphs = if (vertical) {
                val cells = verticalCells(text, cfg.tateChuYoko)
                val rows = max(1, (height / size).toInt() - 3)
                val columns = (cells.size + rows - 1) / rows
                cells.mapIndexed { index, cell ->
                    val column = index / rows; val row = index % rows
                    val count = min(rows, cells.size - column * rows)
                    val x = centerX + (columns - 1) * size / 2 - column * size
                    val y = centerY - (count - 1) * size / 2 + row * size
                    val rotate = if (cell.length == 1 && cell[0] in "（）()［］[]｛｝{}「」『』ー—…") 90f else 0f
                    Glyph(cell, x, y + baselineOffset, min(1f, size / measure.measureText(cell).coerceAtLeast(1f)), rotate)
                }
            } else {
                val rows = wrap(text, measure, width, 3)
                rows.mapIndexed { index, row -> Glyph(row, centerX,
                    centerY + (index - (rows.size - 1) / 2f) * size * 1.15f + baselineOffset) }
            }
            Block(region, size, glyphs, face)
        }
        return Layout(blocks, cfg)
    }

    fun render(page: Bitmap, regions: List<TextRegion>, cfg: RenderConfig = RenderConfig(), tf: Typeface? = null,
               original: Bitmap? = null, preservedRegions: List<TextRegion> = emptyList(), padding: Int = 0): Bitmap =
        compose(page, prepareLayout(regions, cfg, tf), original, preservedRegions, padding)

    fun compose(page: Bitmap, layout: Layout, original: Bitmap? = null,
                preservedRegions: List<TextRegion> = emptyList(), padding: Int = 0): Bitmap {
        val output = page.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val canvas = Canvas(output)
            // Inpainting may finish before translation. Restore unchanged/failed regions
            // from the original before drawing the translated regions, without another bitmap.
            if (original != null && preservedRegions.isNotEmpty()) {
                val saved = canvas.save()
                val translatedLines = Path()
                for (block in layout.blocks) for (line in block.region.lines) {
                    // OCR boxes can omit the last few pixels of a glyph's descender.
                    val box = Geometry.bounds(line.quad)
                    val points = box?.corners(max(1f, min(box.width, box.height) * 0.1f)) ?: line.quad
                    if (points.size < 3) continue
                    translatedLines.moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { translatedLines.lineTo(it.x, it.y) }
                    translatedLines.close()
                }
                // An OCR candidate can overlap the edge of a real dialogue line.
                // Restore its artwork without bringing translated source glyphs back.
                canvas.clipOutPath(translatedLines)
                for (region in preservedRegions) {
                    val bounds = Rect(floor(region.x0 - padding).toInt().coerceAtLeast(0),
                        floor(region.y0 - padding).toInt().coerceAtLeast(0),
                        ceil(region.x1 + padding).toInt().coerceAtMost(page.width),
                        ceil(region.y1 + padding).toInt().coerceAtMost(page.height))
                    if (!bounds.isEmpty) canvas.drawBitmap(original, bounds, bounds, null)
                }
                canvas.restoreToCount(saved)
            }
            for (block in layout.blocks) {
                val dark = layout.config.colorMode == "auto" && backgroundBrightness(page, block.region) < 110
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = block.face; textSize = block.size; textAlign = Paint.Align.CENTER
                    color = if (dark) Color.WHITE else Color.BLACK
                }
                val outline = Paint(fill).apply {
                    style = Paint.Style.STROKE; color = if (dark) Color.BLACK else Color.WHITE
                    strokeWidth = max(2f, block.size * if (block.region.onArt) 0.16f else 0.1f)
                    strokeJoin = Paint.Join.ROUND
                }
                val save = canvas.save()
                if (abs(block.region.angle) >= 1) canvas.rotate(block.region.angle,
                    (block.region.x0 + block.region.x1) / 2, (block.region.y0 + block.region.y1) / 2)
                for (glyph in block.glyphs) {
                    val glyphSave = canvas.save()
                    canvas.scale(glyph.scaleX, 1f, glyph.x, glyph.y)
                    if (glyph.rotation != 0f) canvas.rotate(glyph.rotation, glyph.x, glyph.y + fill.fontMetrics.ascent / 2)
                    if (layout.config.fontBorder) canvas.drawText(glyph.text, glyph.x, glyph.y, outline)
                    canvas.drawText(glyph.text, glyph.x, glyph.y, fill)
                    canvas.restoreToCount(glyphSave)
                }
                canvas.restoreToCount(save)
            }
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }

    private fun backgroundBrightness(page: Bitmap, region: TextRegion): Int {
        val x0 = region.x0.toInt().coerceIn(0, page.width - 1); val y0 = region.y0.toInt().coerceIn(0, page.height - 1)
        val x1 = region.x1.toInt().coerceIn(x0, page.width - 1); val y1 = region.y1.toInt().coerceIn(y0, page.height - 1)
        var sum = 0L; var count = 0
        val step = max(1, max(x1 - x0, y1 - y0) / 24)
        for (y in y0..y1 step step) for (x in x0..x1 step step) {
            val color = page.getPixel(x, y)
            sum += (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000
            count++
        }
        return (sum / count.coerceAtLeast(1)).toInt()
    }

    private fun verticalCells(text: String, combine: Boolean): List<String> {
        val result = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '\n') { index++; continue }
            var end = index + Character.charCount(text.codePointAt(index))
            if (combine && text[index].isAsciiCell()) {
                while (end < text.length && text[end].isAsciiCell()) end++
                if (end - index !in 2..4) end = index + 1
            }
            result.add(text.substring(index, end)); index = end
        }
        return result
    }
    private fun Char.isAsciiCell() = this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z' || this in "!?"

    private fun wrap(text: String, paint: Paint, width: Float, trim: Int): List<String> {
        val limit = max(paint.textSize, width - trim * paint.textSize)
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { lines.add(""); continue }
            var start = 0
            while (start < paragraph.length) {
                var count = paint.breakText(paragraph, start, paragraph.length, true, limit, null).coerceAtLeast(1)
                if (start + count < paragraph.length && Character.isHighSurrogate(paragraph[start + count - 1])) count--
                count = count.coerceAtLeast(Character.charCount(paragraph.codePointAt(start)))
                var end = (start + count).coerceAtMost(paragraph.length)
                if (end < paragraph.length) {
                    val space = paragraph.lastIndexOf(' ', end - 1)
                    if (space > start && end - space < count / 2) end = space
                    while (end < paragraph.length && paragraph[end] in "，。！？、；：)]）】」』") end++
                }
                lines.add(paragraph.substring(start, end).trim())
                start = end
                while (start < paragraph.length && paragraph[start] == ' ') start++
            }
        }
        return lines
    }
}
