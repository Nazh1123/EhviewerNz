package com.hippo.ehviewer.translation

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationInputImageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun llmBackendsPassOriginalPixelsWhileMlKitKeepsItsImageBudget() {
        val source = temp.newFile("wide.png")
        val original = Bitmap.createBitmap(4096, 64, Bitmap.Config.ARGB_8888)
        try {
            original.setPixel(4095, 63, android.graphics.Color.RED)
            source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { original.recycle() }
        for (backend in TranslationBackend.entries) {
            val decoded = GalleryTranslationSession.decodeBounded(source, backend)
            try {
                if (backend != TranslationBackend.ML_KIT) {
                    assertEquals(4096, decoded.width)
                    assertEquals(64, decoded.height)
                    assertEquals(android.graphics.Color.RED, decoded.getPixel(4095, 63))
                } else {
                    assertEquals(2048, decoded.width)
                    assertEquals(32, decoded.height)
                }
            } finally { decoded.recycle() }
        }
    }
}
