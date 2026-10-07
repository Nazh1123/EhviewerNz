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

    @Test fun longImagesPreserveShortEdgePixelsBeforeTiledDetectionForAllBackends() {
        val source = temp.newFile("wide.png")
        val original = Bitmap.createBitmap(4096, 64, Bitmap.Config.ARGB_8888)
        try {
            original.setPixel(4095, 63, android.graphics.Color.RED)
            source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { original.recycle() }
        for (backend in TranslationBackend.entries) {
            val decoded = GalleryTranslationSession.decodeBounded(source, backend)
            try {
                assertEquals(4096, decoded.width)
                assertEquals(64, decoded.height)
                assertEquals(android.graphics.Color.RED, decoded.getPixel(4095, 63))
            } finally { decoded.recycle() }
        }
    }
    @Test fun mlKitRetainsNormalPageBudgetAndBoundsLongPagePixelMemory() {
        for ((width, height, expectedWidth, expectedHeight) in listOf(
            listOf(3072, 3072, 1536, 1536), listOf(1024, 12000, 512, 6000))) {
            val source = temp.newFile("$width-$height.png")
            val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try { source.outputStream().use { assertTrue(page.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { page.recycle() }
            val decoded = GalleryTranslationSession.decodeBounded(source, TranslationBackend.ML_KIT)
            try { assertEquals(expectedWidth, decoded.width); assertEquals(expectedHeight, decoded.height) }
            finally { decoded.recycle() }
        }
    }
}
