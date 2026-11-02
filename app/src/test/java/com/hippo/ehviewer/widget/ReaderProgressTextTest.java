package com.hippo.ehviewer.widget;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;

import com.hippo.ehviewer.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderProgressTextTest {
    @Test public void separatorStaysAtScreenCenterAsEitherCounterChangesWidth() {
        GalleryHeader header = new GalleryHeader(RuntimeEnvironment.getApplication(), null);
        StrokeTextView progress = new StrokeTextView(header.getContext());
        progress.setId(R.id.progress);
        header.addView(progress, new ViewGroup.MarginLayoutParams(-2, -2));
        View battery = new View(header.getContext()); battery.setId(R.id.battery);
        header.addView(battery, new ViewGroup.MarginLayoutParams(20, 20));
        View clock = new View(header.getContext()); clock.setId(R.id.clock);
        header.addView(clock, new ViewGroup.MarginLayoutParams(20, 20));
        ReflectionHelpers.callInstanceMethod(header, "onFinishInflate");

        for (int width : new int[]{360, 721}) {
            for (String text : new String[]{"更新 1/12 · p20/144", "更新 12/12 · p144/144",
                    "Updates 1/1234 · p9/9999", "更新 1/2 · p99999/99999"}) {
                progress.setText(ReaderProgressText.centerSeparator(text));
                header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST));
                header.layout(0, 0, width, header.getMeasuredHeight());
                Layout layout = progress.getLayout();
                int separator = text.indexOf('·');
                float center = progress.getLeft() + progress.getTotalPaddingLeft()
                        + (layout.getPrimaryHorizontal(separator)
                        + layout.getPrimaryHorizontal(separator + 1)) / 2;
                assertEquals(text, width / 2f, center, 1f);
                assertEquals(text, progress.getText().toString());
                // Exercise both outline and fill rendering with the same spans.
                header.draw(new Canvas(Bitmap.createBitmap(width, header.getMeasuredHeight(),
                        Bitmap.Config.ARGB_8888)));
            }
        }
    }
}
