package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Size;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, shadows = ReaderPreviewProviderThumbnailTest.ThumbnailResolver.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderPreviewProviderThumbnailTest {
    @Test public void providerThumbnailAvoidsOpeningOriginalAndStaysWithinPreviewBounds() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ThumbnailResolver.poster = Bitmap.createBitmap(256, 512, Bitmap.Config.ARGB_8888);
        ThumbnailResolver.calls = 0;
        try {
            ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context,
                    UUID.randomUUID().toString(), 128, Uri.parse("content://preview.fixture/image"),
                    () -> { throw new AssertionError("Provider thumbnail reopened original"); });
            assertEquals(1, ThumbnailResolver.calls);
            assertEquals(64, result.bitmap.getWidth());
            assertEquals(128, result.bitmap.getHeight());
            assertTrue(result.bitmap.hasAlpha());
        } finally { ThumbnailResolver.poster = null; }
    }

    @Test public void providerWithoutThumbnailsFallsBackToSampledOriginal() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ThumbnailResolver.poster = null;
        ThumbnailResolver.calls = 0;
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        AtomicInteger opens = new AtomicInteger();
        ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context,
                UUID.randomUUID().toString(), 128, Uri.parse("content://preview.fixture/unsupported"),
                () -> { opens.incrementAndGet(); return new ByteArrayInputStream(encoded.toByteArray()); });
        assertNotNull(result);
        assertEquals(1, ThumbnailResolver.calls);
        assertEquals(2, opens.get());
    }

    @Implements(ContentResolver.class)
    public static class ThumbnailResolver extends ShadowContentResolver {
        static Bitmap poster;
        static int calls;
        @Implementation protected Bitmap loadThumbnail(Uri uri, Size size, CancellationSignal signal) throws IOException {
            calls++;
            assertEquals(128, size.getWidth());
            assertEquals(128, size.getHeight());
            if (poster == null) throw new IOException("Provider has no thumbnail");
            return poster;
        }
    }
}
