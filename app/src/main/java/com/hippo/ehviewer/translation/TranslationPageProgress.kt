package com.hippo.ehviewer.translation

import com.hippo.ehviewer.translation.engine.TranslationStage
import kotlin.math.roundToInt

/** Independent stage contributions allow translation and inpainting to finish in either order. */
internal class TranslationPageProgress {
    private val completed = mutableMapOf<TranslationStage, Float>()

    val value: Int get() = TranslationStage.entries.sumOf { stage ->
        (stage.weight * 10 * (completed[stage] ?: 0f)).roundToInt()
    }

    fun update(stage: TranslationStage, fraction: Float) {
        if (!fraction.isFinite()) return
        completed[stage] = maxOf(completed[stage] ?: 0f, fraction.coerceIn(0f, 1f))
    }
}
