package com.hippo.ehviewer.ui.dialog

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.updater.ReleaseNotes
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import java.net.URI

/** A bounded, theme-aware release-notes body. Dialog title and actions remain outside the scroll area. */
internal class UpdateNotesView(
    context: Context,
    markdown: String,
    releaseUrl: String,
    private val openLink: (String) -> Unit,
    previousReleases: List<ReleaseNotes> = emptyList()
) : NestedScrollView(context) {
    private val markwon by lazy {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val baseUrl = view.tag as? String ?: releaseUrl
                        openWebLink(baseUrl, link)
                    }
                }

                override fun beforeRender(node: Node) {
                    // Use clickable alt text in place of remote images.
                    node.accept(object : AbstractVisitor() {
                        override fun visit(image: Image) {
                            val link = Link(image.destination, image.title)
                            while (image.firstChild != null) link.appendChild(image.firstChild)
                            if (link.firstChild == null) link.appendChild(Text(image.destination))
                            image.insertBefore(link)
                            image.unlink()
                        }
                    })
                }
            })
            .build()
    }

    val notes: TextView = createNotes(markdown, releaseUrl)

    init {
        val density = resources.displayMetrics.density
        val horizontal = (24 * density).toInt()
        val vertical = (8 * density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        clipToPadding = false
        val sections = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sections.addView(notes)
        for (release in previousReleases) {
            sections.addView(AppCompatTextView(context).apply {
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
                setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
                text = release.version
                ViewCompat.setAccessibilityHeading(this, true)
            })
            sections.addView(createNotes(release.markdown, release.url))
        }
        addView(sections, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun createNotes(markdown: String, url: String): TextView = AppCompatTextView(context).apply {
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        setTextIsSelectable(true)
        setLineSpacing(2 * resources.displayMetrics.density, 1f)
        tag = url
        try {
            markwon.setMarkdown(this, markdown)
        } catch (e: Exception) {
            Analytics.recordException(e)
            text = markdown
        }
    }

    private fun openWebLink(releaseUrl: String, link: String) {
        try {
            val resolved = URI(releaseUrl).resolve(link)
            if (resolved.scheme.equals("https", true) || resolved.scheme.equals("http", true)) {
                openLink(resolved.toString())
            }
        } catch (e: Exception) {
            Analytics.recordException(e)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val available = if (mode == MeasureSpec.UNSPECIFIED) maxHeight
            else minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }
}
