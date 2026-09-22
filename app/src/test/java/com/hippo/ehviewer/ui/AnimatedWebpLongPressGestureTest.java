package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class AnimatedWebpLongPressGestureTest {
    private GalleryActivity activity;
    private AnimatedWebpLongPressSpeed selection;
    private TextView notice;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("long-press-test", Context.MODE_PRIVATE));
        // Attach without creating the gallery provider or a native GL surface.
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        notice = new TextView(activity);
        selection = new AnimatedWebpLongPressSpeed(2.2f, 1f);
        selection.begin(100, SystemClock.uptimeMillis());
        ReflectionHelpers.setField(activity, "mAnimatedWebpLongPressNotice", notice);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLongPressSpeedSelection", selection);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLongPressActive", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLongPressCaptured", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchCandidate", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchDownX", 100f);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchDownY", 900f);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpLongPressNotice");
    }

    @After
    public void tearDown() {
        if (activity != null) {
            Handler handler = ReflectionHelpers.getField(activity, "mAnimatedWebpHandler");
            handler.removeCallbacksAndMessages(null);
            ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor"))
                    .shutdownNow();
            ((ExecutorService) ReflectionHelpers.getField(activity, "transferService"))
                    .shutdownNow();
        }
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    @Test
    public void horizontalMovementAdjustsSpeedWithoutStartingScrub() {
        dispatch(MotionEvent.ACTION_MOVE, 132);
        assertEquals(2.5f, selection.getSpeed(), 0f);
        assertEquals(activity.getString(R.string.animated_webp_long_press_speed_hint, "2.5"),
                notice.getText().toString());
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpTouchDragging"));
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
    }

    @Test
    public void initialTimeoutCollapsesNoticeAndStillConsumesHorizontalMovement() {
        dispatch(MotionEvent.ACTION_MOVE, 131);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals("2.2x", notice.getText().toString());
        dispatch(MotionEvent.ACTION_MOVE, 400);
        assertEquals(2.2f, selection.getSpeed(), 0f);
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpTouchDragging"));
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
    }

    @Test
    public void idleTimeoutLocksAndReleaseHidesNotice() {
        dispatch(MotionEvent.ACTION_MOVE, 132);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        assertEquals("2.5x", notice.getText().toString());
        dispatch(MotionEvent.ACTION_MOVE, 196);
        assertEquals(2.5f, selection.getSpeed(), 0f);
        activity.onLongPressSliderAreaReleased();
        assertEquals(View.GONE, notice.getVisibility());
        // A texture/lifecycle release must not allow this same touch to start seeking.
        dispatch(MotionEvent.ACTION_MOVE, 260);
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpTouchDragging"));
    }

    @Test
    public void secondPointerCancelsSpeedControlAndConsumesRemainingMoves() {
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 100);
        assertEquals(View.GONE, notice.getVisibility());
        dispatch(MotionEvent.ACTION_MOVE, 260);
        assertEquals(2.2f, selection.getSpeed(), 0f);
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpTouchDragging"));
    }

    private void dispatch(int action, float x) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, 900, 0);
        try {
            assertTrue(activity.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }
}
