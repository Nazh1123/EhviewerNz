package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.widget.TouchThroughSeekBar;
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

import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AnimatedWebpSeekBarGestureTest {
    private GalleryActivity activity;
    private TouchThroughSeekBar seekBar;
    private ImageTexture texture;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("relative-seek-bar-test", Context.MODE_PRIVATE));
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        activity.getWindow().getDecorView().layout(0, 0, 1000, 1000);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTouchSlop", 8);

        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        ImageWrapper wrapper = new ImageWrapper(Image.create(bitmap));
        assertTrue(wrapper.obtain());
        texture = new ImageTexture(wrapper);
        ReflectionHelpers.setField(texture, "mControllableAnimation", true);
        ReflectionHelpers.setField(texture, "mPlaybackDuration", 1001);
        ReflectionHelpers.setField(texture, "mPlaybackFramePosition", 500);
        texture.setPlaybackPlaying(false);
        ReflectionHelpers.setField(activity, "mAnimatedWebpTexture", texture);

        seekBar = new TouchThroughSeekBar(activity);
        seekBar.layout(0, 0, 1000, 18);
        seekBar.setMax(1000);
        seekBar.setProgress(500);
        ReflectionHelpers.setField(activity, "mAnimatedWebpSeek", seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) ReflectionHelpers.callInstanceMethod(activity,
                        "requestAnimatedWebpSeekPreview", ClassParameter.from(int.class, progress),
                        ClassParameter.from(boolean.class, false));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                ReflectionHelpers.callInstanceMethod(activity, "beginAnimatedWebpSeek");
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                ReflectionHelpers.callInstanceMethod(activity, "finishAnimatedWebpSeek",
                        ClassParameter.from(int.class, bar.getProgress()));
            }
        });
    }

    @After
    public void tearDown() {
        Handler handler = ReflectionHelpers.getField(activity, "mAnimatedWebpHandler");
        handler.removeCallbacksAndMessages(null);
        texture.recycle();
        ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor"))
                .shutdownNow();
        ((ExecutorService) ReflectionHelpers.getField(activity, "transferService"))
                .shutdownNow();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    @Test
    public void dragUsesCurrentPositionEvenWhenFingerStartsElsewhere() {
        touch(MotionEvent.ACTION_DOWN, 850, 8, 0);
        assertEquals(500, seekBar.getProgress());
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
        touch(MotionEvent.ACTION_MOVE, 950, 8, 40);
        assertEquals(700, seekBar.getProgress());
        touch(MotionEvent.ACTION_UP, 950, 8, 80);
        assertEquals(700, seekBar.getProgress());
        assertFalse(ReflectionHelpers.getField(activity, "mAnimatedWebpSeeking"));
    }

    @Test
    public void tapStillJumpsToTappedPosition() {
        touch(MotionEvent.ACTION_DOWN, 750, 8, 0);
        assertEquals(500, seekBar.getProgress());
        touch(MotionEvent.ACTION_UP, 750, 8, 40);
        assertTrue(seekBar.getProgress() > 700);
        assertTrue(seekBar.getProgress() < 800);
    }

    private void touch(int action, float x, float y, long elapsedMs) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime, downTime + elapsedMs,
                action, x, y, 0);
        try {
            assertTrue(ReflectionHelpers.callInstanceMethod(activity,
                    "handleAnimatedWebpSeekBarTouch", ClassParameter.from(View.class, seekBar),
                    ClassParameter.from(MotionEvent.class, event)));
        } finally {
            event.recycle();
        }
    }
}
