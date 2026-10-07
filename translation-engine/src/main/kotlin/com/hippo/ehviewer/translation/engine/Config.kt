package com.hippo.ehviewer.translation.engine

data class EngineConfig(
    val detector: DetectorConfig = DetectorConfig(),
    val ocr: OcrConfig = OcrConfig(),
    val inpainter: InpainterConfig = InpainterConfig(),
    val render: RenderConfig = RenderConfig(),
    val translator: TranslatorConfig = TranslatorConfig(),
)

data class DetectorConfig(
    val inputSize: Int = 1024,
    val textThreshold: Float = 0.5f,
    val boxThreshold: Float = 0.7f,
    val strokeThreshold: Float = 0.12f,
    val expansion: Float = 2.3f,
    val tileLongImages: Boolean = true,
)

data class OcrConfig(
    val minProb: Float = 0.5f,
    val stripPad: Int = 4,
    val concurrent: Boolean = true,
    val concurrency: Int = 4,
)

data class InpainterConfig(val method: String = "aot", val tileSize: Int = 768,
                           val maskRadius: Int = 12, val regionPad: Int = 16)

data class TranslatorConfig(val fromLangName: String = "Japanese", val toLangName: String = "Simplified Chinese")

enum class TextOrientation { AUTO, HORIZONTAL, VERTICAL }

data class RenderConfig(
    val orientation: TextOrientation = TextOrientation.AUTO,
    val fontBorder: Boolean = true,
    val colorMode: String = "auto",
    val fontSizeMin: Int = 9,
    val fontSizeMax: Int = 60,
    val fontScale: Float = 0.85f,
    val tateChuYoko: Boolean = true,
)
