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
@Config(application = Application.class, sdk = 34, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GallerySectionDividerTest {
    @Test public void rendersThreeCompletePixelRowsWithFourPixelGapsAndRoundCaps() {
        View divider = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme)).inflate(R.layout.item_popular_history_divider, null);
        divider.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(11, divider.getMeasuredHeight());
        divider.layout(0, 0, 200, 11);
        Bitmap bitmap = Bitmap.createBitmap(200, 11, Bitmap.Config.ARGB_8888);
        divider.draw(new Canvas(bitmap));
        for (int y = 0; y < 11; y++) {
            if (y >= 4 && y < 7) assertTrue(Color.alpha(bitmap.getPixel(100, y)) >= 128);
            else assertEquals(0, Color.alpha(bitmap.getPixel(100, y)));
        }
        // Antialiasing may cover the immediately adjacent pixel at a rounded endpoint.
        assertEquals(0, Color.alpha(bitmap.getPixel(28, 5)));
        assertEquals(0, Color.alpha(bitmap.getPixel(171, 5)));
        assertTrue(Color.alpha(bitmap.getPixel(30, 5)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(169, 5)) > 0);
        assertTrue(Color.alpha(bitmap.getPixel(30, 4)) < Color.alpha(bitmap.getPixel(31, 4)));
    }
}
