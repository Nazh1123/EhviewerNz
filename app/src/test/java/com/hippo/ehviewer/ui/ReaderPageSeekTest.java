package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.DirGalleryProvider;
import com.hippo.ehviewer.gallery.EhGalleryProvider;
import com.hippo.ehviewer.gallery.ReaderPreviewBitmapCache;
import com.hippo.ehviewer.gallery.ReaderPreviewLoader;
import com.hippo.ehviewer.widget.ReversibleSeekBar;
import com.hippo.lib.glgallery.GalleryPageView;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.unifile.UniFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private TextView left, right;
    private Context context;
    private static com.hippo.conaco.Conaco<com.hippo.lib.image.Image> previewConaco;
    private static int thumbnailLoaderRequests;
    private static final androidx.collection.LruCache<Long, com.hippo.ehviewer.client.data.GalleryDetail> details = new androidx.collection.LruCache<>(2);
    private static EhRequest metadataRequest;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences preferences = context.getSharedPreferences("preview-seek-test", 0);
        preferences.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", preferences);
        activity = Robolectric.buildActivity(GalleryActivity.class).get();
        gallery = new GalleryView.Builder(context, new GalleryView.Adapter() {
            @Override public void onBind(GalleryPageView page, int index) { }
            @Override public void onUnbind(GalleryPageView page, int index) { }
            @Override public String getError() { return null; }
            @Override public int size() { return 1000; }
        }).build();
        seek = new ReversibleSeekBar(context);
        seek.setMax(999); seek.setProgress(10); seek.setOnSeekBarChangeListener(activity);
        left = new TextView(context); right = new TextView(context);
        ReflectionHelpers.setField(activity, "mGalleryView", gallery);
        ReflectionHelpers.setField(activity, "mSeekBar", seek);
        ReflectionHelpers.setField(activity, "mLeftText", left);
        ReflectionHelpers.setField(activity, "mRightText", right);
        ReflectionHelpers.setField(activity, "mSize", 1000);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 10);
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_LEFT_TO_RIGHT);
        com.hippo.conaco.Conaco.Builder<com.hippo.lib.image.Image> builder = new com.hippo.conaco.Conaco.Builder<>();
        builder.hasMemoryCache = true; builder.memoryCacheMaxSize = 1024 * 1024;
        builder.okHttpClient = new okhttp3.OkHttpClient(); builder.objectHelper = new com.hippo.ehviewer.ImageBitmapHelper();
        previewConaco = builder.build(); details.evictAll(); metadataRequest = null;
        thumbnailLoaderRequests = 0;
        ((android.util.LruCache<?, ?>) ReflectionHelpers.getStaticField(ReaderPagePreview.class, "METADATA")).evictAll();
        Object budget = ReflectionHelpers.getStaticField(ReaderPagePreview.class, "BUDGET");
        ((ArrayDeque<?>) ReflectionHelpers.getField(budget, "starts")).clear();
    }

    @After public void cleanup() {
        ReaderPagePreview preview = ReflectionHelpers.getField(activity, "mPagePreview");
        if (preview != null) preview.close();
        SimpleHandler.getInstance().removeCallbacksAndMessages(null);
        ((Handler) ReflectionHelpers.getField(activity, "mAnimatedWebpHandler")).removeCallbacksAndMessages(null);
        ((ExecutorService) ReflectionHelpers.getField(activity, "mImageFileExecutor")).shutdownNow();
        ((ExecutorService) ReflectionHelpers.getField(activity, "transferService")).shutdownNow();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    private List<Integer> methods() { return ReflectionHelpers.getField(gallery, "mMethodList"); }
    private int jumpMethod() { return ReflectionHelpers.getStaticField(GalleryView.class, "METHOD_SET_CURRENT_PAGE"); }
    private void dragTo(int page) {
        activity.onStartTrackingTouch(seek); seek.setProgress(page); activity.onProgressChanged(seek, page, true);
    }
    private byte[] poster() {
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        return encoded.toByteArray();
    }
    private DirGalleryProvider local(AtomicInteger reads) {
        byte[] encoded = poster(); String identity = UUID.randomUUID().toString();
        return new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public com.hippo.ehviewer.gallery.ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    reads.incrementAndGet();
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge, () -> new ByteArrayInputStream(encoded));
                };
            }
        };
    }
    private RecyclerView strip() { return new com.hippo.ehviewer.widget.ReaderPreviewRecyclerView(context); }
    private void layout(RecyclerView row) {
        // Apply the width adjustment on the following layout, as ViewRoot does after resize.
        for (int pass = 0; pass < 3; pass++) {
            row.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST));
            row.layout(0, 0, 1000, row.getMeasuredHeight());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue("Preview did not become ready", ready.getAsBoolean());
    }
    private View page(RecyclerView row, int index) { return row.getLayoutManager().findViewByPosition(index); }
    private ImageView image(View card) { return (ImageView) ((LinearLayout) card).getChildAt(0); }

    @Test @Config(qualifiers = "xxhdpi")
    public void decodeTargetFollowsActualCardSizeWithoutFixedPixelCap() {
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()))) {
            ReflectionHelpers.callInstanceMethod(preview, "updateDimensions",
                    ReflectionHelpers.ClassParameter.from(int.class, 2000));
            int largeEdge = ReflectionHelpers.getField(preview, "edge");
            int itemWidth = ReflectionHelpers.getField(preview, "itemWidth");
            int imageHeight = ReflectionHelpers.getField(preview, "imageHeight");
            int padding = Math.round(4 * row.getResources().getDisplayMetrics().density);
            assertEquals(Math.max(itemWidth - padding, imageHeight), largeEdge);
            assertTrue(largeEdge > 384);
            ReflectionHelpers.callInstanceMethod(preview, "updateDimensions",
                    ReflectionHelpers.ClassParameter.from(int.class, 1000));
            int smallEdge = ReflectionHelpers.getField(preview, "edge");
            assertTrue(smallEdge < largeEdge);
        }
    }

    @Test public void previewDefaultsToEnabledAndCanBeDisabled() {
        assertTrue(Settings.getReaderPagePreview());
        Settings.putReaderPagePreview(false); assertFalse(Settings.getReaderPagePreview());
        Settings.putReaderPagePreview(true); assertTrue(Settings.getReaderPagePreview());
    }

    @Test public void draggingAndReleaseBrowseWithoutCommitting() {
        dragTo(50); activity.onStopTrackingTouch(seek);
        assertFalse(methods().contains(jumpMethod()));
        assertTrue((Boolean) ReflectionHelpers.getField(activity, "mPageSeeking"));
        assertEquals("51", left.getText().toString());
    }

    @Test public void disabledPreviewRestoresReleaseToJumpExactlyOnce() {
        Settings.putReaderPagePreview(false);
        dragTo(50); assertFalse(methods().contains(jumpMethod()));
        activity.onStopTrackingTouch(seek); activity.onStopTrackingTouch(seek);
        assertEquals(1, methods().stream().filter(m -> m == jumpMethod()).count());
        List<Object[]> args = ReflectionHelpers.getField(gallery, "mArgsList");
        assertEquals(50, args.get(methods().indexOf(jumpMethod()))[0]);
    }

    @Test public void currentPageNotificationCannotPullBrowsingSliderBack() {
        dragTo(50); activity.onStopTrackingTouch(seek);
        ReflectionHelpers.setField(activity, "mCurrentIndex", 25);
        ReflectionHelpers.callInstanceMethod(activity, "updateSlider");
        assertEquals(50, seek.getProgress()); assertEquals("51", left.getText().toString());
    }

    @Test public void canceledTouchRestoresCurrentPageWithoutJumping() {
        dragTo(50);
        MotionEvent cancel = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { seek.onTouchEvent(cancel); } finally { cancel.recycle(); }
        activity.onStopTrackingTouch(seek);
        assertFalse(methods().contains(jumpMethod())); assertEquals(10, seek.getProgress());
        assertFalse((Boolean) ReflectionHelpers.getField(activity, "mPageSeeking"));
    }

    @Test public void rightToLeftBrowseAndThumbnailClickUseTheActualPage() {
        ReflectionHelpers.setField(activity, "mLayoutMode", GalleryView.LAYOUT_RIGHT_TO_LEFT);
        dragTo(20); activity.onStopTrackingTouch(seek);
        assertEquals("21", right.getText().toString()); assertFalse(methods().contains(jumpMethod()));
        activity.onPreviewPageBrowsed(24);
        assertEquals(24, seek.getProgress()); assertEquals("25", right.getText().toString());
        activity.onPreviewPageSelected(24); activity.onPreviewPageSelected(24);
        assertEquals(1, methods().stream().filter(m -> m == jumpMethod()).count());
        List<Object[]> args = ReflectionHelpers.getField(gallery, "mArgsList");
        assertEquals(24, args.get(methods().indexOf(jumpMethod()))[0]);
    }

    @Test public void accessibilityProgressOpensBrowsingWithoutJumping() {
        seek.setProgress(30); activity.onProgressChanged(seek, 30, true);
        assertTrue((Boolean) ReflectionHelpers.getField(activity, "mPageSeeking"));
        assertFalse(methods().contains(jumpMethod()));
    }

    @Test public void carouselRecyclesAGalleryAndOnlyClickCommitsTheBoundPage() throws Exception {
        RecyclerView row = strip(); List<Integer> chosen = new ArrayList<>();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()), chosen::add, page -> { })) {
            preview.show(50, 1000, false); layout(row);
            assertEquals(1000, row.getAdapter().getItemCount());
            assertTrue("Whole gallery views were inflated", row.getChildCount() <= 6);
            View first = page(row, 50); assertNotNull(first);
            assertEquals(250, first.getWidth());
            preview.update(300, 1000, false); layout(row);
            View target = page(row, 300); assertNotNull(target);
            assertEquals("301", ((TextView) ((LinearLayout) target).getChildAt(1)).getText().toString());
            assertTrue(chosen.isEmpty());
            target.performClick(); assertEquals(java.util.Collections.singletonList(300), chosen);
        }
    }

    @Test public void aSinglePageHasNoEmptyPlaceholderCards() {
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()))) {
            preview.show(0, 1, false); layout(row);
            assertEquals(1, row.getAdapter().getItemCount()); assertEquals(1, row.getChildCount());
            assertEquals("1", ((TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1)).getText().toString());
        }
    }

    @Test public void rightToLeftReversesPhysicalOrderWithoutChangingPageLabels() {
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()))) {
            preview.show(50, 1000, true); layout(row);
            int leftPage = -1, rightPage = -1, leftX = Integer.MAX_VALUE, rightX = Integer.MIN_VALUE;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i); int page = row.getChildAdapterPosition(child);
                if (child.getLeft() < leftX) { leftX = child.getLeft(); leftPage = page; }
                if (child.getLeft() > rightX) { rightX = child.getLeft(); rightPage = page; }
                assertEquals(Integer.toString(page + 1), ((TextView) ((LinearLayout) child).getChildAt(1)).getText().toString());
            }
            assertTrue(leftPage > rightPage); assertNotNull(page(row, 50));
        }
    }

    @Test public void hiddenCarouselAndReaderProgressDoNotLoadPreviews() throws Exception {
        RecyclerView row = strip(); AtomicInteger reads = new AtomicInteger();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(reads))) {
            ReflectionHelpers.setField(activity, "mPagePreview", preview);
            layout(row);
            ReflectionHelpers.callInstanceMethod(activity, "updateSlider");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
            ReaderPreviewLoader loader = ReflectionHelpers.getField(preview, "localLoader");
            assertFalse(loader.isLoading());
            assertEquals("Hidden carousel started background preview decoding", 0, reads.get());
            preview.show(30, 64, false); layout(row);
            waitFor(() -> page(row, 30) != null && image(page(row, 30)).getDrawable() != null);
            assertTrue(reads.get() > 0);
            assertNull(loader.get(0));
            assertNull(loader.get(63));
        }
    }

    @Test public void hideInterruptsOldDecodeAndAllowsANewCarouselTarget() throws Exception {
        RecyclerView row = strip(); byte[] encoded = poster(); String identity = UUID.randomUUID().toString();
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public com.hippo.ehviewer.gallery.ReaderPreviewSource createPreviewSource(Context app) {
                return (index, edge) -> {
                    if (index == 2) {
                        started.countDown();
                        try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) {
                            interrupted.countDown(); Thread.currentThread().interrupt(); throw new InterruptedIOException();
                        }
                    }
                    return ReaderPreviewBitmapCache.load(app, identity + index, edge, () -> new ByteArrayInputStream(encoded));
                };
            }
        };
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, provider)) {
            preview.show(2, 1000, false); layout(row); assertTrue(started.await(3, TimeUnit.SECONDS));
            preview.hide(); assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(View.GONE, row.getVisibility());
            preview.show(10, 1000, false); layout(row);
            waitFor(() -> page(row, 10) != null && image(page(row, 10)).getDrawable() != null);
            assertEquals("11", ((TextView) ((LinearLayout) page(row, 10)).getChildAt(1)).getText().toString());
        }
    }

    @Test public void backClosesPreviewAndRestoresCurrentProgressWithoutLeavingReader() {
        RecyclerView row = strip();
        ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()));
        ReflectionHelpers.setField(activity, "mPagePreview", preview);
        dragTo(50); layout(row); activity.onStopTrackingTouch(seek);
        assertTrue(preview.isVisible());
        activity.onBackPressed();
        assertFalse(preview.isVisible()); assertFalse(activity.isFinishing());
        assertEquals(10, seek.getProgress()); assertFalse(methods().contains(jumpMethod()));
    }

    @Test public void pausingAndProgressUpdatesDoNotLoadHiddenPreviews() {
        RecyclerView row = strip(); AtomicInteger reads = new AtomicInteger();
        DirGalleryProvider provider = local(reads);
        ReaderPagePreview preview = new ReaderPagePreview(context, row, provider);
        ReflectionHelpers.setField(activity, "mPagePreview", preview);
        ReflectionHelpers.setField(activity, "mGalleryProvider", provider);
        androidx.savedstate.SavedStateRegistryController savedState = ReflectionHelpers.getField(activity, "mSavedStateRegistryController");
        savedState.performRestore(null);
        androidx.fragment.app.FragmentController fragments = ReflectionHelpers.getField(activity, "mFragments");
        fragments.attachHost(null);
        androidx.lifecycle.LifecycleRegistry lifecycle = ReflectionHelpers.getField(activity, "mFragmentLifecycleRegistry");
        lifecycle.setCurrentState(androidx.lifecycle.Lifecycle.State.RESUMED);
        activity.onPause();
        // A late reader notification must not start decoding a hidden preview.
        ReflectionHelpers.callInstanceMethod(activity, "updateSlider");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
        assertEquals("Paused reader started background thumbnail decoding", 0, reads.get());
    }

    @Test public void unchangedProgressKeepsHorizontalBrowsingInProgress() {
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()))) {
            preview.show(50, 1000, false); layout(row);
            RecyclerView.OnScrollListener listener = ReflectionHelpers.getField(preview, "scrollListener");
            listener.onScrollStateChanged(row, RecyclerView.SCROLL_STATE_DRAGGING);
            row.scrollBy(125, 0);
            int selected = ReflectionHelpers.getField(preview, "selected");
            int leftBefore = page(row, selected).getLeft();
            preview.update(selected, 1000, false);
            assertTrue("Unchanged reader notification stopped thumbnail browsing",
                    (Boolean) ReflectionHelpers.getField(preview, "manualScrolling"));
            assertEquals(leftBefore, page(row, selected).getLeft());
        }
    }

    @Test public void reopeningTheSamePageRebindsClearedImages() throws Exception {
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, local(new AtomicInteger()))) {
            preview.show(50, 1000, false); layout(row);
            waitFor(() -> image(page(row, 50)).getDrawable() != null);
            preview.hide(); assertNull(image(page(row, 50)).getDrawable());
            preview.show(50, 1000, false);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("Reopening the same position left the cleared thumbnail blank", image(page(row, 50)).getDrawable());
        }
        assertEquals("Local bitmap binding and dismissal initialized the shared thumbnail loader", 0, thumbnailLoaderRequests);
    }

    @Test public void onlineThumbnailRequestInitializesDeferredLoader() throws Exception {
        GalleryInfo info = new GalleryInfo(); info.gid = 456; info.token = "deferred-thumbnail";
        com.hippo.ehviewer.client.data.NormalPreviewSet set = new com.hippo.ehviewer.client.data.NormalPreviewSet();
        set.addItem(0, "https://example.invalid/deferred", 0, 0, 100, 150, "");
        com.hippo.ehviewer.client.data.GalleryDetail detail = new com.hippo.ehviewer.client.data.GalleryDetail();
        detail.token = info.token; detail.previewSet = set; details.put(info.gid, detail);
        EhGalleryProvider provider = new EhGalleryProvider(context, info) {
            @Override protected UniFile getPreviewDirectory() { return null; }
        };
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, provider)) {
            assertEquals(0, thumbnailLoaderRequests);
            preview.show(0, 1, false); layout(row);
            assertEquals(1, thumbnailLoaderRequests);
            assertEquals(set.getGalleryPreview(info.gid, 0).getImageKey(),
                    ReflectionHelpers.getField(image(page(row, 0)), "mKey"));
            preview.hide();
            assertNull(image(page(row, 0)).getDrawable());
        }
    }

    @Test public void continuousScrollingLoadsVisibleNeighborsBeforeTheCenterFinishes() throws Exception {
        RecyclerView row = strip(); byte[] encoded = poster(); String identity = UUID.randomUUID().toString();
        CountDownLatch centerStarted = new CountDownLatch(1), resumeCenter = new CountDownLatch(1);
        AtomicInteger neighbors = new AtomicInteger();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public com.hippo.ehviewer.gallery.ReaderPreviewSource createPreviewSource(Context app) {
                return (index, edge) -> {
                    if (index == 50) {
                        centerStarted.countDown();
                        try { assertTrue(resumeCenter.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    } else neighbors.incrementAndGet();
                    return ReaderPreviewBitmapCache.load(app, identity + index, edge, () -> new ByteArrayInputStream(encoded));
                };
            }
        };
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, provider)) {
            preview.show(50, 1000, false); assertTrue(centerStarted.await(3, TimeUnit.SECONDS)); layout(row);
            for (int frame = 0; frame < 12; frame++) {
                row.scrollBy(2, 0);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
            }
            waitFor(() -> neighbors.get() > 0);
            assertEquals("Neighbor loading waited for the blocked center", 1, resumeCenter.getCount());
        } finally { resumeCenter.countDown(); }
    }

    @Test public void onlinePreviewMapsFilteredSourceAndCancelsMetadataOnDismiss() throws Exception {
        GalleryInfo info = new GalleryInfo(); info.gid = 123; info.token = "preview-test";
        com.hippo.ehviewer.client.data.NormalPreviewSet set = new com.hippo.ehviewer.client.data.NormalPreviewSet();
        for (int i = 0; i < 40; i++) set.addItem(i, "https://example.invalid/thumb", 0, 0, 100, 150, "");
        com.hippo.ehviewer.client.data.GalleryDetail detail = new com.hippo.ehviewer.client.data.GalleryDetail();
        detail.token = info.token; detail.previewSet = set; details.put(info.gid, detail);
        EhGalleryProvider provider = new EhGalleryProvider(context, info) {
            @Override public int getSourcePage(int index) { return 120 + index; }
            @Override protected UniFile getPreviewDirectory() { return null; }
        };
        RecyclerView row = strip();
        try (ReaderPagePreview preview = new ReaderPagePreview(context, row, provider)) {
            preview.show(0, 3, true); layout(row);
            ReaderPreviewLoader loader = ReflectionHelpers.getField(preview, "localLoader");
            waitFor(() -> loader.isUnavailable(0));
            ReflectionHelpers.callInstanceMethod(preview, "dispatchOnline");
            assertNotNull(metadataRequest); assertTrue(metadataRequest.getArgs()[0].toString().contains("p=3"));
            preview.hide(); assertTrue(metadataRequest.isCancelled());
        }
    }

    @Implements(com.hippo.ehviewer.EhApplication.class)
    public static class PreviewAppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static com.hippo.conaco.Conaco<com.hippo.lib.image.Image> getConaco(Context context) { thumbnailLoaderRequests++; return previewConaco; }
        @Implementation protected static androidx.collection.LruCache<Long, com.hippo.ehviewer.client.data.GalleryDetail> getGalleryDetailCache(Context context) { return details; }
        @Implementation protected static com.hippo.ehviewer.client.EhClient getEhClient(Context context) { return new com.hippo.ehviewer.client.EhClient(context); }
    }

    @Implements(com.hippo.ehviewer.client.EhClient.class)
    public static class PreviewClientShadow {
        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected void execute(EhRequest request) { metadataRequest = request; }
    }
}
