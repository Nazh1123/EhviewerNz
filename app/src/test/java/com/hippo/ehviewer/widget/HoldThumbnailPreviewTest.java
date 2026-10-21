package com.hippo.ehviewer.widget;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.drawable.PreciselyClipDrawable;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.conaco.Conaco;
import com.hippo.conaco.ConacoTask;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.lib.image.Image;
import com.hippo.widget.LoadImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNativeBitmap;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HoldThumbnailPreviewTest {
    private Activity activity;
    private FrameLayout root;
    private ImageView image;
    private HoldThumbnailPreview preview;
    private int clicks;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        image = new ImageView(activity);
        Bitmap bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        image.setImageBitmap(bitmap);
        image.setOnClickListener(v -> clicks++);
        root.addView(image, new FrameLayout.LayoutParams(100, 150));
        layout(root, 480, 800);
        preview = new HoldThumbnailPreview();
        preview.bind(image);
    }

    @After public void tearDown() { preview.dispose(); activity.finish(); }

    @Test public void tapStillClicksAndMovingOutsideCancelsLongPress() {
        touch(image, MotionEvent.ACTION_DOWN, 20, 20);
        touch(image, MotionEvent.ACTION_UP, 20, 20);
        idle(600);
        assertEquals(1, clicks);
        assertNull(overlay());
        touch(image, MotionEvent.ACTION_DOWN, 20, 20);
        touch(image, MotionEvent.ACTION_MOVE, 20, 200);
        idle(600);
        assertNull(overlay());
        touch(image, MotionEvent.ACTION_CANCEL, 20, 200);
    }

    @Test public void longPressStaysOpenAfterReleaseAndTapClosesWithoutClickingThrough() {
        hold();
        assertNotNull(overlay());
        idle(140);
        assertEquals(1f, progress(), 0.001f);
        touch(image, MotionEvent.ACTION_UP, 20, 20);
        idle(140);
        assertNotNull(overlay());
        tapPreview(20, 20);
        idle(140);
        assertNull(overlay());
        assertEquals(0, clicks);
    }

    @Test public void releaseDuringOpeningKeepsPreviewAndBackClosesIt() {
        hold();
        idle(50);
        touch(image, MotionEvent.ACTION_UP, 20, 20);
        idle(250);
        assertNotNull(overlay());
        Dialog dialog = dialog();
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        idle(300);
        assertNull(overlay());
        assertEquals(0, clicks);
        assertFalse(activity.isFinishing());
    }

    @Test public void standardLongClickCanReopenAfterClosingDuringAnimation() {
        assertTrue(image.performLongClick());
        idle(50);
        dialog().onBackPressed();
        idle(200);
        assertNull(overlay());
        assertTrue(image.performLongClick());
        idle(200);
        assertNotNull(overlay());
        assertEquals(1f, progress(), 0.001f);
    }

    @Test @Config(shadows = {PreviewAppShadow.class, ThumbnailLoaderShadow.class})
    public void detailThumbnailLongClickSurvivesLoadingAndFailureRetry() {
        ThumbnailLoaderShadow.loads = 0;
        root.removeAllViews();
        View item = LayoutInflater.from(activity).inflate(R.layout.item_gallery_preview, root, false);
        LoadImageView thumbnail = item.findViewById(R.id.image);
        image = thumbnail;
        thumbnail.setOnClickListener(view -> clicks++);
        preview.bind(thumbnail);
        root.addView(item);
        thumbnail.load("preview", "https://example.invalid/preview.jpg");
        Bitmap bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        Image loaded = Image.create(bitmap);
        assertTrue(thumbnail.onGetValue(loaded, Conaco.SOURCE_MEMORY));
        layout(root, 480, 800);
        hold();
        touch(thumbnail, MotionEvent.ACTION_UP, 20, 20);
        idle(140);
        assertNotNull(overlay());
        preview.dismiss();

        thumbnail.load("replacement", "https://example.invalid/replacement.jpg");
        thumbnail.onFailure();
        int requestsBeforeRetry = ThumbnailLoaderShadow.loads;
        assertTrue(thumbnail.performLongClick());
        assertNull(overlay());
        assertEquals(requestsBeforeRetry + 1, ThumbnailLoaderShadow.loads);

        assertTrue(thumbnail.onGetValue(Image.create(Bitmap.createBitmap(
                100, 150, Bitmap.Config.ARGB_8888)), Conaco.SOURCE_MEMORY));
        layout(root, 480, 800);
        assertFalse("Retry must clear the failure state", thumbnail.mFailed);
        assertTrue("Retry must restore the external long-click listener", thumbnail.isLongClickable());
        assertTrue(thumbnail.performLongClick());
        idle(140);
        assertNotNull(overlay());
        tapPreview(240, 400);
        idle(140);
        assertNull(overlay());
        assertEquals(0, clicks);

        thumbnail.performClick();
        assertEquals(1, clicks);
    }

    @Implements(EhApplication.class)
    public static class PreviewAppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static Conaco<Image> getConaco(Context context) {
            return Shadow.newInstanceOf(Conaco.class);
        }
    }

    @Implements(Conaco.class)
    public static class ThumbnailLoaderShadow {
        private static int loads;
        @Implementation protected void load(ConacoTask.Builder<Image> builder) { loads++; }
    }

    @Test public void cancelOfOpeningGestureDoesNotClosePreview() {
        hold();
        idle(260);
        touch(image, MotionEvent.ACTION_CANCEL, 20, 20);
        touch(image, MotionEvent.ACTION_POINTER_DOWN, 20, 20);
        idle(300);
        assertNotNull(overlay());
        tapPreview(240, 400);
        idle(140);
        assertNull(overlay());
        assertEquals(0, clicks);
    }

    @Test public void detachAndLifecycleDismissCleanUpImmediately() {
        hold();
        root.removeView(image);
        assertNull(overlay());
        root.addView(image);
        layout(root, 480, 800);
        hold();
        preview.dismiss();
        idle(600);
        assertNull(overlay());
    }

    @Test public void spriteSnapshotContainsOnlySelectedTileAndRestoresDrawableBounds() {
        Bitmap sprite = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sprite);
        canvas.drawColor(Color.GREEN);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(100, 0, 200, 150, paint);
        PreciselyClipDrawable drawable = new PreciselyClipDrawable(
                new BitmapDrawable(activity.getResources(), sprite), 100, 0, 100, 150);
        image.setImageDrawable(drawable);
        layout(root, 480, 800);
        Rect before = new Rect(drawable.getBounds());
        hold();
        Bitmap snapshot = ReflectionHelpers.getField(overlay(), "bitmap");
        assertEquals(100, snapshot.getWidth());
        assertEquals(Color.RED, snapshot.getPixel(0, 75));
        assertEquals(Color.RED, snapshot.getPixel(99, 75));
        assertEquals(before, drawable.getBounds());
    }

    @Test @Config(shadows = HardwareBitmap.class)
    public void hardwareCoverCanBePreviewedWithoutRecyclingSharedBitmap() {
        Bitmap original = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888);
        original.eraseColor(Color.BLUE);
        Bitmap hardware = original.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        ((HardwareBitmap) Shadow.extract(hardware)).hardware = true;
        assertEquals(Bitmap.Config.HARDWARE, hardware.getConfig());
        // Desktop native graphics cannot allocate GPU storage. Enforce Android's hardware
        // bitmap restriction at the Java Canvas boundary so the old implementation fails here.
        assertThrows(IllegalArgumentException.class, () -> new Canvas(original)
                .drawBitmap(hardware, 0, 0, null));
        image.setImageBitmap(hardware);
        layout(root, 480, 800);
        hold();
        Bitmap snapshot = ReflectionHelpers.getField(overlay(), "bitmap");
        assertEquals(Bitmap.Config.ARGB_8888, snapshot.getConfig());
        assertEquals(Color.BLUE, snapshot.getPixel(50, 75));
        touch(image, MotionEvent.ACTION_UP, 20, 20);
        idle(140);
        assertNotNull(overlay());
        tapPreview(240, 400);
        idle(140);
        assertNull(overlay());
        assertFalse(hardware.isRecycled());
    }

    @Test @Config(shadows = HardwareBitmap.class)
    public void hardwareSpriteSnapshotPreservesClippingAndOriginalDrawable() {
        Bitmap sprite = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sprite);
        canvas.drawColor(Color.GREEN);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(100, 0, 200, 150, paint);
        Bitmap hardware = sprite.copy(Bitmap.Config.HARDWARE, false);
        ((HardwareBitmap) Shadow.extract(hardware)).hardware = true;
        BitmapDrawable child = new BitmapDrawable(activity.getResources(), hardware);
        PreciselyClipDrawable drawable = new PreciselyClipDrawable(child, 100, 0, 100, 150);
        image.setImageDrawable(drawable);
        layout(root, 480, 800);
        Rect before = new Rect(drawable.getBounds());
        Rect childBefore = new Rect(child.getBounds());
        hold();
        Bitmap snapshot = ReflectionHelpers.getField(overlay(), "bitmap");
        assertEquals(100, snapshot.getWidth());
        assertEquals(Color.RED, snapshot.getPixel(0, 75));
        assertEquals(Color.RED, snapshot.getPixel(99, 75));
        assertEquals(before, drawable.getBounds());
        assertEquals(childBefore, child.getBounds());
        assertSame(child, drawable.getDrawable());
        assertFalse(hardware.isRecycled());
    }

    @Implements(Bitmap.class)
    public static class HardwareBitmap extends ShadowNativeBitmap {
        @RealObject private Bitmap bitmap;
        private boolean hardware;

        @Implementation protected Bitmap.Config getConfig() {
            return hardware ? Bitmap.Config.HARDWARE
                    : Shadow.directlyOn(bitmap, Bitmap.class, "getConfig");
        }
    }

    @Test public void recyclerLongPressStaysOpenUntilTappedWithoutOpeningItem() {
        preview.dispose();
        root.removeAllViews();
        EasyRecyclerView recycler = new EasyRecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                image = new ImageView(activity);
                Bitmap bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.BLUE);
                image.setImageBitmap(bitmap);
                image.setLayoutParams(new RecyclerView.LayoutParams(100, 150));
                return new RecyclerView.ViewHolder(image) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 1; }
        });
        root.addView(recycler, new FrameLayout.LayoutParams(480, 800));
        layout(root, 480, 800);
        preview = new HoldThumbnailPreview();
        preview.attachToRecyclerView(recycler);
        recycler.setOnItemClickListener((parent, view, position, id) -> { clicks++; return true; });
        recycler.setOnItemLongClickListener((parent, view, position, id) -> preview.show(image));
        touch(recycler, MotionEvent.ACTION_DOWN, 20, 20);
        idle(ViewConfiguration.getLongPressTimeout() + 260);
        assertNotNull(overlay());
        touch(recycler, MotionEvent.ACTION_MOVE, 20, 300);
        touch(recycler, MotionEvent.ACTION_UP, 20, 300);
        idle(300);
        assertNotNull(overlay());
        tapPreview(240, 400);
        idle(140);
        assertNull(overlay());
        assertEquals(0, clicks);
    }

    private void hold() {
        touch(image, MotionEvent.ACTION_DOWN, 20, 20);
        idle(ViewConfiguration.getLongPressTimeout());
    }

    private Object overlay() { return ReflectionHelpers.getField(preview, "mOverlay"); }
    private Dialog dialog() { return ReflectionHelpers.getField(preview, "mDialog"); }
    private void tapPreview(float x, float y) {
        View decor = dialog().getWindow().getDecorView();
        layout(decor, 480, 800);
        touch(decor, MotionEvent.ACTION_DOWN, x, y);
        touch(decor, MotionEvent.ACTION_UP, x, y);
    }
    private float progress() { return ReflectionHelpers.getField(overlay(), "progress"); }
    private static void idle(long millis) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static void touch(View view, int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
}
