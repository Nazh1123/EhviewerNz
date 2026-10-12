package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.yorozuya.SimpleHandler;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class,
        shadows = {ReaderPageSeekTest.PreviewAppShadow.class, ReaderPageSeekTest.PreviewClientShadow.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReaderPageSeekTest {
    private GalleryActivity activity;
    private GalleryView gallery;
    private ReversibleSeekBar seek;
    private TextView left;
    private TextView right;
    private static com.hippo.conaco.Conaco<com.hippo.lib.image.Image> previewConaco;
    private static final androidx.collection.LruCache<Long, com.hippo.ehviewer.client.data.GalleryDetail> details = new androidx.collection.LruCache<>(2);
    private static com.hippo.ehviewer.client.EhRequest metadataRequest;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", context.getSharedPreferences("preview-seek-test", 0));
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        gallery = new GalleryView.Builder(context, new GalleryView.Adapter() {
            @Override public void onBind(GalleryPageView page, int index) { }
            @Override public void onUnbind(GalleryPageView page, int index) { }
            @Override public String getError() { return null; }
            @Override public int size() { return 100; }
        }).build();
        seek = new ReversibleSeekBar(context);
        seek.setMax(99);
        seek.setProgress(10);
        seek.setOnSeekBarChangeListener(activity);
        left = new TextView(context);
        right = new TextView(context);
        ReflectionHelpers.setField(activity, "mGalleryView", gallery);
        ReflectionHelpers.setField(activity, "mSeekBar", seek);
        ReflectionHelpers.setField(activity, "mLeftText", left);
        ReflectionHelpers.setField(activity, "mRightText", right);
        ReflectionHelpers.setField(activity, "mSize", 100);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 10);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
    }

    @After public void cleanup() {
        SimpleHandler.getInstance().removeCallbacksAndMessages(null);
        ((Handler) ReflectionHelpers.getField(activity, "mAnimatedWebpHandler")).removeCallbacksAndMessages(null);
        ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor")).shutdownNow();
        ((ExecutorService) ReflectionHelpers.getField(activity, "transferService")).shutdownNow();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    private List<Integer> methods() { return ReflectionHelpers.getField(gallery, "mMethodList"); }
    private int jumpMethod() { return ReflectionHelpers.getStaticField(GalleryView.class, "METHOD_SET_CURRENT_PAGE"); }
    private void dragTo(int page) {
        activity.onStartTrackingTouch(seek);
        seek.setProgress(page);
        activity.onProgressChanged(seek, page, true);
    }

    @Test public void draggingDoesNotJumpAndReleaseCommitsExactlyOnce() {
        dragTo(50);
        assertFalse(methods().contains(jumpMethod()));
        assertEquals("51", left.getText().toString());
        activity.onStopTrackingTouch(seek);
        assertEquals(1, methods().stream().filter(m -> m == jumpMethod()).count());
        List<Object[]> args = ReflectionHelpers.getField(gallery, "mArgsList");
        assertEquals(50, args.get(methods().indexOf(jumpMethod()))[0]);
    }

    @Test public void currentPageNotificationCannotPullSliderBackDuringDrag() {
        dragTo(50);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 25);
        ReflectionHelpers.callInstanceMethod(activity, "updateSlider");
        assertEquals(50, seek.getProgress());
        assertEquals("51", left.getText().toString());
    }

    @Test public void canceledTouchRestoresCurrentPageWithoutJumping() {
        dragTo(50);
        MotionEvent cancel = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { seek.onTouchEvent(cancel); } finally { cancel.recycle(); }
        activity.onStopTrackingTouch(seek);
        assertFalse(methods().contains(jumpMethod()));
        assertEquals(10, seek.getProgress());
    }

    @Test public void rightToLeftDragUpdatesTheRightPageLabel() {
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_RIGHT_TO_LEFT);
        dragTo(20);
        assertEquals("21", right.getText().toString());
        activity.onStopTrackingTouch(seek);
        assertTrue(methods().contains(jumpMethod()));
    }

    private void preparePreview() {
        com.hippo.conaco.Conaco.Builder<com.hippo.lib.image.Image> builder = new com.hippo.conaco.Conaco.Builder<>();
        builder.hasMemoryCache = true;
        builder.memoryCacheMaxSize = 1024 * 1024;
        builder.okHttpClient = new okhttp3.OkHttpClient();
        builder.objectHelper = new com.hippo.ehviewer.ImageBitmapHelper();
        previewConaco = builder.build();
        details.evictAll();
        metadataRequest = null;
        android.util.LruCache<?, ?> metadata = ReflectionHelpers.getStaticField(ReaderPagePreview.class, "METADATA");
        metadata.evictAll();
    }

    private void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("Preview did not become ready", ready.getAsBoolean());
    }

    @Test public void localPreviewDropsStaleWorkAndClosesItsSource() throws Exception {
        preparePreview();
        Context context = RuntimeEnvironment.getApplication();
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger readerRequests = new java.util.concurrent.atomic.AtomicInteger();
        com.hippo.ehviewer.gallery.DirGalleryProvider provider = new com.hippo.ehviewer.gallery.DirGalleryProvider(
                com.hippo.unifile.UniFile.fromFile(context.getCacheDir())) {
            @Override protected void onRequest(int index) { readerRequests.incrementAndGet(); }
            @Override public com.hippo.ehviewer.gallery.ReaderPreviewSource createPreviewSource(Context app) {
                return new com.hippo.ehviewer.gallery.ReaderPreviewSource() {
                    final String identity = java.util.UUID.randomUUID().toString();
                    @Override public com.hippo.ehviewer.gallery.ReaderPreviewBitmapCache.Result load(int index, int edge) throws java.io.IOException {
                        assertNotSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
                        if (index == 2) {
                            started.countDown();
                            try { new java.util.concurrent.CountDownLatch(1).await(3, java.util.concurrent.TimeUnit.SECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
                        }
                        return com.hippo.ehviewer.gallery.ReaderPreviewBitmapCache.load(app, identity + index, edge,
                                () -> new java.io.ByteArrayInputStream(png.toByteArray()));
                    }
                    @Override public void close() { closed.countDown(); }
                };
            }
        };
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        ReaderPagePreview preview = new ReaderPagePreview(context, row, provider);
        try {
            preview.show(2, 1000, false);
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            preview.update(4, 1000, false);
            android.widget.LinearLayout center = (android.widget.LinearLayout) row.getChildAt(2);
            waitFor(() -> ((android.widget.ImageView) center.getChildAt(0)).getDrawable() != null);
            assertEquals("5", ((TextView) center.getChildAt(1)).getText().toString());
            assertEquals(5, row.getChildCount());
            assertEquals(0, readerRequests.get());
            preview.hide();
            assertEquals(android.view.View.GONE, row.getVisibility());
        } finally { preview.close(); }
        assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test public void onlinePreviewMapsFilteredPageToWebsiteThumbnailPagination() {
        preparePreview();
        Context context = RuntimeEnvironment.getApplication();
        com.hippo.ehviewer.client.data.GalleryInfo info = new com.hippo.ehviewer.client.data.GalleryInfo();
        info.gid = 123; info.token = "preview-test";
        com.hippo.ehviewer.client.data.NormalPreviewSet set = new com.hippo.ehviewer.client.data.NormalPreviewSet();
        for (int i = 0; i < 40; i++) set.addItem(i, "https://example.invalid/thumb", 0, 0, 100, 150, "");
        com.hippo.ehviewer.client.data.GalleryDetail detail = new com.hippo.ehviewer.client.data.GalleryDetail();
        detail.token = info.token; detail.previewSet = set;
        details.put(info.gid, detail);
        com.hippo.ehviewer.gallery.EhGalleryProvider provider = new com.hippo.ehviewer.gallery.EhGalleryProvider(context, info) {
            @Override public int getSourcePage(int index) { return 120 + index; }
        };
        ReaderPagePreview preview = new ReaderPagePreview(context, new android.widget.LinearLayout(context), provider);
        try {
            preview.show(0, 3, true);
            ReflectionHelpers.callInstanceMethod(preview, "dispatchOnline");
            assertNotNull(metadataRequest);
            assertTrue(metadataRequest.getArgs()[0].toString().contains("p=3"));
            preview.hide();
            assertTrue(metadataRequest.isCancelled());
        } finally { preview.close(); }
    }

    @Implements(com.hippo.ehviewer.EhApplication.class)
    public static class PreviewAppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static com.hippo.conaco.Conaco<com.hippo.lib.image.Image> getConaco(Context context) {
            return previewConaco;
        }
        @Implementation protected static androidx.collection.LruCache<Long, com.hippo.ehviewer.client.data.GalleryDetail> getGalleryDetailCache(Context context) {
            return details;
        }
        @Implementation protected static com.hippo.ehviewer.client.EhClient getEhClient(Context context) {
            return new com.hippo.ehviewer.client.EhClient(context);
        }
    }

    @Implements(com.hippo.ehviewer.client.EhClient.class)
    public static class PreviewClientShadow {
        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected void execute(com.hippo.ehviewer.client.EhRequest request) { metadataRequest = request; }
    }
}
