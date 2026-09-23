package com.hippo.lib.glgallery;

import android.app.Application;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class GalleryViewAnimatedControlAreaTest {
    @Test
    public void bottomPageAreasGainDoubleTapOnlyWhileAnimationControlsAreActive() {
        GalleryView view = new GalleryView.Builder(RuntimeEnvironment.getApplication(),
                new GalleryView.Adapter() {
                    @Override public void onBind(GalleryPageView page, int index) {}
                    @Override public void onUnbind(GalleryPageView page, int index) {}
                    @Override public String getError() { return null; }
                    @Override public int size() { return 0; }
                }).build();
        view.bounds().set(0, 0, 1000, 900);
        ((Rect) ReflectionHelpers.getField(view, "mLeftArea")).set(0, 0, 360, 900);
        ((Rect) ReflectionHelpers.getField(view, "mRightArea")).set(640, 0, 1000, 900);
        view.setPageAreaDoubleTapEnabled(false); // Quick page turning is on.

        assertFalse(view.isDoubleTapRegion(100, 800));
        assertFalse(view.isDoubleTapRegion(900, 800));
        view.setAnimatedPageControlAreasEnabled(true);
        assertTrue(view.isDoubleTapRegion(100, 600));
        assertTrue(view.isDoubleTapRegion(900, 800));
        assertFalse(view.isDoubleTapRegion(100, 599));

        GestureRecognizer recognizer = ReflectionHelpers.getField(view, "mGestureRecognizer");
        assertDoubleTapSelection(recognizer, 100, 800, true);
        view.setAnimatedPageControlAreasEnabled(false);
        assertDoubleTapSelection(recognizer, 100, 800, false);
    }

    private static void assertDoubleTapSelection(GestureRecognizer recognizer, float x,
                                                 float y, boolean expected) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0);
        try {
            recognizer.onTouchEvent(down);
            boolean actual = ReflectionHelpers.getField(recognizer, "mCurrentGestureUsesDoubleTap");
            if (expected) assertTrue(actual);
            else assertFalse(actual);
            recognizer.onTouchEvent(cancel);
        } finally {
            down.recycle();
            cancel.recycle();
        }
    }
}
