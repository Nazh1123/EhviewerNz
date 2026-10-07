package com.hippo.ehviewer.translation

import android.graphics.Bitmap
import com.hippo.ehviewer.translation.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LanguageOcrTest {
    private fun line() = TextLine(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f)), 1f)
    private class Fake(private val key: String, private val events: MutableList<String>) : PageOcr {
        override suspend fun recognize(page: Bitmap, lines: List<TextLine>) {
            events.add("read:$key")
            lines.forEach { it.text = "$key result" }
        }
        override fun close() { events.add("close:$key") }
    }
    @Test fun autoRereadsFirstPageAndNextPageUsesResolvedModelWithoutReloading() = runBlocking {
        var source = "auto"
        val events = mutableListOf<String>()
        val page = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val router = LanguageOcr({ source }, { lines ->
            if (source == "auto") { assertEquals("ja result", lines.single().text); source = "en" }
        }) { key -> events.add("load:$key"); Fake(key, events) }
        router.warmUp()
        assertTrue(events.isEmpty())
        val first = line(); router.recognize(page, listOf(first))
        assertEquals("en result", first.text)
        val next = line(); router.recognize(page, listOf(next))
        assertEquals(listOf("load:ja", "read:ja", "close:ja", "load:en", "read:en", "read:en"), events)
        router.close(); assertEquals("close:en", events.last()); page.recycle()
    }
    @Test fun manualTraditionalChineseLoadsChineseDirectlyAndUnresolvedAutoKeepsManga() = runBlocking {
        for ((source, expected) in listOf("zh-TW" to "zh", "ko" to "ko", "ja" to "ja", "auto" to "ja")) {
            val events = mutableListOf<String>()
            val page = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            LanguageOcr({ source }, {}) { key -> events.add("load:$key"); Fake(key, events) }.use {
                val line = line(); it.recognize(page, listOf(line)); assertEquals("$expected result", line.text)
            }
            assertEquals(listOf("load:$expected", "read:$expected", "close:$expected"), events)
            page.recycle()
        }
    }
    @Test fun failedSwitchClearsProbeInsteadOfPassingItsTextToTranslation() = runBlocking {
        var source = "auto"
        val events = mutableListOf<String>()
        val page = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val line = line()
        LanguageOcr({ source }, { source = "ko" }) { key ->
            if (key == "ko") throw MissingPpOcrModel() else Fake(key, events)
        }.use {
            try { it.recognize(page, listOf(line)); fail("Missing model accepted") }
            catch (_: MissingPpOcrModel) { }
        }
        assertEquals("", line.text)
        assertEquals(listOf("read:ja", "close:ja"), events); page.recycle()
    }
}
