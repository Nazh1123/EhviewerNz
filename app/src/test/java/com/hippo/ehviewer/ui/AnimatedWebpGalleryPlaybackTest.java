package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.hippo.ehviewer.Settings;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glview.image.ImageTexture;
import com.hippo.lib.glview.image.ImageWrapper;
import com.hippo.lib.image.Image;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AnimatedWebpGalleryPlaybackTest {
    private GalleryActivity activity;
    private final List<ImageTexture> textures = new ArrayList<>();

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("gallery-playback-test", Context.MODE_PRIVATE));
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        ReflectionHelpers.setField(activity, "mAnimatedWebpLifecycleResumed", true);
    }

    @After
    public void tearDown() {
        Handler handler = ReflectionHelpers.getField(activity, "mAnimatedWebpHandler");
        handler.removeCallbacksAndMessages(null);
        for (ImageTexture texture : textures) texture.recycle();
        ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor"))
                .shutdownNow();
        ((ExecutorService) ReflectionHelpers.getField(activity, "transferService"))
                .shutdownNow();
        Settings.putAnimatedWebpAutoAdvance(false);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    @Test
    public void pauseAndPermanentSpeedFollowPagesIncludingNewlyLoadedOne() {
        ImageTexture first = texture();
        ImageTexture neighbor = texture();
        activity.onAnimatedPageVisibility(first, 1f);
        activity.onAnimatedPageVisibility(neighbor, 0f);
        assertTrue(first.isPlaybackPlaying());
        assertFalse(neighbor.isPlaybackPlaying());

        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", first);
        ReflectionHelpers.callInstanceMethod(activity, "cycleAnimatedWebpSpeed");
        assertEquals(1.5f, first.getPlaybackSpeed(), 0f);
        assertEquals(1.5f, neighbor.getPlaybackSpeed(), 0f);

        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", first);
        ReflectionHelpers.callInstanceMethod(activity, "toggleAnimatedWebpPlayback");
        assertFalse(first.isPlaybackPlaying());
        activity.onAnimatedPageVisibility(neighbor, 1f);
        assertFalse(neighbor.isPlaybackPlaying());

        ImageTexture later = texture();
        activity.onAnimatedPageVisibility(later, 1f);
        assertEquals(1.5f, later.getPlaybackSpeed(), 0f);
        assertFalse(later.isPlaybackPlaying());

        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", later);
        ReflectionHelpers.callInstanceMethod(activity, "toggleAnimatedWebpPlayback");
        assertTrue(later.isPlaybackPlaying());
        assertTrue(neighbor.isPlaybackPlaying());
    }

    @Test
    public void bothPagesUseEnteringAndLeavingThresholdsWhenSwipeReverses() {
        ImageTexture oldPage = texture();
        ImageTexture newPage = texture();
        activity.onAnimatedPageVisibility(oldPage, 1f);
        activity.onAnimatedPageVisibility(newPage, 0f);
        activity.onAnimatedPageVisibility(oldPage, 0.74f);
        activity.onAnimatedPageVisibility(newPage, 0.26f);
        assertFalse(oldPage.isPlaybackPlaying());
        assertFalse(newPage.isPlaybackPlaying());

        activity.onAnimatedPageVisibility(oldPage, 0.49f);
        activity.onAnimatedPageVisibility(newPage, 0.51f);
        assertFalse(oldPage.isPlaybackPlaying());
        assertTrue(newPage.isPlaybackPlaying());

        // A short reversal must not immediately flip either page's playback.
        activity.onAnimatedPageVisibility(oldPage, 0.53f);
        activity.onAnimatedPageVisibility(newPage, 0.47f);
        assertFalse(oldPage.isPlaybackPlaying());
        assertTrue(newPage.isPlaybackPlaying());

        // Reversing by more than 10% of the screen width commits both changes.
        activity.onAnimatedPageVisibility(oldPage, 0.6f);
        activity.onAnimatedPageVisibility(newPage, 0.4f);
        assertTrue(oldPage.isPlaybackPlaying());
        assertFalse(newPage.isPlaybackPlaying());

        activity.onAnimatedPageVisibility(oldPage, 0.76f);
        assertTrue(oldPage.isPlaybackPlaying());
        activity.onAnimatedPageVisibility(oldPage, 0.75f);
        assertTrue(oldPage.isPlaybackPlaying());
        activity.onAnimatedPageVisibility(oldPage, 0.65f);
        assertFalse(oldPage.isPlaybackPlaying());
    }

    @Test
    public void lifecyclePauseDoesNotChangeGalleryPlayChoice() {
        ImageTexture page = texture();
        activity.onAnimatedPageVisibility(page, 1f);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLifecycleResumed", false);
        ReflectionHelpers.callInstanceMethod(activity, "updateAllAnimatedPagePlayback");
        assertFalse(page.isPlaybackPlaying());
        ReflectionHelpers.setField(activity, "mAnimatedWebpLifecycleResumed", true);
        ReflectionHelpers.callInstanceMethod(activity, "updateAllAnimatedPagePlayback");
        assertTrue(page.isPlaybackPlaying());
    }

    @Test
    public void longPressTemporarilyOverridesButDoesNotReplaceGalleryChoice() {
        ImageTexture page = texture();
        activity.onAnimatedPageVisibility(page, 1f);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", page);
        ReflectionHelpers.callInstanceMethod(activity, "toggleAnimatedWebpPlayback");
        assertFalse(page.isPlaybackPlaying());

        ReflectionHelpers.setField(activity, "mAnimatedWebpLongPressActive", true);
        ReflectionHelpers.callInstanceMethod(activity,
                "applyAnimatedWebpLongPressPlayback",
                ClassParameter.from(ImageTexture.class, page),
                ClassParameter.from(float.class, 2.5f));
        assertTrue(page.isPlaybackPlaying());
        assertEquals(2.5f, page.getPlaybackSpeed(), 0f);

        activity.onLongPressSliderAreaReleased();
        assertFalse(page.isPlaybackPlaying());
        assertEquals(1f, page.getPlaybackSpeed(), 0f);

        ImageTexture next = texture();
        activity.onAnimatedPageVisibility(next, 1f);
        assertFalse(next.isPlaybackPlaying());
        assertEquals(1f, next.getPlaybackSpeed(), 0f);
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    public void completedCycleSkipsAutoAdvanceDuringPageSwipe() {
        GalleryView view = new GalleryView.Builder(RuntimeEnvironment.getApplication(),
                new GalleryView.Adapter() {
                    @Override public void onBind(GalleryPageView page, int index) {}
                    @Override public void onUnbind(GalleryPageView page, int index) {}
                    @Override public String getError() { return null; }
                    @Override public int size() { return 2; }
                }).build();
        ImageTexture texture = texture();
        ReflectionHelpers.setField(activity, "mGalleryView", view);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 0);
        ReflectionHelpers.setField(activity, "mSize", 2);
        Settings.putAnimatedWebpAutoAdvance(true);
        List<Integer> methods = ReflectionHelpers.getField(view, "mMethodList");
        List<Object[]> args = ReflectionHelpers.getField(view, "mArgsList");

        activity.onPlaybackCycleCompleted(texture);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, methods.size());
        assertEquals(1, args.get(0)[0]);
        methods.clear();
        args.clear();

        // The swipe ended while the completion callback was waiting in the queue.
        ReflectionHelpers.setField(view, "mPageSwipeInProgress", true);
        activity.onPlaybackCycleCompleted(texture);
        ReflectionHelpers.setField(view, "mPageSwipeInProgress", false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(methods.isEmpty());

        // The swipe began after the callback was queued.
        activity.onPlaybackCycleCompleted(texture);
        ReflectionHelpers.setField(view, "mPageSwipeInProgress", true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(methods.isEmpty());
    }

    private ImageTexture texture() {
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        Image image = Image.create(bitmap);
        ImageWrapper wrapper = new ImageWrapper(image);
        assertTrue(wrapper.obtain());
        ImageTexture texture = new ImageTexture(wrapper);
        // Playback control only exists for native animated images. The bitmap
        // supplies a harmless backing image for this state-only test.
        ReflectionHelpers.setField(texture, "mControllableAnimation", true);
        textures.add(texture);
        return texture;
    }
}
