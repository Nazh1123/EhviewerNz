package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;

import com.hippo.ehviewer.Settings;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
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

        // Reverse the swipe before it completes: the old page enters again,
        // while the new page starts disappearing from the opposite edge.
        activity.onAnimatedPageVisibility(oldPage, 0.6f);
        activity.onAnimatedPageVisibility(newPage, 0.4f);
        assertTrue(oldPage.isPlaybackPlaying());
        assertFalse(newPage.isPlaybackPlaying());

        activity.onAnimatedPageVisibility(oldPage, 0.76f);
        assertTrue(oldPage.isPlaybackPlaying());
        activity.onAnimatedPageVisibility(oldPage, 0.75f);
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
