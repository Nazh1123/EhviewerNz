package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.Toolbar;

import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.BaseScene;
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene;
import com.hippo.ehviewer.widget.EhDrawerLayout;
import com.hippo.ehviewer.widget.EhDrawerView;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.scene.StageActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = GalleryDetailPreviewDrawerTest.TestApplication.class, sdk = 28,
        shadows = GalleryDetailPreviewDrawerTest.StageShadow.class, instrumentedPackages = "com.hippo.scene")
public class GalleryDetailPreviewDrawerTest {
    private MainActivity host;
    private Activity window;
    private EhDrawerLayout layout;
    private EhDrawerView right;
    private TestScene scene;
    private View root;

    @Before public void setUp() throws Exception {
        host = Robolectric.buildActivity(MainActivity.class).get();
        host.setTheme(R.style.AppTheme_Main);
        window = Robolectric.buildActivity(Activity.class).setup().get();
        root = LayoutInflater.from(host).inflate(R.layout.activity_main, null);
        layout = root.findViewById(R.id.draw_view);
        right = root.findViewById(R.id.right_drawer);
        ReflectionHelpers.setField(host, "mDrawerLayout", layout);
        ReflectionHelpers.setField(host, "mRightDrawer", right);
        layout.setDrawerListener(host);
        scene = new TestScene(host);
        StageShadow.top = scene;
        GalleryDetail detail = new GalleryDetail();
        detail.gid = 123;
        detail.token = "token";
        detail.previewPages = 3;
        ReflectionHelpers.setField(scene, "mGalleryDetail", detail);
        // The scene is current and resumed; its heavy detail data binding is outside this test.
        java.lang.reflect.Field fragmentState = androidx.fragment.app.Fragment.class.getDeclaredField("mState");
        fragmentState.setAccessible(true);
        fragmentState.setInt(scene, 7);
        right.setFullWidth(scene.isDrawerFullWidth());
        right.addView(scene.onCreateDrawerView(LayoutInflater.from(host), right, null));
        window.setContentView(root);
        measure();
        assertSame(scene, host.getTopScene());
        assertTrue(scene.isResumed());
        assertTrue(right.isAttachedToWindow());
    }

    @Test public void shortAndRetreatedDragContainOnlyTitleAndNeverNavigate() {
        assertEquals(480, right.getMeasuredWidth());
        assertEquals(host.getString(R.string.gallery_previews),
                ((Toolbar) right.findViewById(R.id.toolbar)).getTitle());
        assertTrue(((ViewGroup) right.findViewById(R.id.content_panel)).getChildCount() <= 1);
        assertNull(right.findViewById(R.id.content_layout));
        touch(MotionEvent.ACTION_DOWN, 430, 400);
        touch(MotionEvent.ACTION_MOVE, 400, 400);
        touch(MotionEvent.ACTION_MOVE, 380, 400);
        assertEquals(0, scene.launches);
        assertFalse(layout.isDrawerOpen(right));
        touch(MotionEvent.ACTION_MOVE, 420, 400);
        touch(MotionEvent.ACTION_UP, 420, 400);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(400));
        assertEquals(0, scene.launches);
        assertFalse(layout.isDrawersVisible());
    }

    @Test public void completeDrawerOpensRealPreviewSceneOnceWithoutSecondAnimation() {
        layout.openDrawer(right);
        assertEquals(0, scene.launches);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(400));
        assertTrue(layout.isDrawerOpen(right));
        assertEquals("completion callback", 1, scene.callbacks);
        assertEquals("navigation after callback", 1, scene.launches);
        assertEquals(GalleryPreviewsScene.class, scene.launched.getClazz());
        assertNotNull(ReflectionHelpers.getField(scene.launched, "tranHelper"));
        scene.onDrawerOpened();
        ShadowLooper.idleMainLooper();
        assertEquals(1, scene.launches);
    }

    @Test public void completedDragUsesExistingDrawerRecognition() {
        touch(MotionEvent.ACTION_DOWN, 430, 400);
        touch(MotionEvent.ACTION_MOVE, 400, 400);
        touch(MotionEvent.ACTION_MOVE, 50, 400);
        assertEquals(0, scene.launches);
        touch(MotionEvent.ACTION_UP, 50, 400);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(400));
        assertEquals(1, scene.launches);
    }

    @Test public void destroyedOrReplacedSceneCannotNavigateFromQueuedCompletion() {
        scene.onDrawerOpened();
        scene.onDestroyDrawerView();
        ShadowLooper.idleMainLooper();
        assertEquals(0, scene.launches);
        right.removeAllViews();
        right.addView(scene.onCreateDrawerView(LayoutInflater.from(host), right, null));
        scene.onDrawerOpened();
        StageShadow.top = new EmptyScene(host);
        ShadowLooper.idleMainLooper();
        assertEquals(0, scene.launches);
    }

    @Test public void destinationRemovesShellImmediatelyAndRestoresNormalDrawerWidth() {
        layout.openDrawer(right);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(400));
        EmptyScene destination = new EmptyScene(host);
        StageShadow.top = destination;
        host.onSceneViewCreated(destination, null);
        assertFalse(layout.isDrawerOpen(right));
        assertFalse(layout.isDrawersVisible());
        assertFalse(right.isFullWidth());
        measure();
        assertTrue(right.getMeasuredWidth() < 480);
        assertEquals(1, right.getChildCount());
        assertNull(right.findViewById(R.id.toolbar));
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED,
                layout.getDrawerLockMode(android.view.Gravity.RIGHT));
    }

    private void measure() {
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 480, 800);
    }

    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        layout.dispatchTouchEvent(event);
        event.recycle();
    }

    @Implements(StageActivity.class)
    public static class StageShadow extends org.robolectric.shadows.ShadowActivity {
        static SceneFragment top;
        @Implementation protected SceneFragment getTopScene() { return top; }
    }

    private static class TestScene extends GalleryDetailScene {
        final MainActivity host;
        int launches;
        int callbacks;
        Announcer launched;
        TestScene(MainActivity host) { this.host = host; }
        @Override public Context getContext() { return host; }
        @Override public Context getEHContext() { return host; }
        @Override public MainActivity getActivity2() { return host; }
        @Override public void onDrawerOpened() { callbacks++; super.onDrawerOpened(); }
        @Override public void startScene(Announcer announcer) { launched = announcer; launches++; }
    }

    private static class EmptyScene extends BaseScene {
        final Context context;
        EmptyScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public View onCreateDrawerView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
            return new View(context);
        }
    }

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() { Settings.initialize(this); }
    }
}
