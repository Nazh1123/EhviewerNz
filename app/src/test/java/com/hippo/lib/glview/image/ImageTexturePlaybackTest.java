package com.hippo.lib.glview.image;

import android.app.Application;
import android.graphics.Bitmap;

import com.hippo.lib.image.Image;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ImageTexturePlaybackTest {
    private TestImage image;
    private ImageTexture texture;
    private ImageTexture.PlaybackListener listener;

    @Before public void setup() {
        image = new TestImage();
        assertTrue(image.obtain());
        texture = new ImageTexture(image);
    }

    @After public void cleanup() throws Exception {
        texture.stop();
        image.allowPrepare.countDown();
        image.allowSeek.countDown();
        texture.recycle();
        await(this::workerIdle);
    }

    @Test public void startDuringOldWorkerCleanupHandsPlaybackToAnotherWorker() throws Exception {
        Class<?> type = Class.forName(ImageTexture.class.getName() + "$AnimateRunnable");
        Constructor<?> constructor = type.getDeclaredConstructor(ImageTexture.class);
        constructor.setAccessible(true);
        Runnable task = (Runnable) constructor.newInstance(texture);
        ReflectionHelpers.setField(texture, "mAnimateRunnable", task);
        Thread oldWorker = new Thread(task, "old-animation-worker");
        synchronized (image) {
            oldWorker.start();
            await(() -> oldWorker.getState() == Thread.State.BLOCKED);
            texture.start();
        }
        oldWorker.join(2000);
        assertFalse(oldWorker.isAlive());
        assertTrue("Restart did not create a worker", image.prepareEntered.await(2, TimeUnit.SECONDS));
        assertTrue(texture.isRunning());
        assertNotNull(ReflectionHelpers.getField(texture, "mAnimateRunnable"));
    }

    @Test public void stopAndRestartDuringDecodeDoesNotLoseStartRequest() throws Exception {
        texture.start();
        assertTrue(image.prepareEntered.await(2, TimeUnit.SECONDS));
        texture.stop();
        texture.start();
        texture.setPlaybackPlaying(false);
        long request = texture.seekTo(600);
        image.allowPrepare.countDown();
        await(() -> texture.hasCompletedSeek(request));
        assertTrue(texture.isRunning());
        assertEquals(600, texture.getPlaybackPosition());
        assertFalse(texture.hasPlaybackDecodeFailed());
    }

    @Test public void newerSeekReplacesLiveTargetAtTheNextFrameBoundary() throws Exception {
        image.blockFirstStep = true;
        texture.setPlaybackPlaying(false);
        long first = texture.seekTo(200);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        assertFalse(texture.hasCompletedSeek(first));
        long second = texture.seekTo(700);
        image.allowSeek.countDown();
        await(() -> texture.hasCompletedSeek(second));
        assertFalse(texture.hasCompletedSeek(first));
        assertEquals(700, texture.getPlaybackPosition());
        assertEquals(2, image.seekCalls.get());
    }

    @Test public void visibilityStopRetainsTheCursorAndLatestTargetForRestart() throws Exception {
        image.blockFirstStep = true;
        image.advanceOneFrame = true;
        texture.setPlaybackPlaying(false);
        long request = texture.seekTo(800);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        texture.stop();
        image.allowSeek.countDown();
        await(this::workerIdle);
        assertFalse(texture.hasCompletedSeek(request));
        texture.start();
        await(() -> texture.hasCompletedSeek(request));
        assertEquals(800, texture.getPlaybackPosition());
        assertEquals(100, (int) image.startingPositions.get(1));
    }

    @Test public void failedSeekStopsDecoderWithoutAcknowledgingIt() throws Exception {
        image.failSeek = true;
        texture.setPlaybackPlaying(false);
        long request = texture.seekTo(300);
        texture.start();
        await(() -> texture.hasPlaybackDecodeFailed() && !texture.isRunning()
                && !texture.isPlaybackPlaying());
        assertFalse(texture.hasCompletedSeek(request));
        assertFalse(texture.isRunning());
        assertFalse(texture.isPlaybackPlaying());
        texture.start();
        assertFalse(texture.isRunning());
    }

    @Test public void continuousPreviewRequestsPublishFramesAndCoalesceTargets() throws Exception {
        image.blockFirstStep = true;
        CopyOnWriteArrayList<Integer> shown = new CopyOnWriteArrayList<>();
        listener = new ImageTexture.PlaybackListener() {
            @Override public void onPlaybackChanged(ImageTexture source, boolean changed) {
                if (changed) shown.add(source.getPlaybackPosition());
            }
            @Override public void onPlaybackCycleCompleted(ImageTexture source, long generation) {}
            @Override public void onPlaybackStalled(ImageTexture source) { fail("Unexpected decoder failure"); }
        };
        texture.setPlaybackListener(listener);
        texture.setPlaybackPlaying(false);
        texture.previewSeekTo(200);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        texture.previewSeekTo(400);
        long latest = texture.previewSeekTo(700);
        image.allowSeek.countDown();
        await(() -> texture.hasCompletedSeek(latest) && shown.size() == 2);
        assertEquals(java.util.List.of(200, 700), shown);
        assertEquals(2, image.seekCalls.get());
    }

    @Test public void committedSeekUsesTheNextFrameBoundaryAndSkipsIntermediateTargets() throws Exception {
        image.blockFirstStep = true;
        texture.setPlaybackPlaying(false);
        texture.previewSeekTo(200);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        texture.previewSeekTo(400);
        long committed = texture.seekTo(900);
        image.allowSeek.countDown();
        await(() -> texture.hasCompletedSeek(committed));
        assertEquals(900, texture.getPlaybackPosition());
        assertEquals(2, image.seekCalls.get());
        assertEquals(java.util.List.of(200, 900), image.targets);
    }

    @Test public void committingActivePreviewAtSamePositionDoesNotRestartReplay() throws Exception {
        image.blockFirstStep = true;
        texture.setPlaybackPlaying(false);
        long preview = texture.previewSeekTo(600);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        long committed = texture.seekTo(600);
        assertEquals(preview, committed);
        image.allowSeek.countDown();
        await(() -> texture.hasCompletedSeek(committed));
        assertEquals(1, image.seekCalls.get());
    }

    @Test public void committingAnotherTimeInTheSameFrameReusesTheDecodedCanvas() throws Exception {
        image.blockFirstStep = true;
        texture.setPlaybackPlaying(false);
        long preview = texture.previewSeekTo(550);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        long committed = texture.seekTo(551);
        image.allowSeek.countDown();
        await(() -> texture.hasCompletedSeek(committed));
        assertFalse(texture.hasCompletedSeek(preview));
        assertEquals(551, texture.getPlaybackPosition());
        assertEquals(1, image.seekCalls.get());
    }

    @Test public void reversingTargetDoesNotFinishTheOldDependencyChain() throws Exception {
        image.blockFirstStep = true;
        image.advanceOneFrame = true;
        texture.setPlaybackPlaying(false);
        texture.previewSeekTo(900);
        texture.start();
        assertTrue(image.seekEntered.await(2, TimeUnit.SECONDS));
        long reversed = texture.previewSeekTo(50);
        image.allowSeek.countDown();
        await(() -> texture.hasCompletedSeek(reversed));
        assertEquals(java.util.List.of(900, 50), image.targets);
        assertEquals(50, texture.getPlaybackPosition());
    }

    @Test public void intermediateFramesArePublishedBeforeTheTargetIsAcknowledged() throws Exception {
        image.advanceOneFrame = true;
        texture.setPlaybackPlaying(false);
        long request = texture.previewSeekTo(900);
        java.util.concurrent.atomic.AtomicBoolean progressBeforeCompletion =
                new java.util.concurrent.atomic.AtomicBoolean();
        listener = new ImageTexture.PlaybackListener() {
            @Override public void onPlaybackChanged(ImageTexture source, boolean changed) {
                if (changed && source.getPlaybackPosition() == 100) {
                    progressBeforeCompletion.set(!source.hasCompletedSeek(request));
                }
            }
            @Override public void onPlaybackCycleCompleted(ImageTexture source, long generation) {}
            @Override public void onPlaybackStalled(ImageTexture source) { fail("Unexpected decoder failure"); }
        };
        texture.setPlaybackListener(listener);
        texture.start();
        await(() -> texture.hasCompletedSeek(request));
        assertTrue(progressBeforeCompletion.get());
        assertEquals(9, image.seekCalls.get());
    }

    private boolean workerIdle() {
        synchronized (image) {
            return ReflectionHelpers.getField(texture, "mAnimateRunnable") == null;
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue("Timed out waiting for playback worker", condition.getAsBoolean());
    }

    private static class TestImage extends ImageWrapper {
        final CountDownLatch prepareEntered = new CountDownLatch(1);
        final CountDownLatch allowPrepare = new CountDownLatch(1);
        final CountDownLatch seekEntered = new CountDownLatch(1);
        final CountDownLatch allowSeek = new CountDownLatch(1);
        final AtomicInteger seekCalls = new AtomicInteger();
        final CopyOnWriteArrayList<Integer> targets = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Integer> startingPositions = new CopyOnWriteArrayList<>();
        volatile boolean blockFirstStep;
        volatile boolean failSeek;
        volatile boolean advanceOneFrame;
        private int position;

        TestImage() {
            super(Image.create(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)));
        }

        @Override public Boolean getAnimated() { return true; }
        @Override public boolean isControllableAnimation() { return true; }
        @Override public boolean canUseDirectTexture() { return false; }
        @Override public int getTotalDuration() { return 1001; }
        @Override public int getCurrentPosition() { return position; }
        @Override public int getDelay() { return 100; }

        @Override public boolean prepareNextFrame() {
            prepareEntered.countDown();
            try {
                return allowPrepare.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        }

        @Override public int seekStep(int target) {
            int call = seekCalls.incrementAndGet();
            targets.add(target);
            startingPositions.add(position);
            seekEntered.countDown();
            if (blockFirstStep && call == 1) {
                try {
                    if (!allowSeek.await(3, TimeUnit.SECONDS)) throw new AssertionError("Preview was not released");
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
            }
            if (failSeek) return -1;
            int frame = target / 100 * 100;
            position = advanceOneFrame && frame > position ? Math.min(frame, position + 100) : frame;
            return position;
        }
    }
}
