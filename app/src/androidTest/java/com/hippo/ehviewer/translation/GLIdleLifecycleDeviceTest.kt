package com.hippo.ehviewer.translation

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.lib.glview.glrenderer.GLCanvas
import com.hippo.lib.glview.view.GLRootView
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the platform GLThread pause handshake without opening a gallery or running models. */
@RunWith(AndroidJUnit4::class)
class GLIdleLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aRepeatingIdleCallbackCannotBlockPauseAndWaitsForResume() {
        lateinit var root: TestRoot
        val repeat = AtomicBoolean(true)
        val calls = AtomicInteger()
        val paused = CountDownLatch(1)
        val canvas = Proxy.newProxyInstance(GLCanvas::class.java.classLoader, arrayOf(GLCanvas::class.java)) {
            _, _, _ -> null
        } as GLCanvas
        instrumentation.runOnMainSync {
            root = TestRoot(instrumentation.targetContext)
            GLRootView::class.java.getDeclaredField("mCanvas").apply { isAccessible = true }.set(root, canvas)
            root.addOnGLIdleListener { _, _ -> calls.incrementAndGet(); repeat.get() }
        }
        val pauseThread = Thread {
            root.onPause()
            paused.countDown()
        }
        try {
            await { calls.get() >= 5 }
            pauseThread.start()
            assertTrue("GL idle events starved the platform pause handshake", paused.await(2, TimeUnit.SECONDS))
            val atPause = calls.get()
            val pendingDelivered = AtomicBoolean(false)
            root.addOnGLIdleListener { _, _ -> pendingDelivered.set(true); false }
            SystemClock.sleep(150)
            assertEquals("Callbacks continued while paused", atPause, calls.get())
            assertFalse(pendingDelivered.get())
            repeat.set(false)
            root.onResume()
            root.addOnGLIdleListener { _, _ -> false }
            SystemClock.sleep(150)
            assertFalse("Idle callbacks ran before the resumed EGL frame", pendingDelivered.get())
            assertEquals(atPause, calls.get())
            // This unattached test has no surface: emulate the resume frame clearing its
            // render flag, then trigger the retained queue once the gate is open.
            GLRootView::class.java.getDeclaredField("mIdleResumePending").apply { isAccessible = true }.set(root, false)
            GLRootView::class.java.getDeclaredField("mRenderRequested").apply { isAccessible = true }.set(root, false)
            root.addOnGLIdleListener { _, _ -> false }
            await { pendingDelivered.get() && calls.get() > atPause }
        } finally {
            // A failing regression must still let an unfixed GL thread finish its pause.
            repeat.set(false)
            if (pauseThread.state != Thread.State.NEW) pauseThread.join(3000)
            instrumentation.runOnMainSync { root.dispose() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (!condition()) {
            assertTrue("Timed out waiting for GL idle callback", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(10)
        }
    }

    private class TestRoot(context: Context) : GLRootView(context) {
        fun dispose() = super.onDetachedFromWindow()
    }
}
