package com.hippo.ehviewer.translation

import android.app.Application
import android.content.Context
import com.hippo.lib.glview.glrenderer.GLCanvas
import com.hippo.lib.glview.view.GLRootView
import java.lang.reflect.Proxy
import javax.microedition.khronos.opengles.GL10
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Deterministic queue/frame ordering; does not simulate the native EGL pause handshake. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class GLIdleLifecycleTest {
    @Test fun queuedAndNewIdleWorkWaitForTheFirstResumedFrame() {
        val root = TestRoot(RuntimeEnvironment.getApplication())
        val canvas = Proxy.newProxyInstance(GLCanvas::class.java.classLoader, arrayOf(GLCanvas::class.java)) {
            _, _, _ -> null
        } as GLCanvas
        ReflectionHelpers.setField(root, "mCanvas", canvas)
        var calls = 0
        try {
            root.addOnGLIdleListener { _, _ -> calls++; false }
            assertEquals(1, root.events.size)
            root.onPause()
            root.events.removeFirst().run() // queued before pause, delivered after it
            assertEquals(0, calls)
            root.addOnGLIdleListener { _, _ -> calls++; false }
            assertTrue(root.events.isEmpty())
            // Simulate a render request left pending when the old surface was paused.
            ReflectionHelpers.setField(root, "mRenderRequested", true)
            root.onResume()
            assertEquals(1, root.forcedFrames)
            root.addOnGLIdleListener { _, _ -> calls++; false }
            assertTrue("Old canvas allowed idle work before the resumed frame", root.events.isEmpty())
            ReflectionHelpers.callInstanceMethod<Unit>(root, "onDrawFrameLocked",
                ReflectionHelpers.ClassParameter.from(GL10::class.java, null))
            while (root.events.isNotEmpty()) root.events.removeFirst().run()
            assertEquals(3, calls)
        } finally { root.dispose() }
    }

    private class TestRoot(context: Context) : GLRootView(context) {
        val events = ArrayDeque<Runnable>()
        var forcedFrames = 0
        override fun queueEvent(event: Runnable) { events.addLast(event) }
        override fun requestRenderForced() { forcedFrames++ }
        fun dispose() = super.onDetachedFromWindow()
    }
}
