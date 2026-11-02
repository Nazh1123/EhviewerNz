package com.hippo.lib.glview.image;

import android.app.Application;
import android.graphics.Bitmap;

import com.hippo.lib.image.Image;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ImageTextureLifetimeTest {
    @Test public void recyclerAndExitingWorkerReleaseOnlyOneOwnershipReference() throws Exception {
        Image source = Image.create(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch recyclerEntered = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger releases = new AtomicInteger();
        Thread[] recycler = new Thread[1];
        ImageWrapper wrapper = new ControlledImage(source) {
            @Override public boolean isImageRecycled() {
                if (Thread.currentThread() != recycler[0] && firstRead.compareAndSet(true, false)) {
                    workerEntered.countDown();
                    await(recyclerEntered);
                }
                return super.isImageRecycled();
            }

            @Override public void release() {
                if (Thread.currentThread() == recycler[0]) {
                    recyclerEntered.countDown();
                    await(workerFinished);
                }
                releases.incrementAndGet();
                super.release();
            }
        };
        assertTrue(wrapper.obtain()); // The texture's reference.
        assertTrue(wrapper.obtain()); // An independent owner of the same wrapper.
        ImageTexture texture = new ImageTexture(wrapper);
        Constructor<?> constructor = Class.forName(ImageTexture.class.getName() + "$AnimateRunnable")
                .getDeclaredConstructor(ImageTexture.class);
        constructor.setAccessible(true);
        Runnable task = (Runnable) constructor.newInstance(texture);
        ReflectionHelpers.setField(texture, "mAnimateRunnable", task);
        ((AtomicBoolean) ReflectionHelpers.getField(texture, "mRunning")).set(true);
        Thread worker = new Thread(() -> {
            try { task.run(); }
            finally { workerFinished.countDown(); }
        }, "exiting-animation-worker");
        recycler[0] = new Thread(texture::recycle, "animation-recycler");
        try {
            worker.start();
            await(workerEntered);
            recycler[0].start();
            worker.join(4000);
            recycler[0].join(4000);
            assertFalse("Worker did not exit", worker.isAlive());
            assertFalse("Recycler did not finish", recycler[0].isAlive());
            assertEquals("One texture must release exactly one reference", 1, releases.get());
            assertFalse("The other owner's image was recycled", source.isRecycled());
            texture.recycle();
            assertEquals("Repeated recycling must be harmless", 1, releases.get());
        } finally {
            recyclerEntered.countDown();
            workerFinished.countDown();
            worker.join(4000);
            if (recycler[0].getState() != Thread.State.NEW) recycler[0].join(4000);
            wrapper.release();
        }
        assertTrue(source.isRecycled());
    }

    @Test public void recyclingDuringDecodeWaitsForNativeOperationToFinish() throws Exception {
        verifyDeferredRelease(false);
    }

    @Test public void failedNativeOperationStillReleasesOnlyTheTexturesReference() throws Exception {
        verifyDeferredRelease(true);
    }

    @Test public void staticTextureReleasesOnlyItsOwnReferenceEvenWhenRecycledAgain() {
        Image source = Image.create(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
        ImageWrapper wrapper = new ImageWrapper(source);
        assertTrue(wrapper.obtain());
        assertTrue(wrapper.obtain());
        ImageTexture texture = new ImageTexture(wrapper);
        texture.recycle();
        texture.recycle();
        assertFalse(source.isRecycled());
        wrapper.release();
        assertTrue(source.isRecycled());
    }

    private void verifyDeferredRelease(boolean failDecode) throws Exception {
        Image source = Image.create(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch finishDecode = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        ImageWrapper wrapper = new ControlledImage(source) {
            @Override public boolean prepareNextFrame() {
                decoding.countDown();
                await(finishDecode);
                if (failDecode) throw new IllegalStateException("Injected decoder failure");
                return true;
            }

            @Override public void release() {
                releases.incrementAndGet();
                super.release();
                released.countDown();
            }
        };
        assertTrue(wrapper.obtain());
        assertTrue(wrapper.obtain());
        ImageTexture texture = new ImageTexture(wrapper);
        try {
            texture.start();
            await(decoding);
            texture.recycle();
            assertEquals("Native operation still owns the image", 0, releases.get());
            assertFalse(source.isRecycled());
            finishDecode.countDown();
            await(released);
            synchronized (wrapper) {
                assertEquals(1, releases.get());
                assertFalse(source.isRecycled());
            }
            texture.recycle();
            assertEquals(1, releases.get());
            if (failDecode) assertTrue(texture.hasPlaybackDecodeFailed());
        } finally {
            finishDecode.countDown();
            texture.recycle();
            await(released);
            wrapper.release();
        }
        assertTrue(source.isRecycled());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Timed out waiting for ownership handoff", latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }

    private static class ControlledImage extends ImageWrapper {
        ControlledImage(Image image) { super(image); }
        @Override public Boolean getAnimated() { return true; }
        @Override public boolean isControllableAnimation() { return true; }
        @Override public boolean canUseDirectTexture() { return false; }
        @Override public int getDelay() { return 1000; }
        @Override public int getTotalDuration() { return 1001; }
    }
}
