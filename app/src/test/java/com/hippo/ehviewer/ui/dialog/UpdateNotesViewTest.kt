package com.hippo.ehviewer.ui.dialog

import android.app.Application
import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.ContextThemeWrapper
import android.view.View
import com.hippo.ehviewer.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class UpdateNotesViewTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    private fun notes(markdown: String, openLink: (String) -> Unit = {}) =
        UpdateNotesView(context, markdown, "https://example.com/releases/3", openLink)

    private fun spanNames(view: UpdateNotesView): List<String> = (view.notes.text as Spanned).let {
        it.getSpans(0, it.length, Any::class.java).map { span -> span.javaClass.simpleName }
    }

    @Test fun rendersHeadingsEmphasisNestedListsQuotesAndCode() {
        val view = notes("## Changes\n\n**Bold** and *italic* with `code`\n\n" +
            "- Parent\n  - Child\n\n> Quote\n\n```kotlin\n    val x = 1\n```")
        val text = view.notes.text.toString()
        assertFalse(text.contains("##"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("```"))
        assertTrue(text, text.contains("Bold and italic with"))
        assertTrue(text, text.contains("code"))
        assertTrue(text.contains("    val x = 1"))
        val spans = spanNames(view)
        for (name in listOf("HeadingSpan", "StrongEmphasisSpan", "EmphasisSpan", "CodeSpan",
            "BulletListItemSpan", "BlockQuoteSpan", "CodeBlockSpan")) {
            assertTrue("Missing $name in $spans", spans.contains(name))
        }
    }

    @Test fun selectableLinksResolveAgainstReleasePageAndOpenExternally() {
        val opened = mutableListOf<String>()
        val view = notes("[Details](../notes) and [Website](https://example.org)", opened::add)
        assertTrue(view.notes.isTextSelectable)
        val text = view.notes.text as Spanned
        val links = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(2, links.size)
        links.sortedBy(text::getSpanStart).forEach { it.onClick(view.notes) }
        assertEquals(listOf("https://example.com/notes", "https://example.org"), opened)
    }

    @Test fun imagesBecomeClickableAltTextWithoutImageSpans() {
        val opened = mutableListOf<String>()
        val view = notes("![Screenshot](https://example.com/one.png)\n\n" +
            "![](https://example.com/two.png)", opened::add)
        val text = view.notes.text as Spanned
        assertTrue(text.toString().contains("Screenshot"))
        assertTrue(text.toString().contains("https://example.com/two.png"))
        assertFalse(spanNames(view).any { it.contains("Drawable") || it.contains("ImageSpan") })
        val links = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(2, links.size)
        links.sortedBy(text::getSpanStart).forEach { it.onClick(view.notes) }
        assertEquals(listOf("https://example.com/one.png", "https://example.com/two.png"), opened)
    }

    @Test fun rendersCommonGithubExtensionsAndMarkdownAutolinks() {
        val view = notes("~~Old~~\n\n- [x] Fixed\n- [ ] Pending\n\n" +
            "| Feature | Status |\n| --- | --- |\n| Updates | Ready |\n\n<https://example.com>")
        val spans = spanNames(view)
        assertTrue(spans.toString(), spans.contains("StrikethroughSpan"))
        assertTrue(spans.toString(), spans.contains("TaskListSpan"))
        assertTrue(spans.toString(), spans.contains("TableRowSpan"))
        val text = view.notes.text as Spanned
        assertTrue(text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty())
    }

    @Test fun longNotesScrollWithinBoundedHeightAndShortNotesWrapContent() {
        val width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(10000, View.MeasureSpec.AT_MOST)
        val long = notes((1..200).joinToString("\n\n") { "Change $it" })
        long.measure(width, height)
        assertTrue(long.measuredHeight <= (long.resources.displayMetrics.heightPixels * 0.6f).toInt())
        assertTrue(long.notes.measuredHeight > long.measuredHeight)
        val short = notes("A short change")
        short.measure(width, height)
        assertTrue(short.measuredHeight < long.measuredHeight)
        val smallWindow = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST)
        long.measure(width, smallWindow)
        assertTrue(long.measuredHeight <= 100)
    }

    @Test fun markdownAutolinksOpenInBrowserButPlainTextAndCodeStayPlain() {
        val opened = mutableListOf<String>()
        val view = notes("<https://example.com/page>\n\nhttps://example.com/plain\n\n" +
            "`https://example.com/inline`\n\n" +
            "```\nhttps://example.com/block\n```", opened::add)
        val text = view.notes.text as Spanned
        val links = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(1, links.size)
        links.single().onClick(view.notes)
        assertEquals(listOf("https://example.com/page"), opened)
    }

    @Test fun dottedVersionsStayPlainUnlessExplicitlyMarkedAsMarkdownLinks() {
        val opened = mutableListOf<String>()
        val view = notes("## 2.0.2.6\n\n从 2.0.2.5 升级到 2.0.2.6\n\n" +
            "v2.0.2.6 / 2.0.2.6.0 / 1.2.3-rc.1\n\n" +
            "[2.0.2.6](https://example.com/releases/2.0.2.6)", opened::add)
        val text = view.notes.text as Spanned
        val links = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(1, links.size)
        val link = links.single()
        assertEquals("2.0.2.6", text.subSequence(text.getSpanStart(link), text.getSpanEnd(link)).toString())
        link.onClick(view.notes)
        assertEquals(listOf("https://example.com/releases/2.0.2.6"), opened)
    }

    @Test fun textFollowsLightDarkAndBlackThemes() {
        fun color(theme: Int) = UpdateNotesView(
            ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme),
            "**Change**", "https://example.com", {}).notes.currentTextColor
        val light = Color.luminance(color(R.style.AppTheme))
        assertTrue(Color.luminance(color(R.style.AppTheme_Dark)) > light)
        assertTrue(Color.luminance(color(R.style.AppTheme_Black)) > light)
    }
}
