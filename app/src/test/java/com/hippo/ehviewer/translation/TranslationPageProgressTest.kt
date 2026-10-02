package com.hippo.ehviewer.translation

import li.joye.yakuyomi.engine.TranslationStage
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationPageProgressTest {
    @Test fun overlappingStagesContributeIndependentlyAndNeverMoveBackwards() {
        val progress = TranslationPageProgress()
        progress.update(TranslationStage.DETECT, 1f)
        progress.update(TranslationStage.OCR, 1f)
        progress.update(TranslationStage.TRANSLATE, 0.5f)
        assertEquals(575, progress.value)
        progress.update(TranslationStage.INPAINT, 1f)
        assertEquals(725, progress.value)
        progress.update(TranslationStage.TRANSLATE, 0.25f)
        assertEquals(725, progress.value)
        progress.update(TranslationStage.TRANSLATE, 1f)
        assertEquals(950, progress.value)
        progress.update(TranslationStage.RENDER, 1f)
        assertEquals(1000, progress.value)
    }
}
