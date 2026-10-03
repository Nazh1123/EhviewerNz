package com.hippo.lib.glgallery;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReaderKeyGestureTest {
    private GalleryView view;
    private int[] normal;

    @Before public void setup() {
        view = new GalleryView.Builder(RuntimeEnvironment.getApplication(), new GalleryView.Adapter() {
            @Override public void onBind(GalleryPageView page, int index) { }
            @Override public void onUnbind(GalleryPageView page, int index) { }
            @Override public String getError() { return null; }
            @Override public int size() { return 5; }
        }).build();
        view.bounds().set(0, 0, 1000, 1000);
        ((Rect) ReflectionHelpers.getField(view, "mLeftArea")).set(0, 0, 360, 1000);
        ((Rect) ReflectionHelpers.getField(view, "mRightArea")).set(640, 0, 1000, 1000);
        normal = new int[21];
        Arrays.fill(normal, ReaderKeyMap.LEGACY);
    }

    @Test public void animationTakesOverDoubleTapOnlyInsideItsPercentage() {
        normal[ReaderKeyMap.RIGHT_BOTTOM * 3 + 1] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(normal, 30));
        view.setPageAreaDoubleTapEnabled(true);
        view.setAnimatedPageControlAreasEnabled(true);
        assertTrue(view.isDoubleTapRegion(900, 900));
        assertFalse(view.isDoubleTapRegion(900, 699));
        assertTrue(view.isDoubleTapRegion(900, 700));
        assertTrue(view.isDoubleTapRegion(900, 100));
        assertFalse(view.isSameTapRegion(900, 499, 900, 500));
        assertFalse(view.isSameTapRegion(900, 699, 900, 700));
    }

    @Test public void changingAnimationPercentageUpdatesItsExactBoundary() {
        normal[ReaderKeyMap.RIGHT_BOTTOM * 3 + 1] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(normal, 40));
        view.setPageAreaDoubleTapEnabled(false);
        assertFalse(view.isDoubleTapRegion(900, 600));
        view.setAnimatedPageControlAreasEnabled(true);
        assertTrue(view.isDoubleTapRegion(900, 600));
        assertFalse(view.isDoubleTapRegion(900, 599));
        assertFalse(view.isDoubleTapRegion(900, 499));
        view.setAnimatedPageControlAreasEnabled(false);
        assertFalse(view.isDoubleTapRegion(900, 600));
        view.setReaderKeyMap(new ReaderKeyMap(normal, 0));
        view.setAnimatedPageControlAreasEnabled(true);
        assertFalse(view.isDoubleTapRegion(900, 999));
    }

    @Test public void nearbyTapsAcrossMidlineRemainTwoSingles() {
        List<String> callbacks = new ArrayList<>();
        GestureRecognizer.Listener listener = (GestureRecognizer.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{GestureRecognizer.Listener.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isDoubleTapRegion")) return true;
                    if (method.getName().equals("isSameTapRegion")) {
                        return ReaderKeyMap.region((float) args[0], (float) args[1], 1000, 1000)
                                == ReaderKeyMap.region((float) args[2], (float) args[3], 1000, 1000);
                    }
                    if (method.getName().equals("onSingleTapConfirmed") || method.getName().equals("onDoubleTapConfirmed")) {
                        callbacks.add(method.getName());
                    }
                    return method.getReturnType() == boolean.class ? true : null;
                });
        GestureRecognizer recognizer = new GestureRecognizer(RuntimeEnvironment.getApplication(), listener);
        tap(recognizer, 900, 499);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(70));
        tap(recognizer, 900, 501);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(Arrays.asList("onSingleTapConfirmed", "onSingleTapConfirmed"), callbacks);
    }

    @Test public void splitAreasDispatchIndependentActionsAndClampPageLimits() {
        TestLayout layout = new TestLayout(view);
        ReflectionHelpers.setField(view, "mLayoutManager", layout);
        normal[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        normal[ReaderKeyMap.RIGHT_BOTTOM * 3] = ReaderKeyMap.PREVIOUS;
        view.setReaderKeyMap(new ReaderKeyMap(normal, 30));
        single(900, 100);
        assertEquals(1, layout.index);
        single(900, 700);
        assertEquals(0, layout.index);
        single(900, 700);
        assertEquals(0, layout.index);
        layout.index = 4;
        single(900, 100);
        assertEquals(4, layout.index);
    }

    @Test public void currentReadingDirectionSelectsKeysWithoutReloadingTheProfile() {
        TestLayout layout = new TestLayout(view);
        ReflectionHelpers.setField(view, "mLayoutManager", layout);
        int[][] directions = new int[3][21];
        for (int[] keys : directions) Arrays.fill(keys, ReaderKeyMap.LEGACY);
        directions[GalleryView.LAYOUT_LEFT_TO_RIGHT][ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        directions[GalleryView.LAYOUT_RIGHT_TO_LEFT][ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.PREVIOUS;
        directions[GalleryView.LAYOUT_TOP_TO_BOTTOM][ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NONE;
        directions[GalleryView.LAYOUT_LEFT_TO_RIGHT][ReaderKeyMap.RIGHT_TOP * 3 + 1] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(directions, 30));
        view.setPageAreaDoubleTapEnabled(true);
        ReflectionHelpers.setField(view, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
        single(900, 100);
        assertEquals(1, layout.index);
        assertFalse(view.isDoubleTapRegion(900, 100));
        ReflectionHelpers.setField(view, "mLayoutMode", GalleryView.LAYOUT_RIGHT_TO_LEFT);
        single(900, 100);
        assertEquals(0, layout.index);
        assertTrue(view.isDoubleTapRegion(900, 100));
        ReflectionHelpers.setField(view, "mLayoutMode", GalleryView.LAYOUT_TOP_TO_BOTTOM);
        single(900, 100);
        assertEquals(0, layout.index);
    }

    private void single(float x, float y) {
        ReflectionHelpers.callInstanceMethod(view, "onSingleTapConfirmedInternal",
                ReflectionHelpers.ClassParameter.from(float.class, x),
                ReflectionHelpers.ClassParameter.from(float.class, y));
    }

    private static final class TestLayout extends GalleryView.LayoutManager {
        int index;
        TestLayout(GalleryView view) { super(view); }
        @Override public void onAttach(GalleryView.Adapter adapter) { }
        @Override public GalleryView.Adapter onDetach() { return null; }
        @Override public void onFill() { }
        @Override public void onDown() { }
        @Override public void onUp() { }
        @Override public void onDoubleTapConfirmed(float x, float y) { }
        @Override public void onLongPress(float x, float y) { }
        @Override public void onScroll(float dx, float dy, float tx, float ty, float x, float y) { }
        @Override public void onFling(float vx, float vy) { }
        @Override public boolean canScale() { return true; }
        @Override public void onScale(float x, float y, float scale) { }
        @Override public boolean onUpdateAnimation(long time) { return false; }
        @Override public void onDataChanged() { }
        @Override public void onPageLeft() { index--; }
        @Override public void onPageRight() { index++; }
        @Override public boolean isTapOrPressEnable() { return true; }
        @Override public GalleryPageView findPageByIndex(int index) { return null; }
        @Override public int getCurrentIndex() { return index; }
        @Override public void setCurrentIndex(int value) { index = value; }
        @Override public int getIndexUnder(float x, float y) { return index; }
        @Override int getInternalCurrentIndex() { return index; }
    }

    private void tap(GestureRecognizer recognizer, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0);
        recognizer.onTouchEvent(down); recognizer.onTouchEvent(up);
        down.recycle(); up.recycle();
    }
}
