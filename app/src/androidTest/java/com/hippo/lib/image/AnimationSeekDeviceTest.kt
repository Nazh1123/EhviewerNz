package com.hippo.lib.image

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/** Exercises the real JNI decoder, including disposal/blending and cancellation. */
@RunWith(AndroidJUnit4::class)
class AnimationSeekDeviceTest {
    private fun decode(name: String, sample: Int = 1): Image1 {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Image1.initialize(instrumentation.targetContext)
        return instrumentation.context.assets.open("animation/$name").use {
            requireNotNull(Image1.decode(it, false, sample))
        }
    }

    private fun snapshot(image: Image1): Bitmap =
        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
            image.render(0, 0, it, 0, 0, image.width, image.height, true, 0)
        }

    @Test fun gifAndWebpSeeksMatchSequentialFramesIncludingBackwardAndSameFrameSeeks() {
        for ((name, sample) in listOf("disposal.gif" to 1, "blend.webp" to 1, "blend.webp" to 2)) {
            val image = decode(name, sample)
            val frames = mutableListOf<Bitmap>()
            val starts = mutableListOf<Int>()
            val delays = mutableListOf<Int>()
            try {
                var start = 0
                repeat(image.frameCount) {
                    assertEquals(start, image.currentPosition)
                    starts += start
                    delays += image.delay
                    frames += snapshot(image)
                    start += image.delay
                    image.advanceAndGetLooped()
                }
                assertEquals(start, image.totalDuration)
                val order = listOf(frames.lastIndex, 0, 7, 7, 2, 15, 3, frames.lastIndex, 0)
                repeat(3) {
                    for (index in order) {
                        val position = starts[index] + delays[index] / 2
                        assertEquals(starts[index], image.seekTo(position))
                        assertEquals(starts[index], image.currentPosition)
                        val actual = snapshot(image)
                        try { assertTrue("$name sample=$sample frame=$index", frames[index].sameAs(actual)) }
                        finally { actual.recycle() }
                    }
                }
                assertEquals(0, image.seekTo(-1))
                assertEquals(starts.last(), image.seekTo(Int.MAX_VALUE))
            } finally {
                frames.forEach(Bitmap::recycle)
                image.recycle()
            }
        }
    }

    @Test fun cancelledReplayKeepsPublishedPixelsAndNextSeekRecovers() {
        for (name in listOf("disposal.gif", "blend.webp")) {
            val image = decode(name)
            val original = snapshot(image)
            try {
                val calls = AtomicInteger()
                assertEquals(-2, image.seekTo(image.totalDuration - 1,
                    BooleanSupplier { calls.incrementAndGet() >= 3 }))
                val afterCancel = snapshot(image)
                try { assertTrue(name, original.sameAs(afterCancel)) }
                finally { afterCancel.recycle() }
                assertEquals(0, image.seekTo(0))
                val recovered = snapshot(image)
                try { assertTrue(name, original.sameAs(recovered)) }
                finally { recovered.recycle() }
                assertTrue(image.seekTo(image.totalDuration - 1) > 0)
            } finally {
                original.recycle()
                image.recycle()
            }
        }
    }

    @Test fun webpPixelReadersRemainAvailableWhileSeekReplays() {
        val image = decode("blend.webp")
        val original = snapshot(image)
        val replayPaused = CountDownLatch(1)
        val continueReplay = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val calls = AtomicInteger()
        try {
            val seek = executor.submit<Int> {
                image.seekTo(image.totalDuration - 1, BooleanSupplier {
                    if (calls.incrementAndGet() == 3) {
                        replayPaused.countDown()
                        check(continueReplay.await(5, TimeUnit.SECONDS))
                    }
                    false
                })
            }
            assertTrue(replayPaused.await(3, TimeUnit.SECONDS))
            val read = executor.submit<Bitmap> { snapshot(image) }
            val current = read.get(1, TimeUnit.SECONDS)
            try { assertTrue("Seek exposed an intermediate canvas", original.sameAs(current)) }
            finally { current.recycle() }
            continueReplay.countDown()
            assertTrue(seek.get(3, TimeUnit.SECONDS) > 0)
        } finally {
            continueReplay.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            original.recycle()
            image.recycle()
        }
    }
}
