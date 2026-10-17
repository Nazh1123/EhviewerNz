package com.hippo.ehviewer.widget;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.ViewCompat;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "zh-rCN-mdpi")
public class TriStateSwitchTest {
    private TriStateSwitch control(int state) {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme_Main);
        TriStateSwitch control = new TriStateSwitch(context, null);
        control.setState(state);
        control.layout(0, 0, 88, 48);
        return control;
    }

    @Test public void tapsSelectEachPhysicalPositionAndOnlyNotifyOnChanges() {
        TriStateSwitch control = control(0);
        List<Integer> changes = new ArrayList<>();
        control.setOnStateChangedListener(changes::add);
        tap(control, 68);
        assertEquals(1, control.getState());
        tap(control, 44);
        assertEquals(0, control.getState());
        tap(control, 20);
        assertEquals(-1, control.getState());
        tap(control, 20);
        assertEquals(List.of(1, 0, -1), changes);
    }

    @Test public void horizontalDragsSnapOnReleaseAndDoNotChangeQueryDuringMovement() {
        TriStateSwitch control = control(0);
        List<Integer> changes = new ArrayList<>();
        control.setOnStateChangedListener(changes::add);
        touch(control, MotionEvent.ACTION_DOWN, 44, 24);
        touch(control, MotionEvent.ACTION_MOVE, 67, 24);
        assertEquals(0, control.getState());
        assertTrue(changes.isEmpty());
        touch(control, MotionEvent.ACTION_UP, 67, 24);
        assertEquals(1, control.getState());
        settle();
        touch(control, MotionEvent.ACTION_DOWN, 68, 24);
        touch(control, MotionEvent.ACTION_MOVE, 45, 24);
        touch(control, MotionEvent.ACTION_UP, 45, 24);
        assertEquals(0, control.getState());
        settle();
        touch(control, MotionEvent.ACTION_DOWN, 44, 24);
        touch(control, MotionEvent.ACTION_MOVE, 18, 24);
        touch(control, MotionEvent.ACTION_UP, 18, 24);
        assertEquals(-1, control.getState());
        assertEquals(List.of(1, 0, -1), changes);
    }

    @Test public void canceledAndVerticalGesturesKeepTheOriginalState() {
        TriStateSwitch control = control(0);
        List<Integer> changes = new ArrayList<>();
        control.setOnStateChangedListener(changes::add);
        touch(control, MotionEvent.ACTION_DOWN, 44, 24);
        touch(control, MotionEvent.ACTION_MOVE, 68, 24);
        touch(control, MotionEvent.ACTION_CANCEL, 68, 24);
        assertEquals(0, control.getState());
        settle();
        touch(control, MotionEvent.ACTION_DOWN, 44, 24);
        touch(control, MotionEvent.ACTION_MOVE, 48, 46);
        touch(control, MotionEvent.ACTION_UP, 68, 46);
        assertEquals(0, control.getState());
        assertTrue(changes.isEmpty());
    }

    @Test public void keyboardAndAccessibilityCanReachEveryState() {
        TriStateSwitch control = control(0);
        control.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(-1, control.getState());
        assertEquals(control.getContext().getString(R.string.search_preset_exclude),
                ViewCompat.getStateDescription(control));
        assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        assertEquals(0, control.getState());
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1);
        assertTrue(control.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
        assertEquals(1, control.getState());
        control.setEnabled(false);
        tap(control, 20);
        assertFalse(control.performClick());
        assertEquals(1, control.getState());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void neutralIsGrayAndActiveThumbsUseAccentWithACutoutOnlyOnTheLeft() {
        for (int theme : new int[]{R.style.AppTheme_Main, R.style.AppTheme_Main_Dark}) {
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme);
            int accent = AttrResources.getAttrColor(context, androidx.appcompat.R.attr.colorAccent);
            int background = AttrResources.getAttrColor(context, android.R.attr.colorBackground);
            int[] trackColors = new int[3];
            for (int state = -1; state <= 1; state++) {
                TriStateSwitch control = new TriStateSwitch(context, null);
                control.setState(state);
                control.layout(0, 0, 88, 48);
                Bitmap bitmap = Bitmap.createBitmap(88, 48, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(background);
                control.draw(canvas);
                int trackX = state == 1 ? 28 : 60;
                int visibleHeight = 0;
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    if (bitmap.getPixel(trackX, y) != background) visibleHeight++;
                }
                assertEquals(22, visibleHeight);
                trackColors[state + 1] = bitmap.getPixel(trackX, 24);
                int x = 44 + state * 24;
                int body = bitmap.getPixel(x, 20);
                if (state == 0) {
                    assertEquals(Color.red(body), Color.green(body));
                    assertEquals(Color.green(body), Color.blue(body));
                } else {
                    assertEquals(accent, body);
                    if (state == -1) assertNotEquals(body, bitmap.getPixel(x, 24));
                    else assertEquals(body, bitmap.getPixel(x, 24));
                }
                bitmap.recycle();
            }
            assertEquals(trackColors[0], trackColors[1]);
            assertNotEquals(trackColors[1], trackColors[2]);
        }
    }

    private static void tap(TriStateSwitch control, float x) {
        touch(control, MotionEvent.ACTION_DOWN, x, 24);
        touch(control, MotionEvent.ACTION_UP, x, 24);
    }

    private static void touch(TriStateSwitch control, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 20, action, x, y, 0);
        control.onTouchEvent(event);
        event.recycle();
    }

    private static void settle() {
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
    }
}
