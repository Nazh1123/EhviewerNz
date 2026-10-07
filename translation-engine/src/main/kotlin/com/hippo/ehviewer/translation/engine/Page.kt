package com.hippo.ehviewer.translation.engine

import android.graphics.Bitmap

data class Pt(val x: Float, val y: Float)

class TextLine(val quad: List<Pt>, val score: Float) {
    var direction: String = "h"
    var text: String = ""
    var bubble: BubbleBoundary? = null
}

class TextRegion(
    val lines: List<TextLine>, val direction: String, val angle: Float = 0f,
    val cx: Float = 0f, val cy: Float = 0f, val boxW: Float = 0f, val boxH: Float = 0f,
    val sourceSeparator: String = "",
) {
    val bubble: BubbleBoundary? = lines.firstOrNull()?.bubble?.takeIf { boundary -> lines.all { it.bubble === boundary } }
    val x0: Float = lines.minOf { it.quad.minOf(Pt::x) }
    val y0: Float = lines.minOf { it.quad.minOf(Pt::y) }
    val x1: Float = lines.maxOf { it.quad.maxOf(Pt::x) }
    val y1: Float = lines.maxOf { it.quad.maxOf(Pt::y) }
    val sourceText: String get() = lines.joinToString(sourceSeparator) { it.text.trim() }
    var translatedText: String = ""
    @Volatile var onArt: Boolean = false
}

class Detection(val lines: List<TextLine>, val textMask: Bitmap)

sealed interface PageResult {
    data class Translated(val page: Bitmap, val stats: PageStats) : PageResult
    data class Skipped(val reason: String, val stats: PageStats) : PageResult
    data class Failed(val reason: String) : PageResult
}

data class PageStats(val lines: Int, val regions: Int, val kept: Int,
    val detectMs: Long, val ocrMs: Long, val translateMs: Long, val inpaintMs: Long,
    val renderMs: Long, val wallMs: Long = 0, val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val totalMs: Long get() = detectMs + ocrMs + translateMs + inpaintMs + renderMs
}

interface TranslationEngine : AutoCloseable {
    suspend fun translatePage(page: Bitmap): PageResult
    fun warmUp()
}

interface Translator {
    suspend fun translate(queries: List<String>): List<String>
}

object EngineTrace {
    @Volatile var sink: ((String) -> Unit)? = null
    fun log(message: String) { sink?.invoke(message) }
}
