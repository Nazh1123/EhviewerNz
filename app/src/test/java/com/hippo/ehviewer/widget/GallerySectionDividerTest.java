package com.hippo.ehviewer.widget;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;

import com.hippo.ehviewer.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34, qualifiers = "600dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GallerySectionDividerTest {
    @Test public void rendersIdenticalLinesWithConfiguredGapAtDeviceDensity() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(11, divider.getPaddingTop());
        assertEquals(11, divider.getPaddingBottom());
        assertEquals(46, divider.getMeasuredHeight());
        divider.layout(0, 0, 200, 46);
        Bitmap bitmap = Bitmap.createBitmap(200, 46, Bitmap.Config.ARGB_8888);
        divider.draw(new Canvas(bitmap));
        for (int y = 0; y < 46; y++) {
            if ((y >= 11 && y < 20) || (y >= 26 && y < 35)) assertTrue(Color.alpha(bitmap.getPixel(100, y)) >= 128);
            else assertEquals(0, Color.alpha(bitmap.getPixel(100, y)));
        }
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 200; x++) {
                assertEquals(bitmap.getPixel(x, 11 + y), bitmap.getPixel(x, 26 + y));
            }
        }
        // Antialiasing may cover the immediately adjacent pixel at a rounded endpoint.
        assertEquals(0, Color.alpha(bitmap.getPixel(28, 15)));
        assertEquals(0, Color.alpha(bitmap.getPixel(171, 15)));
        assertTrue(Color.alpha(bitmap.getPixel(30, 15)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(169, 15)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(30, 11)) < Color.alpha(bitmap.getPixel(38, 11)));
    }

    @Test @Config(qualifiers = "xxhdpi")
    public void strokeAndGapsScaleWithDensity() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(7, divider.getResources().getDimensionPixelSize(R.dimen.gallery_section_divider_thickness));
        assertEquals(5, divider.getResources().getDimensionPixelSize(R.dimen.gallery_section_divider_line_gap));
        assertEquals(9, divider.getPaddingTop());
        assertEquals(9, divider.getPaddingBottom());
        assertEquals(37, divider.getMeasuredHeight());
    }
}
