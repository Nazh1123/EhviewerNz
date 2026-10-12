package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderPreviewBitmapCacheTest {
    @Test public void decodedReaderPosterAvoidsOpeningOriginalAndCanBePersisted() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String identity = UUID.randomUUID().toString();
        Bitmap poster = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context, identity, 128,
                Uri.parse("file:///unavailable-original.png"),
                () -> { throw new AssertionError("Reused original opened source"); }, () -> poster);
        assertSame(poster, result.bitmap);
        result.persist(context);
        assertNotNull(ReaderPreviewBitmapCache.cached(context, identity, 128));
    }
    @Test public void boundsLimitLargeAndExtremelyTallImages() {
        assertEquals(8, ReaderPreviewBitmapCache.sampleSize(2400, 3600, 512));
        assertEquals(1, ReaderPreviewBitmapCache.sampleSize(100, 150, 512));
        int sample = ReaderPreviewBitmapCache.sampleSize(1000, 100000, 320);
        assertTrue(100000 / sample <= 320);
        assertEquals(0, sample & (sample - 1));
        assertEquals(4, ReaderPreviewBitmapCache.sampleSize(513, 257, 256));
        assertEquals(1 << 30, ReaderPreviewBitmapCache.sampleSize(Integer.MAX_VALUE, 1, 1));
        // On a 3x density device the smaller preview target doubles this sample factor.
        assertEquals(16, ReaderPreviewBitmapCache.sampleSize(2400, 3600, 330));
        assertEquals(32, ReaderPreviewBitmapCache.sampleSize(2400, 3600, 216));
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

    @Test public void opaquePosterUsesJpegAndTransparentPosterPreservesAlpha() throws Exception {
        ReflectionHelpers.setStaticField(ReaderPreviewBitmapCache.class, "cache", null);
        Context context = RuntimeEnvironment.getApplication();
        for (boolean alpha : new boolean[] {false, true}) {
            Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(alpha ? 0x80804020 : 0xFF804020);
            bitmap.setHasAlpha(alpha);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png));
            String identity = UUID.randomUUID().toString();
            ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context, identity, 128,
                    () -> new ByteArrayInputStream(png.toByteArray()));
            assertNotNull(result);
            result.persist(context);
            com.hippo.beerbelly.SimpleDiskCache disk = ReflectionHelpers.getStaticField(ReaderPreviewBitmapCache.class, "cache");
            String key = ReflectionHelpers.getField(result, "key");
            com.hippo.streampipe.InputStreamPipe pipe = disk.getInputStreamPipe(key);
            assertNotNull(pipe);
            try {
                pipe.obtain();
                java.io.InputStream input = pipe.open();
                assertEquals(alpha ? 0x89 : 0xFF, input.read());
                assertEquals(alpha ? 'P' : 0xD8, input.read());
            } finally { pipe.close(); pipe.release(); }
            ReaderPreviewBitmapCache.Result restored = ReaderPreviewBitmapCache.cached(context, identity, 128);
            assertNotNull(restored);
            assertEquals(alpha, restored.bitmap.hasAlpha());
            if (alpha) assertEquals(0x80, android.graphics.Color.alpha(restored.bitmap.getPixel(0, 0)));
        }
    }

    @Test public void largeImageIsDecodedWithinPreviewBounds() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(1025, 1537, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpeg));
        ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context, UUID.randomUUID().toString(), 256,
                () -> new ByteArrayInputStream(jpeg.toByteArray()));
        assertNotNull(result);
        assertTrue(result.bitmap.getWidth() <= 256);
        assertTrue(result.bitmap.getHeight() <= 256);
    }

    @Test public void longImageHeaderDecodesWithIndependentStreams() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpeg));
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        byte[] original = jpeg.toByteArray();
        padded.write(original, 0, 2); // SOI
        padded.write(new byte[] {(byte) 0xFF, (byte) 0xEF, (byte) 0xFF, (byte) 0xFF}); // APP15
        padded.write(new byte[65533]);
        padded.write(original, 2, original.length - 2);
        AtomicInteger opens = new AtomicInteger();
        ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context, UUID.randomUUID().toString(), 128,
                () -> { opens.incrementAndGet(); return new ByteArrayInputStream(padded.toByteArray()); });
        assertNotNull(result);
        assertEquals(2, opens.get());
        assertEquals(64, result.bitmap.getWidth());
        assertEquals(48, result.bitmap.getHeight());
    }

    @Test public void localFilesUseStaticDecoderForJpegPngWebpAndGif() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(1025, 1537, Bitmap.Config.ARGB_8888);
        for (Bitmap.CompressFormat format : new Bitmap.CompressFormat[] {
                Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP}) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(format, 90, encoded));
            File file = imageFile(context, encoded.toByteArray());
            try {
                ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context,
                        UUID.randomUUID().toString(), 128, Uri.fromFile(file),
                        () -> { throw new AssertionError("URI decoder unnecessarily reopened image"); });
                assertTrue(Math.max(result.bitmap.getWidth(), result.bitmap.getHeight()) <= 128);
                assertFalse(result.bitmap.getConfig() == Bitmap.Config.HARDWARE);
            } finally { deleteMappedFixture(file); }
        }
        byte[] gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAUEBAAAACH5BAAAAAAALAAAAAABAAEAAAICRAEAOw==");
        File file = imageFile(context, gif);
        try {
            ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context,
                    UUID.randomUUID().toString(), 128, Uri.fromFile(file),
                    () -> { throw new AssertionError("GIF decoder unnecessarily reopened image"); });
            assertEquals(1, result.bitmap.getWidth());
            assertEquals(1, result.bitmap.getHeight());
        } finally { deleteMappedFixture(file); }
    }

    @Test public void unavailableUriFallsBackToIndependentStreams() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        AtomicInteger opens = new AtomicInteger();
        ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context,
                UUID.randomUUID().toString(), 128, Uri.fromFile(new File(context.getCacheDir(), "missing-" + UUID.randomUUID())),
                () -> { opens.incrementAndGet(); return new ByteArrayInputStream(encoded.toByteArray()); });
        assertNotNull(result);
        assertEquals(2, opens.get());
        assertEquals(64, result.bitmap.getWidth());
    }

    @Test public void fileDecoderRespectsExifRotation() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded));
        byte[] jpeg = encoded.toByteArray();
        ByteArrayOutputStream oriented = new ByteArrayOutputStream();
        oriented.write(jpeg, 0, 2);
        // APP1, Exif, little-endian TIFF, one SHORT orientation tag with value 6 (90 degrees).
        oriented.write(new byte[] {(byte) 0xFF, (byte) 0xE1, 0, 34,
                'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0,
                1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0});
        oriented.write(jpeg, 2, jpeg.length - 2);
        File file = imageFile(context, oriented.toByteArray());
        try {
            ReaderPreviewBitmapCache.Result result = ReaderPreviewBitmapCache.load(context,
                    UUID.randomUUID().toString(), 128, Uri.fromFile(file),
                    () -> { throw new AssertionError("Rotated JPEG unexpectedly used stream fallback"); });
            assertTrue(result.bitmap.getWidth() <= 128);
            assertEquals(result.bitmap.getHeight() * 2, result.bitmap.getWidth());
        } finally { deleteMappedFixture(file); }
    }

    @Test public void corruptImageReportsFailureAndIsNotCached() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String identity = UUID.randomUUID().toString();
        File file = imageFile(context, new byte[] {1, 2, 3});
        try {
            try {
                ReaderPreviewBitmapCache.load(context, identity, 128, Uri.fromFile(file),
                        () -> new java.io.FileInputStream(file));
                fail("Corrupt image should report a decode failure");
            } catch (java.io.IOException failure) {
                assertTrue(failure.getMessage().contains("dimensions"));
                assertEquals(1, failure.getSuppressed().length);
            }
            assertNull(ReaderPreviewBitmapCache.cached(context, identity, 128));
        } finally { deleteMappedFixture(file); }
    }

    private static void deleteMappedFixture(File file) {
        if (file.delete()) return;
        // Android permits unlinking a mapped file. Windows keeps the fixture locked until the
        // MappedByteBuffer is collected; this is unrelated to the decoder's assertions above.
        assertTrue(System.getProperty("os.name").startsWith("Windows"));
        file.deleteOnExit();
    }

    private static File imageFile(Context context, byte[] bytes) throws Exception {
        File file = File.createTempFile("preview-test-", ".image", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        return file;
    }
}
