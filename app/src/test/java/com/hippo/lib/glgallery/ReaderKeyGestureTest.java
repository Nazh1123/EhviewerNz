package com.hippo.lib.glgallery;

import android.app.Application;
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
        normal = new int[21];
        Arrays.fill(normal, ReaderKeyMap.LEGACY);
    }

    @Test public void animationTakesOverDoubleTapOnlyInsideItsPercentage() {
        normal[ReaderKeyMap.RIGHT_BOTTOM * 3 + 1] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(normal));
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
        view.setReaderKeyMap(mapWithBoundary(normal, .6f));
        assertFalse(view.isDoubleTapRegion(900, 600));
        view.setAnimatedPageControlAreasEnabled(true);
        assertTrue(view.isDoubleTapRegion(900, 600));
        assertFalse(view.isDoubleTapRegion(900, 599));
        assertTrue(view.isDoubleTapRegion(900, 499)); // Default upper-area action is zoom.
        view.setAnimatedPageControlAreasEnabled(false);
        assertFalse(view.isDoubleTapRegion(900, 600));
        view.setReaderKeyMap(mapWithBoundary(normal, 1f));
        view.setAnimatedPageControlAreasEnabled(true);
        assertFalse(view.isDoubleTapRegion(900, 999));
    }

    @Test public void takeoverIncludesCenterBottomAndFullLowerBandButExcludesCenterTop() {
        normal[ReaderKeyMap.CENTER_TOP * 3 + ReaderKeyMap.DOUBLE_TAP] = ReaderKeyMap.NONE;
        normal[ReaderKeyMap.CENTER_BOTTOM * 3 + ReaderKeyMap.DOUBLE_TAP] = ReaderKeyMap.NONE;
        normal[ReaderKeyMap.CENTER_MENU * 3 + ReaderKeyMap.DOUBLE_TAP] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(normal));
        view.setAnimatedPageControlAreasEnabled(true);
        assertFalse(view.isDoubleTapRegion(500, 100));
        assertTrue(view.isDoubleTapRegion(500, 500));
        assertTrue(view.isDoubleTapRegion(500, 700));
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(ReaderTouchAreas.CENTER_BOTTOM_SPLIT, .85f);
        view.setReaderKeyMap(new ReaderKeyMap(new int[][]{normal, normal, normal}, new ReaderTouchAreas[]{areas, areas, areas}));
        assertFalse(view.isDoubleTapRegion(500, 699));
        assertTrue(view.isDoubleTapRegion(500, 700));
        assertFalse(view.isSameTapRegion(500, 699, 500, 700));
        view.setReaderKeyMap(mapWithBoundary(normal, 1f));
        assertTrue(view.isDoubleTapRegion(500, 500));
        assertFalse(view.isAnimatedPageControlArea(900, 999));
        List<String> callbacks = new ArrayList<>();
        GalleryView.Listener listener = (GalleryView.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{GalleryView.Listener.class}, (proxy, method, args) -> {
                    callbacks.add(method.getName());
                    return method.getReturnType() == boolean.class ? false : null;
                });
        ReflectionHelpers.setField(view, "mListener", listener);
        gesture(ReaderKeyMap.DOUBLE_TAP, 500, 500);
        assertEquals(Arrays.asList("onDoubleTapSliderArea"), callbacks);
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
        GalleryView.LayoutManager layout = attachLayout();
        normal[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        normal[ReaderKeyMap.RIGHT_BOTTOM * 3] = ReaderKeyMap.PREVIOUS;
        view.setReaderKeyMap(new ReaderKeyMap(normal));
        single(900, 100);
        assertEquals(1, layout.getInternalCurrentIndex());
        single(900, 700);
        assertEquals(0, layout.getInternalCurrentIndex());
        single(900, 700);
        assertEquals(0, layout.getInternalCurrentIndex());
        layout.setCurrentIndex(4);
        single(900, 100);
        assertEquals(4, layout.getInternalCurrentIndex());
    }

    @Test public void currentReadingDirectionSelectsKeysWithoutReloadingTheProfile() {
        GalleryView.LayoutManager layout = attachLayout();
        int[][] directions = new int[3][21];
        for (int[] keys : directions) Arrays.fill(keys, ReaderKeyMap.LEGACY);
        directions[GalleryView.LAYOUT_LEFT_TO_RIGHT][ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        directions[GalleryView.LAYOUT_RIGHT_TO_LEFT][ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.PREVIOUS;
        directions[GalleryView.LAYOUT_TOP_TO_BOTTOM][ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NONE;
        directions[GalleryView.LAYOUT_LEFT_TO_RIGHT][ReaderKeyMap.RIGHT_TOP * 3 + 1] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(directions));
        layout = setDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT);
        single(900, 100);
        assertEquals(1, layout.getInternalCurrentIndex());
        assertFalse(view.isDoubleTapRegion(900, 100));
        layout = setDirection(GalleryView.LAYOUT_RIGHT_TO_LEFT);
        single(900, 100);
        assertEquals(0, layout.getInternalCurrentIndex());
        assertTrue(view.isDoubleTapRegion(900, 100));
        layout = setDirection(GalleryView.LAYOUT_TOP_TO_BOTTOM);
        single(900, 100);
        assertEquals(0, layout.getInternalCurrentIndex());
    }

    @Test public void animationModeSelectsIndependentKeysOutsideTakeoverAndOnStaticPages() {
        GalleryView.LayoutManager layout = attachLayout();
        int[] animated = normal.clone();
        normal[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        animated[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.PREVIOUS;
        animated[ReaderKeyMap.RIGHT_TOP * 3 + 1] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(new int[][]{normal, normal, normal},
                new int[][]{animated, animated, animated}, null));
        layout.setCurrentIndex(2);
        single(900, 100);
        assertEquals(3, layout.getInternalCurrentIndex());
        view.setAnimatedReaderKeysEnabled(true);
        single(900, 100);
        assertEquals(2, layout.getInternalCurrentIndex());
        assertFalse(view.isDoubleTapRegion(900, 100));
        view.setAnimatedPageControlAreasEnabled(true);
        assertFalse(view.isDoubleTapRegion(900, 100));
        assertTrue(view.isDoubleTapRegion(900, 900));
        view.setAnimatedPageControlAreasEnabled(false);
        view.setAnimatedReaderKeysEnabled(false);
        single(900, 100);
        assertEquals(3, layout.getInternalCurrentIndex());
        assertTrue(view.isDoubleTapRegion(900, 100));
    }

    @Test public void defaultActionsAndGestureEligibilityFollowResizedAreas() {
        attachLayout();
        List<String> callbacks = new ArrayList<>();
        GalleryView.Listener listener = (GalleryView.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{GalleryView.Listener.class}, (proxy, method, args) -> {
                    callbacks.add(method.getName());
                    return method.getReturnType() == boolean.class ? false : null;
                });
        ReflectionHelpers.setField(view, "mListener", listener);
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(0, .2f).withPosition(1, .8f)
                .withPosition(4, .25f).withPosition(5, .65f);
        normal[ReaderKeyMap.LEFT_TOP * 3 + ReaderKeyMap.DOUBLE_TAP] = ReaderKeyMap.NONE;
        view.setReaderKeyMap(new ReaderKeyMap(new int[][]{normal, normal, normal}, new ReaderTouchAreas[]{areas, areas, areas}));
        single(250, 300);
        assertEquals(Arrays.asList("onTapMenuArea"), callbacks);
        callbacks.clear(); single(500, 200);
        assertEquals(Arrays.asList("onTapSliderArea"), callbacks);
        callbacks.clear(); single(500, 600);
        assertEquals(Arrays.asList("onTapMenuArea"), callbacks);
        assertFalse(view.isDoubleTapRegion(150, 200));
        assertTrue(view.isDoubleTapRegion(250, 200));
        assertFalse(view.isSameTapRegion(199, 200, 200, 200));
        assertFalse(view.isSameTapRegion(500, 649, 500, 650));
        assertTrue(view.isSameTapRegion(500, 500, 500, 600));
        view.bounds().set(0, 0, 2000, 500);
        callbacks.clear(); single(500, 150);
        assertEquals(Arrays.asList("onTapMenuArea"), callbacks);
    }

    @Test public void customPageActionsWorkInEveryRegionGestureAndReadingDirection() {
        attachLayout();
        for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
            GalleryView.LayoutManager layout = setDirection(direction);
            for (int region = 0; region < ReaderKeyMap.REGION_COUNT; region++) {
                float[] bounds = ReaderKeyMap.bounds(region);
                float x = (bounds[0] + bounds[2]) * 500;
                float y = (bounds[1] + bounds[3]) * 500;
                for (int gesture = 0; gesture < ReaderKeyMap.GESTURE_COUNT; gesture++) {
                    String context = "direction=" + direction + ", region=" + region + ", gesture=" + gesture;
                    Arrays.fill(normal, ReaderKeyMap.LEGACY);
                    int key = region * ReaderKeyMap.GESTURE_COUNT + gesture;
                    normal[key] = ReaderKeyMap.PREVIOUS;
                    view.setReaderKeyMap(new ReaderKeyMap(normal));
                    layout.setCurrentIndex(2);
                    gesture(gesture, x, y);
                    assertEquals(context, 1, layout.getInternalCurrentIndex());
                    layout.setCurrentIndex(0);
                    gesture(gesture, x, y);
                    assertEquals(context, 0, layout.getInternalCurrentIndex());

                    normal[key] = ReaderKeyMap.NEXT;
                    view.setReaderKeyMap(new ReaderKeyMap(normal));
                    layout.setCurrentIndex(2);
                    gesture(gesture, x, y);
                    assertEquals(context, 3, layout.getInternalCurrentIndex());
                    layout.setCurrentIndex(4);
                    gesture(gesture, x, y);
                    assertEquals(context, 4, layout.getInternalCurrentIndex());
                }
            }
        }
    }

    private ReaderKeyMap mapWithBoundary(int[] keys, float position) {
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(ReaderTouchAreas.ANIMATED_SPLIT, position);
        return new ReaderKeyMap(new int[][]{keys, keys, keys}, new ReaderTouchAreas[]{areas, areas, areas});
    }

    private GalleryView.LayoutManager attachLayout() {
        ReflectionHelpers.callInstanceMethod(view, "attachLayoutManager");
        // The real attach path transfers the adapter to the layout manager.
        assertNull(ReflectionHelpers.getField(view, "mAdapter"));
        return ReflectionHelpers.getField(view, "mLayoutManager");
    }

    private GalleryView.LayoutManager setDirection(int direction) {
        ReflectionHelpers.callInstanceMethod(view, "setLayoutModeInternal",
                ReflectionHelpers.ClassParameter.from(int.class, direction));
        return ReflectionHelpers.getField(view, "mLayoutManager");
    }

    private void gesture(int gesture, float x, float y) {
        String method = switch (gesture) {
            case ReaderKeyMap.TAP -> "onSingleTapConfirmedInternal";
            case ReaderKeyMap.DOUBLE_TAP -> "onDoubleTapConfirmedInternal";
            case ReaderKeyMap.LONG_PRESS -> "onLongPressInternal";
            default -> throw new IllegalArgumentException("Invalid gesture");
        };
        ReflectionHelpers.callInstanceMethod(view, method,
                ReflectionHelpers.ClassParameter.from(float.class, x),
                ReflectionHelpers.ClassParameter.from(float.class, y));
    }

    private void single(float x, float y) {
        ReflectionHelpers.callInstanceMethod(view, "onSingleTapConfirmedInternal",
                ReflectionHelpers.ClassParameter.from(float.class, x),
                ReflectionHelpers.ClassParameter.from(float.class, y));
    }

    private void tap(GestureRecognizer recognizer, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0);
        recognizer.onTouchEvent(down); recognizer.onTouchEvent(up);
        down.recycle(); up.recycle();
    }
}
