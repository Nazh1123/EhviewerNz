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
    @Test public void rendersIdenticalLinesSeparatedByOneDpAtDeviceDensity() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(8, divider.getPaddingTop());
        assertEquals(8, divider.getPaddingBottom());
        assertEquals(32, divider.getMeasuredHeight());
        divider.layout(0, 0, 200, 32);
        Bitmap bitmap = Bitmap.createBitmap(200, 32, Bitmap.Config.ARGB_8888);
        divider.draw(new Canvas(bitmap));
        for (int y = 0; y < 32; y++) {
            if ((y >= 8 && y < 14) || (y >= 18 && y < 24)) assertTrue(Color.alpha(bitmap.getPixel(100, y)) >= 128);
            else assertEquals(0, Color.alpha(bitmap.getPixel(100, y)));
        }
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 200; x++) {
                assertEquals(bitmap.getPixel(x, 8 + y), bitmap.getPixel(x, 18 + y));
            }
        }
        // Antialiasing may cover the immediately adjacent pixel at a rounded endpoint.
        assertEquals(0, Color.alpha(bitmap.getPixel(28, 11)));
        assertEquals(0, Color.alpha(bitmap.getPixel(171, 11)));
        assertTrue(Color.alpha(bitmap.getPixel(30, 11)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(169, 11)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(30, 8)) < Color.alpha(bitmap.getPixel(38, 8)));
    }

    @Test @Config(qualifiers = "xxhdpi")
    public void strokeAndGapsScaleWithDensity() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(5, divider.getResources().getDimensionPixelSize(R.dimen.gallery_section_divider_thickness));
        assertEquals(3, divider.getResources().getDimensionPixelSize(R.dimen.gallery_section_divider_line_gap));
        assertEquals(6, divider.getPaddingTop());
        assertEquals(6, divider.getPaddingBottom());
        assertEquals(25, divider.getMeasuredHeight());
    }
}
