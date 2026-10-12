package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ReaderPreviewBitmapCacheTest {
    @Test public void boundsLimitLargeAndExtremelyTallImages() {
        assertEquals(8, ReaderPreviewBitmapCache.sampleSize(2400, 3600, 512));
        assertEquals(1, ReaderPreviewBitmapCache.sampleSize(100, 150, 512));
        int sample = ReaderPreviewBitmapCache.sampleSize(1000, 100000, 320);
        assertTrue(100000 / sample <= 320);
        assertEquals(0, sample & (sample - 1));
    }

    @Test public void posterIsReusedFromDiskWithoutOpeningOriginal() throws Exception {
        ReflectionHelpers.setStaticField(ReaderPreviewBitmapCache.class, "cache", null);
        Context context = RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png));
        String identity = UUID.randomUUID().toString();
        AtomicInteger opens = new AtomicInteger();
        ReaderPreviewBitmapCache.Result first = ReaderPreviewBitmapCache.load(context, identity, 128, () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(png.toByteArray());
        });
        assertNotNull(first);
        assertEquals(2, opens.get());
        first.persist(context);
        ReaderPreviewBitmapCache.Result cached = ReaderPreviewBitmapCache.load(context, identity, 128,
                () -> { throw new AssertionError("Disk hit opened original"); });
        assertNotNull(cached);
        assertEquals(first.bitmap.getWidth(), cached.bitmap.getWidth());
        assertNull(ReaderPreviewBitmapCache.cached(context, identity, 256));
    }
}
