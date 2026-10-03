package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import com.hippo.ehviewer.ReaderKeyProfiles;
import com.hippo.ehviewer.Settings;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReaderOrientationGestureTest {
    private GalleryActivity activity;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("reader-swipe-test", 0);
        prefs.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", prefs);
        Settings.putReadingDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT);
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        GalleryView gallery = new GalleryView.Builder(context, new GalleryView.Adapter() {
            @Override public void onBind(GalleryPageView page, int index) { }
            @Override public void onUnbind(GalleryPageView page, int index) { }
            @Override public String getError() { return null; }
            @Override public int size() { return 5; }
        }).setLayoutMode(GalleryView.LAYOUT_LEFT_TO_RIGHT).build();
        gallery.bounds().set(0, 0, 360, 800);
        AtomicLong imageSize = ReflectionHelpers.getField(gallery, "mCurrentImageSize");
        imageSize.set(((long) 1600 << 32) | 800);
        ReflectionHelpers.setField(activity, "mGalleryView", gallery);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchSlop", 8);
        activity.getWindow().getDecorView().layout(0, 0, 360, 800);
    }

    @After public void cleanup() {
        ((Handler) ReflectionHelpers.getField(activity, "mAnimatedWebpHandler")).removeCallbacksAndMessages(null);
        ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor")).shutdownNow();
        ((ExecutorService) ReflectionHelpers.getField(activity, "transferService")).shutdownNow();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    private void use(int direction) {
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        profiles.active().orientationSwipe = direction;
        profiles.save();
        ReflectionHelpers.callInstanceMethod(activity, "applyReaderKeyProfile");
    }

    @Test public void upwardGestureSwitchesOrientationWhenConfigured() {
        use(ReaderKeyProfiles.SWIPE_UP);
        assertFalse(event(MotionEvent.ACTION_DOWN, 400));
        assertTrue(event(MotionEvent.ACTION_MOVE, 200));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, activity.getRequestedOrientation());
    }

    @Test public void downwardGestureKeepsTheExistingDefaultBehavior() {
        use(ReaderKeyProfiles.SWIPE_DOWN);
        assertFalse(event(MotionEvent.ACTION_DOWN, 400));
        assertTrue(event(MotionEvent.ACTION_MOVE, 600));
        assertEquals(2, Settings.getScreenRotation());
    }

    @Test public void disabledAndOppositeGesturesDoNotSwitchOrientation() {
        use(ReaderKeyProfiles.SWIPE_OFF);
        event(MotionEvent.ACTION_DOWN, 400);
        assertFalse(event(MotionEvent.ACTION_MOVE, 600));
        use(ReaderKeyProfiles.SWIPE_UP);
        event(MotionEvent.ACTION_DOWN, 400);
        assertFalse(event(MotionEvent.ACTION_MOVE, 600));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.getRequestedOrientation());
    }

    @Test public void switchingProfileAppliesItsGestureAndCancelsPendingSwipe() {
        use(ReaderKeyProfiles.SWIPE_UP);
        event(MotionEvent.ACTION_DOWN, 400);
        assertTrue((Boolean) ReflectionHelpers.getField(activity, "mOrientationSwipeCandidate"));
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        ReaderKeyProfiles.Profile other = new ReaderKeyProfiles.Profile("Off");
        other.orientationSwipe = ReaderKeyProfiles.SWIPE_OFF;
        profiles.profiles.add(other); profiles.selected = 1; profiles.save();
        ReflectionHelpers.callInstanceMethod(activity, "applyReaderKeyProfile");
        assertFalse((Boolean) ReflectionHelpers.getField(activity, "mOrientationSwipeCandidate"));
        assertFalse(event(MotionEvent.ACTION_MOVE, 200));
    }

    private boolean event(int action, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 180, y, 0);
        try {
            return ReflectionHelpers.callInstanceMethod(activity, "handleOrientationSwipeGesture",
                    ReflectionHelpers.ClassParameter.from(MotionEvent.class, event));
        } finally { event.recycle(); }
    }
}
