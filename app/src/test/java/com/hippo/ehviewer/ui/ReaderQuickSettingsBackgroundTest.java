package com.hippo.ehviewer.ui;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.hippo.ehviewer.R;
import com.hippo.android.resource.AttrResources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class ReaderQuickSettingsBackgroundTest {
    @Test public void translucentStatusBarUsesTheSystemScrimInsteadOfWindowColor() {
        Window window = Robolectric.buildActivity(Activity.class).get().getWindow();
        window.setStatusBarColor(0xff123456);
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        int colorId = window.getContext().getResources().getIdentifier(
                "system_bar_background_semi_transparent", "color", "android");
        assertNotEquals(0, colorId);
        int alpha = Color.alpha(window.getContext().getResources().getColor(colorId, null));
        assertTrue(alpha > 0 && alpha < 255);
        assertEquals(alpha, GalleryActivity.getStatusBarBackgroundAlpha(window));
    }

    @Test public void regularStatusBarUsesItsWindowColorAndLegacyBarsAreOpaque() {
        Window window = Robolectric.buildActivity(Activity.class).get().getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(0x60123456);
        assertEquals(0x60, GalleryActivity.getStatusBarBackgroundAlpha(window));
        window.setStatusBarColor(Color.TRANSPARENT);
        assertEquals(0, GalleryActivity.getStatusBarBackgroundAlpha(window));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        assertEquals(255, GalleryActivity.getStatusBarBackgroundAlpha(window));
    }

    @Test public void alphaChangesUpdateViewOpacityAndKeepThemeAndIcons() {
        for (int theme : new int[]{R.style.AppTheme_Gallery, R.style.AppTheme_Gallery_Black}) {
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme);
            int color = AttrResources.getAttrColor(context, R.attr.gallerySliderBackgroundColor);
            LinearLayout panel = new LinearLayout(context);
            panel.setBackgroundColor(color);
            panel.setPadding(8, 0, 8, 0);
            ImageButton button = new ImageButton(context);
            panel.addView(button);
            assertTrue(panel.isOpaque());

            for (int alpha : new int[]{60, 85, 0, 255, 60}) {
                GalleryActivity.setQuickSettingsBackgroundAlpha(panel, alpha);
                assertEquals(alpha, panel.getBackground().getAlpha());
                assertEquals(color & 0x00ffffff,
                        ((ColorDrawable) panel.getBackground()).getColor() & 0x00ffffff);
                assertEquals("View opacity must track the background alpha", alpha == 255, panel.isOpaque());
                assertEquals(1f, panel.getAlpha(), 0f);
                assertEquals(1f, button.getAlpha(), 0f);
                assertEquals(255, button.getImageAlpha());
                assertEquals(8, panel.getPaddingLeft());
            }
        }
    }
}
