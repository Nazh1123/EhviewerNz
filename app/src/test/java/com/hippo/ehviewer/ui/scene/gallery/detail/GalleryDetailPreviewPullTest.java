package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene;
import com.hippo.refreshlayout.RefreshLayout;
import com.hippo.scene.Announcer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = GalleryDetailPreviewPullTest.TestApplication.class, sdk = 28)
public class GalleryDetailPreviewPullTest {
    private Context context;
    private GalleryDetailRefreshLayout pullLayout;
    private GalleryDetailScrollView scrollView;
    private TestScene scene;
    private long downTime;
    private long eventTime;

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("detail-preview-pull-test", Context.MODE_PRIVATE));
        context.getSharedPreferences("detail-preview-pull-test", Context.MODE_PRIVATE).edit().clear().commit();
        FrameLayout root = new FrameLayout(context);
        pullLayout = new GalleryDetailRefreshLayout(context, null);
        scrollView = new GalleryDetailScrollView(context);
        View content = new View(context);
        scrollView.addView(content, new ScrollView.LayoutParams(480, 1600));
        pullLayout.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pullLayout, new FrameLayout.LayoutParams(-1, -1));
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 480, 800);
        pullLayout.layout(0, 0, 480, 800);
        scrollView.layout(0, 0, 480, 800);
        content.layout(0, 0, 480, 1600);
        assertEquals(1600, content.getHeight());
        assertEquals(800, scrollView.getHeight());
        scrollView.scrollTo(0, 800);
        assertEquals(800, scrollView.getScrollY());
        assertFalse(pullLayout.canChildScrollDown());

        scene = new TestScene(context);
        GalleryDetail detail = new GalleryDetail();
        detail.gid = 123;
        detail.token = "token";
        detail.previewPages = 3;
        ReflectionHelpers.setField(scene, "mGalleryDetail", detail);
        ReflectionHelpers.callInstanceMethod(scene, "initPreviewPull",
                ReflectionHelpers.ClassParameter.from(RefreshLayout.class, pullLayout));
    }

    @After public void tearDown() {
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    @Test public void detailLayoutWrapsScrollViewWithFooterGesture() {
        View root = LayoutInflater.from(context).inflate(R.layout.scene_gallery_detail, null);
        assertTrue(root.findViewById(R.id.preview_pull_layout) instanceof GalleryDetailRefreshLayout);
        assertSame(root.findViewById(R.id.preview_pull_layout),
                root.findViewById(R.id.scroll_view).getParent());
    }

    @Test public void progressFollowsPullDistanceAndReleaseOpensPreviewsOnce() {
        Settings.putGalleryPreviewImmediateJump(false);
        float threshold = beginPull();
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 0.4f);
        float partial = progress();
        assertTrue(partial > 0f && partial < 1f);
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 0.75f);
        assertTrue(progress() > partial);
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 1.25f);
        assertEquals(1f, progress(), 0.001f);
        assertEquals(0, scene.launches);
        touch(MotionEvent.ACTION_UP, 240, 700 - threshold * 1.25f);
        assertEquals(1, scene.launches);
        assertEquals(GalleryPreviewsScene.class, scene.launched.getClazz());
        assertFalse(scene.launched.getArgs().containsKey(GalleryPreviewsScene.KEY_INITIAL_PAGE));
        assertFalse(pullLayout.isRefreshing());
    }

    @Test public void completedPullHonorsSecondPageSetting() {
        Settings.putGalleryPreviewImmediateJump(true);
        completePull();
        assertEquals(1, scene.launches);
        assertEquals(1, scene.launched.getArgs().getInt(GalleryPreviewsScene.KEY_INITIAL_PAGE, -1));
    }

    @Test public void singlePreviewPageStartsOnFirstPageEvenWithSettingEnabled() {
        Settings.putGalleryPreviewImmediateJump(true);
        ReflectionHelpers.<GalleryDetail>getField(scene, "mGalleryDetail").previewPages = 1;
        completePull();
        assertEquals(1, scene.launches);
        assertFalse(scene.launched.getArgs().containsKey(GalleryPreviewsScene.KEY_INITIAL_PAGE));
    }

    @Test public void shortPullDoesNotNavigate() {
        float threshold = beginPull();
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 0.6f);
        touch(MotionEvent.ACTION_UP, 240, 700 - threshold * 0.6f);
        assertEquals(0, scene.launches);
        assertFalse(pullLayout.isRefreshing());
    }

    @Test public void retreatBelowThresholdDoesNotNavigate() {
        float threshold = beginPull();
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 1.25f);
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 0.6f);
        assertTrue(progress() < 1f);
        touch(MotionEvent.ACTION_UP, 240, 700 - threshold * 0.6f);
        assertEquals(0, scene.launches);
    }

    @Test public void cancelledPullDoesNotNavigate() {
        float threshold = beginPull();
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 1.25f);
        touch(MotionEvent.ACTION_CANCEL, 240, 700 - threshold * 1.25f);
        assertEquals(0, scene.launches);
        assertFalse(pullLayout.isRefreshing());
    }

    @Test public void regularScrollAboveBottomDoesNotNavigate() {
        scrollView.scrollTo(0, 0);
        assertTrue(pullLayout.canChildScrollDown());
        touch(MotionEvent.ACTION_DOWN, 240, 700);
        touch(MotionEvent.ACTION_MOVE, 240, 680);
        touch(MotionEvent.ACTION_MOVE, 240, 200);
        touch(MotionEvent.ACTION_UP, 240, 200);
        assertEquals(0, scene.launches);
        assertEquals(0f, progress(), 0.001f);
        assertTrue(scrollView.getScrollY() > 0);
    }

    @Test public void horizontalSwipeAtBottomStillReachesScrollView() {
        int[] swipes = {0};
        scrollView.setOnSwipeLeftListener(new GalleryDetailScrollView.OnSwipeLeftListener() {
            @Override public void onSwipeLeftReadyChanged(boolean ready) {}
            @Override public void onSwipeLeft(boolean canJumpToNewContent) { swipes[0]++; }
        });
        touch(MotionEvent.ACTION_DOWN, 400, 700);
        touch(MotionEvent.ACTION_MOVE, 380, 690);
        touch(MotionEvent.ACTION_MOVE, 160, 675);
        touch(MotionEvent.ACTION_UP, 160, 675);
        assertEquals(1, swipes[0]);
        assertEquals(0, scene.launches);
        assertEquals(0f, progress(), 0.001f);
    }

    @Test public void pullDownAtTopDoesNotStartHeaderRefresh() {
        scrollView.scrollTo(0, 0);
        touch(MotionEvent.ACTION_DOWN, 240, 200);
        touch(MotionEvent.ACTION_MOVE, 240, 500);
        touch(MotionEvent.ACTION_UP, 240, 500);
        assertFalse(pullLayout.isRefreshing());
        assertEquals(0, scene.launches);
    }

    private float beginPull() {
        touch(MotionEvent.ACTION_DOWN, 240, 700);
        float threshold = ReflectionHelpers.getField(pullLayout, "mFooterDistanceToTriggerSync");
        assertTrue(threshold > 0);
        touch(MotionEvent.ACTION_MOVE, 240, 680);
        return threshold;
    }

    private void completePull() {
        float threshold = beginPull();
        touch(MotionEvent.ACTION_MOVE, 240, 700 - threshold * 1.25f);
        touch(MotionEvent.ACTION_UP, 240, 700 - threshold * 1.25f);
    }

    private float progress() {
        return ReflectionHelpers.getField(pullLayout, "mFooterCurrPercentage");
    }

    private void touch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = SystemClock.uptimeMillis();
            eventTime = downTime;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime += 30, action, x, y, 0);
        pullLayout.dispatchTouchEvent(event);
        event.recycle();
    }

    private static class TestScene extends GalleryDetailScene {
        final Context context;
        Announcer launched;
        int launches;
        TestScene(Context context) { this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public Context getEHContext() { return context; }
        @Override public void startScene(Announcer announcer) { launched = announcer; launches++; }
    }

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {}
    }
}
