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
    @Test public void rendersTwelveCompletePixelRowsWithFourPixelGapsAtDeviceDensity() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(4, divider.getPaddingTop());
        assertEquals(4, divider.getPaddingBottom());
        assertEquals(20, divider.getMeasuredHeight());
        divider.layout(0, 0, 200, 20);
        Bitmap bitmap = Bitmap.createBitmap(200, 20, Bitmap.Config.ARGB_8888);
        divider.draw(new Canvas(bitmap));
        for (int y = 0; y < 20; y++) {
            if (y >= 4 && y < 16) assertTrue(Color.alpha(bitmap.getPixel(100, y)) >= 128);
            else assertEquals(0, Color.alpha(bitmap.getPixel(100, y)));
        }
        // Antialiasing may cover the immediately adjacent pixel at a rounded endpoint.
        assertEquals(0, Color.alpha(bitmap.getPixel(28, 11)));
        assertEquals(0, Color.alpha(bitmap.getPixel(171, 11)));
        assertTrue(Color.alpha(bitmap.getPixel(30, 11)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(169, 11)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(30, 4)) < Color.alpha(bitmap.getPixel(38, 4)));
    }

    @Test @Config(qualifiers = "xxhdpi")
    public void strokeAndGapsScaleWithDensity() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(10, divider.getResources().getDimensionPixelSize(R.dimen.gallery_section_divider_thickness));
        assertEquals(3, divider.getPaddingTop());
        assertEquals(3, divider.getPaddingBottom());
        assertEquals(16, divider.getMeasuredHeight());
    }
}
