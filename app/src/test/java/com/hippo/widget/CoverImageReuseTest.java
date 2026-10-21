package com.hippo.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import com.hippo.conaco.Conaco;
import com.hippo.conaco.ConacoTask;
import com.hippo.conaco.Unikery;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.lib.image.Image;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = CoverImageReuseTest.TestApplication.class,
        shadows = {CoverImageReuseTest.AppShadow.class, CoverImageReuseTest.LoaderShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoverImageReuseTest {
    private static Conaco<Image> loader;
    private static final List<ConacoTask.Builder<Image>> requests = new ArrayList<>();
    private static final String URL = "https://example.invalid/cover.jpg";
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        loader = Shadow.newInstanceOf(Conaco.class);
        requests.clear();
    }

    private Image image(int width, int height) {
        return Image.create(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
    }

    @Test public void thumbnailHandoffSurvivesSourceReleaseWithoutCopyingOrAnotherRequest() {
        LoadImageViewNew source = new LoadImageViewNew(context);
        source.load(EhCacheKeyFactory.getThumbKey(123), URL);
        Image image = image(1200, 1800); // Too large for the ordinary thumbnail memory cache.
        assertTrue(source.onGetValue(image, Conaco.SOURCE_NETWORK));
        LoadImageView.ImageSnapshot snapshot = source.snapshot();
        assertNotNull(snapshot);
        source.unload();
        assertFalse(image.isRecycled());
        LoadImageView target = new LoadImageView(context, false);
        assertTrue(target.adopt(snapshot));
        snapshot.close();
        target.loadKeepingImage(EhCacheKeyFactory.getThumbKey(123), URL, null);
        assertEquals(1, requests.size());
        assertTrue(target.hasImage());
        assertFalse(image.isRecycled());
        target.unload();
        assertTrue(image.isRecycled());
    }

    @Test public void replacementKeepsOldImageThroughMissWaitAndFailureThenSwapsOnSuccess() {
        LoadImageView target = new LoadImageView(context, false);
        target.loadKeepingImage("old", URL, null);
        Image old = image(100, 150);
        target.onGetValue(old, Conaco.SOURCE_NETWORK);
        Drawable displayed = target.getDrawable();
        target.loadKeepingImage("new", URL + "/new", null);
        target.onMiss(Conaco.SOURCE_MEMORY);
        target.onWait();
        target.onFailure();
        assertSame(displayed, target.getDrawable());
        assertFalse(old.isRecycled());
        // Failure remains retryable, and duplicate in-flight binds do not restart it.
        target.loadKeepingImage("new", URL + "/new", null);
        target.loadKeepingImage("new", URL + "/new", null);
        assertEquals(3, requests.size());
        Image updated = image(200, 300);
        target.onGetValue(updated, Conaco.SOURCE_NETWORK);
        assertNotSame(displayed, target.getDrawable());
        assertTrue(old.isRecycled());
        target.unload();
        assertTrue(updated.isRecycled());
    }

    @Test public void bothViewsOwnTheirImageAndIndependentDrawableState() {
        LoadImageView source = new LoadImageView(context, false);
        source.loadKeepingImage("cover", URL, null);
        Image image = image(100, 150);
        source.onGetValue(image, Conaco.SOURCE_NETWORK);
        try (LoadImageView.ImageSnapshot snapshot = source.snapshot()) {
            LoadImageView target = new LoadImageView(context, false);
            assertTrue(target.adopt(snapshot));
            assertNotSame(source.getDrawable(), target.getDrawable());
            target.unload();
            assertFalse(image.isRecycled());
        }
        source.unload();
        assertTrue(image.isRecycled());
    }

    @Test public void coverIdentityAvoidsHostOnlyReloadsAndDistinguishesUpdatesAndSizes() {
        String file = "0123456789012345678901234567890123456789-1000-1000-1500-jpg_250.jpg";
        String a = "https://ehgt.org/01/23/" + file;
        String b = "https://exhentai.org/t/01/23/" + file;
        assertFalse(EhCacheKeyFactory.needsCoverUpdate(a, b));
        String larger = b.replace("_250.jpg", "_300.jpg");
        assertTrue(EhCacheKeyFactory.needsCoverUpdate(a, larger));
        assertFalse(EhCacheKeyFactory.needsCoverUpdate(larger, a));
        assertNotEquals(EhCacheKeyFactory.getCoverKey(123, a), EhCacheKeyFactory.getCoverKey(123, larger));
        assertFalse(EhCacheKeyFactory.needsCoverUpdate(a, ""));
        assertTrue(EhCacheKeyFactory.needsCoverUpdate(a, "https://example.invalid/changed.jpg"));
    }

    public static class TestApplication extends EhApplication {
        @Override public void onCreate() {}
    }

    @Implements(EhApplication.class) public static class AppShadow extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected static Conaco<Image> getConaco(Context context) { return loader; }
    }

    @Implements(Conaco.class) public static class LoaderShadow {
        @Implementation protected void load(ConacoTask.Builder<Image> builder) {
            requests.add(builder);
            builder.getUnikery().setTaskId(requests.size());
        }
        @Implementation protected void cancel(Unikery<Image> view) { view.setTaskId(Unikery.INVALID_ID); }
    }
}
