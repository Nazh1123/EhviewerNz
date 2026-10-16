package com.hippo.ehviewer.gallery

import android.app.Application
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.hippo.ehviewer.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryResultLayoutTest {
    @Test fun dualResultsAlignBesideRatingAndNeverWrapOrCoverTheActionLayer() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val inflater = LayoutInflater.from(context).cloneInContext(context)
        // Keep thumbnail networking out of this layout geometry check.
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                if (name == "com.hippo.ehviewer.widget.FixedThumb") View(context, attrs) else null
            override fun onCreateView(name: String, context: Context, attrs: AttributeSet) =
                onCreateView(null, name, context, attrs)
        }
        val card = inflater.inflate(R.layout.item_download, null)
        card.findViewById<View>(R.id.thumb).layoutParams.apply { width = 100; height = 160 }
        card.findViewById<TextView>(R.id.title).text = "Gallery"
        card.findViewById<TextView>(R.id.uploader).text = "Uploader"
        card.findViewById<TextView>(R.id.category).text = "Manga"
        card.findViewById<TextView>(R.id.read_progress).text = "1/123"
        val separator = card.findViewById<TextView>(R.id.processing_separator).apply { visibility = View.VISIBLE }
        val primary = card.findViewById<TextView>(R.id.processing_progress).apply { text = "TL 54/123"; visibility = View.VISIBLE }
        val sr = card.findViewById<TextView>(R.id.sr_progress).apply { text = "SR 25/123"; visibility = View.VISIBLE }
        val rating = card.findViewById<View>(R.id.rating)
        for (width in listOf(480, 280)) {
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, width, card.measuredHeight)
            assertEquals(primary.left, sr.left)
            assertEquals(rating.top, sr.top)
            assertTrue(sr.left >= rating.right)
            assertEquals(1, primary.lineCount)
            assertEquals(1, sr.lineCount)
            assertTrue(card.findViewById<View>(R.id.actions).z > primary.z)
            assertEquals("  -  ", separator.text.toString())
        }
    }
}
