package com.hippo.ehviewer.ui.dialog

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.NestedScrollView
import com.hippo.ehviewer.Analytics
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
    private val releaseUrl: String,
    private val openLink: (String) -> Unit
) : NestedScrollView(context) {
    val notes: TextView = AppCompatTextView(context).apply {
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        setTextIsSelectable(true)
        setLineSpacing(2 * resources.displayMetrics.density, 1f)
    }

    init {
        val density = resources.displayMetrics.density
        val horizontal = (24 * density).toInt()
        val vertical = (8 * density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        clipToPadding = false
        addView(notes, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        try {
            Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(TaskListPlugin.create(context))
                .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver { _, link ->
                            openWebLink(link)
                        }
                    }

                    override fun beforeRender(node: Node) {
                        // Keep images useful as clickable alt text, without loading remote media in the dialog.
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
                .setMarkdown(notes, markdown)
        } catch (e: Exception) {
            Analytics.recordException(e)
            notes.text = markdown
        }
    }

    private fun openWebLink(link: String) {
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
