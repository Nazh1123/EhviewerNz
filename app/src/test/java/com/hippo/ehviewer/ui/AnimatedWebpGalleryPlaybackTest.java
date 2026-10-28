package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.SeekBar;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.widget.TouchThroughSeekBar;
import com.hippo.ehviewer.ReaderKeyProfiles;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.ReaderTouchAreas;
import com.hippo.lib.glview.view.GLRootView;
import android.view.MotionEvent;
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
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.time.Duration;

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
    public void secondPointerEndsScrubAndResumesPlayback() {
        assertSecondPointerEndsScrub(true);
    }

    @Test
    public void secondPointerEndsScrubWithoutResumingUserPausedPlayback() {
        assertSecondPointerEndsScrub(false);
    }

    private void assertSecondPointerEndsScrub(boolean playing) {
        activity.getWindow().getDecorView().layout(0, 0, 1000, 1000);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchSlop", 8);
        ReflectionHelpers.setField(activity, "mAnimatedWebpGalleryPlaying", playing);
        ImageTexture page = texture();
        ReflectionHelpers.setField(page, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(page, "mPlaybackFramePosition", 500);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", page);
        activity.onAnimatedPageVisibility(page, 1f);
        Settings.putAnimatedWebpAllowSeek(true);

        // DOWN has already selected the animation takeover area.
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchCandidate", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchDownX", 100f);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchDownY", 900f);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchStartPosition", 500);
        scrubTouch(MotionEvent.ACTION_MOVE, 200, 1);
        assertTrue(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
        assertFalse(page.isPlaybackPlaying());
        assertEquals(700, (int) ReflectionHelpers.getField(activity,
                "mAnimatedWebpRequestedPosition"));

        scrubTouch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                200, 2);
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
        assertEquals(playing, page.isPlaybackPlaying());
        assertEquals(700, (int) ReflectionHelpers.getField(page, "mPendingSeek"));
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpTouchCandidate"));
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpTouchDragging"));

        scrubTouch(MotionEvent.ACTION_MOVE, 350, 2);
        scrubTouch(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                350, 2);
        scrubTouch(MotionEvent.ACTION_UP, 350, 1);
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
        assertEquals(playing, page.isPlaybackPlaying());
        assertEquals(700, (int) ReflectionHelpers.getField(page, "mPendingSeek"));
    }

    private void scrubTouch(int action, float x, int pointerCount) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = x + i * 100;
            coordinates[i].y = 900;
        }
        MotionEvent event = MotionEvent.obtain(0, 100, action, pointerCount,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0);
        try {
            ReflectionHelpers.callInstanceMethod(activity, "handleAnimatedWebpScrubGesture",
                    ClassParameter.from(MotionEvent.class, event));
        } finally {
            event.recycle();
        }
    }

    @Test
    public void unitLongPressSpeedFallsBackToPageMenu() {
        assertAnimatedLongPress(1f, ReaderKeyMap.LEGACY,
                List.of("onLongPressSliderArea", "onLongPressPage"));
    }

    @Test
    public void unitLongPressSpeedFallsBackToConfiguredAction() {
        assertAnimatedLongPress(1f, ReaderKeyMap.MENU,
                List.of("onLongPressSliderArea", "onTapMenuArea"));
    }

    @Test
    public void speedLongPressStillConsumesGestureAndReleasesOnce() {
        assertAnimatedLongPress(2f, ReaderKeyMap.MENU,
                List.of("onLongPressSliderArea"));
    }

    private void assertAnimatedLongPress(float speed, int action, List<String> expected) {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        Settings.putAnimatedWebpLongPressSpeed(speed);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLongPressTouchDown", true);
        ImageTexture texture = texture();
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        activity.onAnimatedPageVisibility(texture, 1f);
        GalleryView view = new GalleryView.Builder(RuntimeEnvironment.getApplication(),
                new GalleryView.Adapter() {
                    @Override public void onBind(GalleryPageView page, int index) {}
                    @Override public void onUnbind(GalleryPageView page, int index) {}
                    @Override public String getError() { return null; }
                    @Override public int size() { return 1; }
                }).build();
        view.bounds().set(0, 0, 1000, 1000);
        ReflectionHelpers.callInstanceMethod(view, "attachLayoutManager");
        Object layout = ReflectionHelpers.getField(view, "mLayoutManager");
        GalleryPageView page = new GalleryPageView(null, 0, 0, 0, 0, 0);
        ReflectionHelpers.setField(page, "mIndex", 0);
        page.bounds().set(0, 0, 1000, 1000);
        page.setImage(texture);
        ReflectionHelpers.setField(layout, "mCurrent", page);
        int[] keys = new int[ReaderKeyMap.REGION_COUNT * ReaderKeyMap.GESTURE_COUNT];
        java.util.Arrays.fill(keys, ReaderKeyMap.LEGACY);
        keys[ReaderKeyMap.CENTER_BOTTOM * ReaderKeyMap.GESTURE_COUNT
                + ReaderKeyMap.LONG_PRESS] = action;
        view.setReaderKeyMap(new ReaderKeyMap(keys));
        view.setAnimatedPageControlAreasEnabled(true);
        List<String> callbacks = new ArrayList<>();
        ReflectionHelpers.setField(view, "mListener", (GalleryView.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{GalleryView.Listener.class},
                (proxy, method, args) -> {
                    callbacks.add(method.getName());
                    if (method.getName().equals("onLongPressSliderArea")) {
                        return activity.onLongPressSliderArea((ImageTexture) args[0]);
                    }
                    if (method.getName().equals("onLongPressSliderAreaReleased")) {
                        activity.onLongPressSliderAreaReleased();
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                }));
        ReflectionHelpers.callInstanceMethod(view, "onLongPressInternal",
                ClassParameter.from(float.class, 500f), ClassParameter.from(float.class, 900f));
        assertEquals(expected, callbacks);
        boolean speeding = speed != 1f;
        assertEquals(speeding, (boolean) ReflectionHelpers.getField(view, "mSliderLongPressHandled"));
        assertEquals(speeding, (boolean) ReflectionHelpers.getField(activity, "mAnimatedWebpLongPressActive"));
        if (speeding) assertEquals(speed, texture.getPlaybackSpeed(), 0f);
        ReflectionHelpers.callInstanceMethod(view, "onUpInternal");
        ReflectionHelpers.callInstanceMethod(view, "onUpInternal");
        List<String> released = new ArrayList<>(expected);
        if (speeding) released.add("onLongPressSliderAreaReleased");
        assertEquals(released, callbacks);
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpLongPressActive"));
        assertEquals(1f, texture.getPlaybackSpeed(), 0f);
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

        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, methods.size());
        assertEquals(1, args.get(0)[0]);
        methods.clear();
        args.clear();

        // The swipe ended while the completion callback was waiting in the queue.
        ReflectionHelpers.setField(view, "mPageSwipeInProgress", true);
        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        ReflectionHelpers.setField(view, "mPageSwipeInProgress", false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(methods.isEmpty());

        // The swipe began after the callback was queued.
        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        ReflectionHelpers.setField(view, "mPageSwipeInProgress", true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(methods.isEmpty());
    }

    private GalleryView autoAdvanceGallery(ImageTexture texture) {
        GalleryView view = new GalleryView.Builder(RuntimeEnvironment.getApplication(),
                new GalleryView.Adapter() {
                    @Override public void onBind(GalleryPageView page, int index) {}
                    @Override public void onUnbind(GalleryPageView page, int index) {}
                    @Override public String getError() { return null; }
                    @Override public int size() { return 2; }
                }).build();
        ReflectionHelpers.setField(activity, "mGalleryView", view);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 0);
        ReflectionHelpers.setField(activity, "mSize", 2);
        Settings.putAnimatedWebpAutoAdvance(true);
        return view;
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void queuedCycleDoesNotAdvanceAfterPauseEvenIfPlaybackResumes() {
        ImageTexture texture = texture();
        GalleryView view = autoAdvanceGallery(texture);
        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        texture.setPlaybackPlaying(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((List<?>) ReflectionHelpers.getField(view, "mMethodList")).isEmpty());

        texture.setPlaybackPlaying(true);
        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        texture.setPlaybackPlaying(false);
        texture.setPlaybackPlaying(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((List<?>) ReflectionHelpers.getField(view, "mMethodList")).isEmpty());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void queuedCycleDoesNotAdvanceDuringOrAfterAnimationSeek() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(texture, "mPlaybackDuration", 1001);
        GalleryView view = autoAdvanceGallery(texture);
        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        ReflectionHelpers.setField(activity, "mAnimatedWebpSeeking", true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((List<?>) ReflectionHelpers.getField(view, "mMethodList")).isEmpty());

        ReflectionHelpers.setField(activity, "mAnimatedWebpSeeking", false);
        activity.onPlaybackCycleCompleted(texture, texture.getPlaybackGeneration());
        texture.seekTo(500);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((List<?>) ReflectionHelpers.getField(view, "mMethodList")).isEmpty());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void oldCycleGenerationIsRejectedEvenWhenDeliveredAfterResume() {
        ImageTexture texture = texture();
        GalleryView view = autoAdvanceGallery(texture);
        long generation = texture.getPlaybackGeneration();
        texture.setPlaybackPlaying(false);
        texture.setPlaybackPlaying(true);
        activity.onPlaybackCycleCompleted(texture, generation);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((List<?>) ReflectionHelpers.getField(view, "mMethodList")).isEmpty());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void progressWaitsForMatchingSeekCompletionInsteadOfNearbyPosition() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(texture, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(texture, "mPlaybackFramePosition", 500);
        ReflectionHelpers.setField(texture, "mPlaybackFrameDelay", 100);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        Settings.putAnimatedWebpAllowSeek(true);
        TouchThroughSeekBar seek = new TouchThroughSeekBar(activity);
        seek.setMax(1001);
        ReflectionHelpers.setField(activity, "mAnimatedWebpSeek", seek);
        ReflectionHelpers.callInstanceMethod(activity, "beginAnimatedWebpSeek");
        ReflectionHelpers.callInstanceMethod(activity, "finishAnimatedWebpSeek",
                ClassParameter.from(int.class, 600));
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpProgress",
                ClassParameter.from(ImageTexture.class, texture));
        assertTrue(ReflectionHelpers.getField(activity, "mAnimatedWebpSeekAwaitingFrame"));
        assertEquals(600, seek.getProgress());

        long request = ReflectionHelpers.getField(activity, "mAnimatedWebpSeekRequestId");
        ReflectionHelpers.setField(texture, "mCompletedSeekGeneration", request - 1);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpProgress",
                ClassParameter.from(ImageTexture.class, texture));
        assertTrue(ReflectionHelpers.getField(activity, "mAnimatedWebpSeekAwaitingFrame"));

        ReflectionHelpers.setField(texture, "mCompletedSeekGeneration", request);
        ReflectionHelpers.setField(texture, "mPlaybackFramePosition", 600);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpProgress",
                ClassParameter.from(ImageTexture.class, texture));
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeekAwaitingFrame"));
        assertEquals(600, seek.getProgress());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void frameCallbacksAreCoalescedAndHiddenProgressDoesNotScheduleWork() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(texture, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(texture, "mPlaybackFramePosition", 500);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        TouchThroughSeekBar seek = new TouchThroughSeekBar(activity);
        seek.setMax(1001);
        final int[] changes = {0};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { ++changes[0]; }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        ReflectionHelpers.setField(activity, "mAnimatedWebpSeek", seek);
        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLastUiUpdateAt", SystemClock.uptimeMillis());
        for (int i = 0; i < 100; ++i) activity.onPlaybackChanged(texture, true);
        assertEquals(0, changes[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(49));
        assertEquals(0, changes[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals(1, changes[0]);
        assertEquals(500, seek.getProgress());

        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", false);
        ReflectionHelpers.setField(texture, "mPlaybackFramePosition", 700);
        for (int i = 0; i < 100; ++i) activity.onPlaybackChanged(texture, true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(1, changes[0]);
        assertEquals(500, seek.getProgress());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void clearingCallbacksAllowsFreshProgressAfterResume() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", true);
        activity.onPlaybackChanged(texture, true);
        ReflectionHelpers.callInstanceMethod(activity, "clearAnimatedWebpCallbacks");
        assertEquals(null, ReflectionHelpers.getField(activity, "mAnimatedWebpUiUpdateTexture"));
        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", true);
        activity.onPlaybackChanged(texture, true);
        assertEquals(texture, ReflectionHelpers.getField(activity, "mAnimatedWebpUiUpdateTexture"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(null, ReflectionHelpers.getField(activity, "mAnimatedWebpUiUpdateTexture"));
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void stateChangeIsNotDelayedBehindThrottledProgress() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpLastUiUpdateAt", SystemClock.uptimeMillis());
        activity.onPlaybackChanged(texture, true);
        activity.onPlaybackChanged(texture, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // Without an attached gallery page, a full UI update clears the texture.
        assertEquals(null, ReflectionHelpers.getField(activity, "mAnimatedWebpTexture"));
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void hiddenProgressStillReceivesSeekAcknowledgement() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", false);
        ReflectionHelpers.setField(activity, "mAnimatedWebpSeekAwaitingFrame", true);
        ReflectionHelpers.setField(activity, "mAnimatedWebpSeekRequestId", 7L);
        ReflectionHelpers.setField(texture, "mCompletedSeekGeneration", 7L);
        activity.onPlaybackChanged(texture, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeekAwaitingFrame"));
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void fastSeekCompletionIsNotLostWhenProgressIsHidden() {
        ImageWrapper wrapper = new ImageWrapper(Image.create(
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)));
        assertTrue(wrapper.obtain());
        ImageTexture texture = new ImageTexture(wrapper) {
            @Override public long seekTo(int position) {
                long request = super.seekTo(position);
                ReflectionHelpers.setField(this, "mCompletedSeekGeneration", request);
                ReflectionHelpers.setField(this, "mPlaybackFramePosition", position);
                activity.onPlaybackChanged(this, true);
                return request;
            }
        };
        textures.add(texture);
        ReflectionHelpers.setField(texture, "mControllableAnimation", true);
        ReflectionHelpers.setField(texture, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        ReflectionHelpers.setField(activity, "mAnimatedWebpProgressVisible", false);
        Settings.putAnimatedWebpAllowSeek(true);
        ReflectionHelpers.callInstanceMethod(activity, "beginAnimatedWebpSeek");
        ReflectionHelpers.callInstanceMethod(activity, "finishAnimatedWebpSeek",
                ClassParameter.from(int.class, 600));
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeekAwaitingFrame"));
        assertEquals(600, texture.getPlaybackPosition());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void dragUpdatesOneLiveTargetImmediatelyAndReleaseKeepsTheFinalPosition() {
        ImageTexture texture = texture();
        ReflectionHelpers.setField(texture, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);
        Settings.putAnimatedWebpAllowSeek(true);
        ReflectionHelpers.callInstanceMethod(activity, "beginAnimatedWebpSeek");
        preview(200);
        preview(400);
        preview(700);
        assertEquals(700, (int) ReflectionHelpers.getField(texture, "mPendingSeek"));

        preview(800);
        ReflectionHelpers.callInstanceMethod(activity, "finishAnimatedWebpSeek",
                ClassParameter.from(int.class, 900));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(900, (int) ReflectionHelpers.getField(texture, "mPendingSeek"));
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void previousPageInputDoesNotScheduleDelayedWorkForAnotherPage() {
        ImageTexture first = texture();
        ImageTexture next = texture();
        ReflectionHelpers.setField(first, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(next, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", first);
        Settings.putAnimatedWebpAllowSeek(true);
        ReflectionHelpers.callInstanceMethod(activity, "beginAnimatedWebpSeek");
        preview(200);
        preview(700);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", next);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(700, (int) ReflectionHelpers.getField(first, "mPendingSeek"));
        assertEquals(-1, (int) ReflectionHelpers.getField(next, "mPendingSeek"));
    }

    private void preview(int position) {
        ReflectionHelpers.callInstanceMethod(activity, "requestAnimatedWebpSeekPreview",
                ClassParameter.from(int.class, position), ClassParameter.from(boolean.class, false));
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

    @Test
    public void readerKeysFollowCurrentControllablePageInsteadOfGlobalAnimationSetting() {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
            for (int region = ReaderKeyMap.CENTER_TOP; region <= ReaderKeyMap.CENTER_BOTTOM; region++) {
                for (int gesture = 0; gesture < ReaderKeyMap.GESTURE_COUNT; gesture++) {
                    profiles.active().keys(direction)[region * 3 + gesture] = ReaderKeyMap.NONE;
                    profiles.active().keys(direction, true)[region * 3 + gesture] = ReaderKeyMap.MENU;
                }
            }
        }
        profiles.save();
        GalleryView view = new GalleryView.Builder(RuntimeEnvironment.getApplication(), new GalleryView.Adapter() {
            @Override public void onBind(GalleryPageView page, int index) { }
            @Override public void onUnbind(GalleryPageView page, int index) { }
            @Override public String getError() { return null; }
            @Override public int size() { return 2; }
        }).build();
        view.bounds().set(0, 0, 1000, 1000);
        ReflectionHelpers.callInstanceMethod(view, "attachLayoutManager");
        Object layout = ReflectionHelpers.getField(view, "mLayoutManager");
        GalleryPageView page = new GalleryPageView(null, 0, 0, 0, 0, 0);
        ReflectionHelpers.setField(page, "mIndex", 0);
        ReflectionHelpers.setField(layout, "mCurrent", page);
        ReflectionHelpers.setField(activity, "mGalleryView", view);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 0);

        // Loading and static pages keep normal bindings even with animation enabled.
        ReflectionHelpers.callInstanceMethod(activity, "applyReaderKeyProfile");
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, false);
        ImageTexture animated = texture();
        page.setImage(animated);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, true);
        ReflectionHelpers.callInstanceMethod(activity, "applyReaderKeyProfile");
        assertCenterKeys(view, true);

        // A missing next page must clear the previous page's animation state.
        ReflectionHelpers.setField(activity, "mCurrentIndex", 1);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, false);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 0);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, true);
        ReflectionHelpers.setField(animated, "mControllableAnimation", false);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, false);
        ReflectionHelpers.setField(animated, "mControllableAnimation", true);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, true);

        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_TOP_TO_BOTTOM);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, false);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, true);
        Settings.putExperimentalAnimatedWebpEnabled(false);
        ReflectionHelpers.callInstanceMethod(activity, "updateAnimatedWebpUi");
        assertCenterKeys(view, false);
    }

    private void assertCenterKeys(GalleryView view, boolean animated) {
        for (int region = ReaderKeyMap.CENTER_TOP; region <= ReaderKeyMap.CENTER_BOTTOM; region++) {
            float[] bounds = ReaderKeyMap.bounds(region);
            float y = (bounds[1] + bounds[3]) * 500;
            assertEquals(animated, view.isDoubleTapRegion(500, y));
            assertEquals(animated && region == ReaderKeyMap.CENTER_BOTTOM,
                    view.isAnimatedPageControlArea(500, y));
            for (int gesture = 0; gesture < ReaderKeyMap.GESTURE_COUNT; gesture++) {
                int action = ReflectionHelpers.callInstanceMethod(view, "readerKeyAction",
                        ClassParameter.from(float.class, 500f), ClassParameter.from(float.class, y),
                        ClassParameter.from(int.class, gesture));
                assertEquals(animated ? ReaderKeyMap.MENU : ReaderKeyMap.NONE, action);
            }
        }
    }

    @Test public void scrubbingUsesTakeoverUnionAndCurrentProfileInsteadOfLowerQuarter() {
        GalleryView view = new GalleryView.Builder(RuntimeEnvironment.getApplication(), new GalleryView.Adapter() {
            @Override public void onBind(GalleryPageView page, int index) { }
            @Override public void onUnbind(GalleryPageView page, int index) { }
            @Override public String getError() { return null; }
            @Override public int size() { return 2; }
        }).build();
        view.bounds().set(0, 0, 1000, 1000);
        GLRootView root = new GLRootView(activity);
        root.layout(0, 0, 1000, 1000);
        ReflectionHelpers.setField(activity, "mGLRootView", root);
        ReflectionHelpers.setField(activity, "mGalleryView", view);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture());
        Settings.putAnimatedWebpAllowSeek(true);
        view.setAnimatedPageControlAreasEnabled(true);
        view.setReaderKeyMap(new ReaderKeyMap((int[][]) null, boundaryAreas(.6f)));
        assertTrue(canScrub(900, 600));
        assertFalse(canScrub(900, 599));
        assertTrue(canScrub(500, 500));
        assertFalse(canScrub(500, 100));
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(ReaderTouchAreas.CENTER_BOTTOM_SPLIT, .85f)
                .withPosition(ReaderTouchAreas.ANIMATED_SPLIT, .9f);
        view.setReaderKeyMap(new ReaderKeyMap(null, new ReaderTouchAreas[]{areas, areas, areas}));
        assertFalse(canScrub(500, 849));
        assertTrue(canScrub(500, 850));
        assertFalse(canScrub(900, 899));
        assertTrue(canScrub(900, 900));
        Settings.putAnimatedWebpAllowSeek(false);
        assertFalse(canScrub(500, 950));
        root.onPause();
    }

    private ReaderTouchAreas[] boundaryAreas(float position) {
        ReaderTouchAreas areas = ReaderTouchAreas.defaults().withPosition(ReaderTouchAreas.ANIMATED_SPLIT, position);
        return new ReaderTouchAreas[]{areas, areas, areas};
    }

    private boolean canScrub(float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            return ReflectionHelpers.callInstanceMethod(activity, "canStartAnimatedWebpScrub",
                    ClassParameter.from(MotionEvent.class, event));
        } finally { event.recycle(); }
    }
}
