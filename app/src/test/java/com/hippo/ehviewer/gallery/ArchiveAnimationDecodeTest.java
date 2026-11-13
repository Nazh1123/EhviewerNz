package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.net.Uri;
import com.hippo.ehviewer.Settings;
import com.hippo.lib.image.Image;
import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28,
        shadows = ArchiveAnimationDecodeTest.NativeDecoder.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArchiveAnimationDecodeTest {
    private Context context;
    private ArchiveGalleryProvider provider;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                context.getSharedPreferences("archive-animation", Context.MODE_PRIVATE));
        Settings.putReadingDirection(0);
        Settings.putExperimentalAnimatedWebpEnabled(false);
        Image.Companion.setScreenWidth(1080);
        Image.Companion.setScreenHeight(1920);
        NativeDecoder.calls = 0;
        provider = new ArchiveGalleryProvider(context, Uri.fromFile(new File(context.getCacheDir(), "book.cbz")));
    }

    @After public void cleanup() {
        provider.stop();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    private byte[] animation(String name) throws IOException {
        return Files.readAllBytes(new File("src/androidTest/assets/animation", name).toPath());
    }

    @Test public void disabledControlsKeepGifAndWebpAnimatedAndRecognizeTheirSource() throws Exception {
        for (String name : new String[]{"disposal.gif", "blend.webp"}) {
            Image image = provider.decodeImage(new ByteArrayInputStream(animation(name)), 0);
            assertNotNull(name, image);
            try {
                assertTrue(name, image.getAnimated());
                assertTrue(name, image.getControllableAnimationSource());
                assertFalse(name, image.getAnimationControlRequested());
            } finally { image.recycle(); }
        }
        assertEquals(0, NativeDecoder.calls);
    }

    @Test public void enablingControlsAndPerPageFallbackUseTheSharedDecoderModes() throws Exception {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        for (String name : new String[]{"disposal.gif", "blend.webp"}) {
            byte[] bytes = animation(name);
            provider.setAnimatedWebpDecodeMode(0, Image.ANIMATED_WEBP_MODE_DEFAULT);
            Image image = provider.decodeImage(new ByteArrayInputStream(bytes), 0);
            assertNotNull(image);
            try {
                assertTrue(image.getAnimationControlRequested());
                assertTrue(image.getAnimated()); // Native decoder failure still preserves system playback.
                assertEquals(1, NativeDecoder.sample);
                assertArrayEquals(bytes, NativeDecoder.bytes);
            } finally { image.recycle(); }

            provider.setAnimatedWebpDecodeMode(0, Image.ANIMATED_WEBP_MODE_SAMPLE_2);
            image = provider.decodeImage(new ByteArrayInputStream(bytes), 0);
            assertNotNull(image);
            try {
                assertTrue(image.getAnimationControlRequested());
                assertEquals(name.endsWith("webp") ? 2 : 1, NativeDecoder.sample);
            } finally { image.recycle(); }

            int calls = NativeDecoder.calls;
            provider.setAnimatedWebpDecodeMode(0, Image.ANIMATED_WEBP_MODE_SYSTEM);
            image = provider.decodeImage(new ByteArrayInputStream(bytes), 0);
            assertNotNull(image);
            try {
                assertFalse(image.getAnimationControlRequested());
                assertTrue(image.getAnimated());
                assertTrue(image.getControllableAnimationSource());
                assertEquals(calls, NativeDecoder.calls);
            } finally { image.recycle(); }
        }
    }

    private byte[] staticImage(Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.BLUE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { assertTrue(bitmap.compress(format, 100, bytes)); }
        finally { bitmap.recycle(); }
        return bytes.toByteArray();
    }

    private ArchiveGalleryProvider directOnlyProvider() {
        Context directOnly = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { throw new AssertionError("Static decoding must not use a temporary file"); }
        };
        return new ArchiveGalleryProvider(directOnly, Uri.fromFile(new File(context.getCacheDir(), "direct.cbz")));
    }

    private InputStream shortReads(byte[] bytes) {
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override public boolean markSupported() { return false; }
            @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                return super.read(buffer, offset, Math.min(3, count));
            }
        };
    }

    @Test public void staticPngJpegAndWebpKeepDirectDecodingAndOriginalDimensions() throws Exception {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        // The file decoder would sample these images against this small screen.
        Image.Companion.setScreenWidth(16);
        Image.Companion.setScreenHeight(16);
        ArchiveGalleryProvider direct = directOnlyProvider();
        try {
            for (Bitmap.CompressFormat format : new Bitmap.CompressFormat[]{
                    Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP}) {
                Image image = direct.decodeImage(shortReads(staticImage(format)), 0);
                assertNotNull(format.toString(), image);
                try {
                    assertEquals(96, image.getWidth());
                    assertEquals(128, image.getHeight());
                    assertFalse(image.getAnimated());
                    assertFalse(image.getControllableAnimationSource());
                    assertEquals(0, NativeDecoder.calls);
                } finally { image.recycle(); }
            }
        } finally { direct.stop(); }
    }

    @Test public void animationHeadersSurviveShortReadsAndReachDecoderWithAllOriginalBytes() throws Exception {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        for (String name : new String[]{"disposal.gif", "blend.webp"}) {
            byte[] original = animation(name);
            Image image = provider.decodeImage(shortReads(original), 0);
            assertNotNull(name, image);
            try {
                assertTrue(image.getAnimationControlRequested());
                assertArrayEquals(original, NativeDecoder.bytes);
            } finally { image.recycle(); }
        }
    }

    @Test public void staticStreamIsDrainedIncludingBytesAfterTheImage() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(staticImage(Bitmap.CompressFormat.PNG));
        bytes.write(new byte[16384]); // Larger than the archive pipe's capacity.
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        ArchiveGalleryProvider direct = directOnlyProvider();
        try {
            Image image = direct.decodeImage(input, 0);
            assertNotNull(image);
            try { assertEquals(0, input.available()); }
            finally { image.recycle(); }
        } finally { direct.stop(); }
    }

    @Test public void shortOrInvalidHeadersDoNotRequireTemporaryStorage() throws Exception {
        ArchiveGalleryProvider direct = directOnlyProvider();
        try {
            for (byte[] header : new byte[][]{new byte[0], new byte[]{'G', 'I', 'F'},
                    new byte[]{'G', 'I', 'F', '8', '0', 'a'}}) {
                assertNull(direct.decodeImage(shortReads(header), 0));
            }
            byte[] staticExtendedWebp = new byte[21];
            System.arraycopy(new byte[]{'R', 'I', 'F', 'F'}, 0, staticExtendedWebp, 0, 4);
            System.arraycopy(new byte[]{'W', 'E', 'B', 'P', 'V', 'P', '8', 'X'}, 0, staticExtendedWebp, 8, 8);
            // VP8X alone is not an animation; the animation bit must also be present.
            assertNull(direct.decodeImage(shortReads(staticExtendedWebp), 0));
        } finally { direct.stop(); }
    }

    @Test public void readFailureAndStoppedReaderRemoveTheirTemporaryFiles() throws Exception {
        File[] before = temporaryPages();
        assertThrows(IOException.class, () -> provider.decodeImage(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Broken entry"); }
        }, 0));
        assertArrayEquals(before, temporaryPages());
        byte[] gif = animation("disposal.gif");
        assertThrows(IOException.class, () -> provider.decodeImage(new FilterInputStream(new ByteArrayInputStream(gif)) {
            private int position;
            @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                if (position >= 21) {
                    assertEquals(before.length + 1, temporaryPages().length);
                    throw new IOException("Animation entry interrupted after header");
                }
                int read = super.read(buffer, offset, Math.min(count, 21 - position));
                if (read > 0) position += read;
                return read;
            }
        }, 0));
        assertArrayEquals(before, temporaryPages());
        provider.stop();
        assertThrows(IOException.class, () -> provider.decodeImage(new ByteArrayInputStream(new byte[8192]), 0));
        assertArrayEquals(before, temporaryPages());
    }

    @Test public void savingAndIndependentProcessingSourcesKeepOriginalBytesAfterMixedDecoding() throws Exception {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        String[] names = {"chapter/001.png", "chapter/002.gif", "chapter/003.webp"};
        byte[][] originals = {staticImage(Bitmap.CompressFormat.PNG), animation("disposal.gif"), animation("blend.webp")};
        ArchiveGalleryProvider archive = new ArchiveGalleryProvider(context,
                Uri.fromFile(new File(context.getCacheDir(), "mixed.cbz")), () -> new ArchivePageSource() {
                    @Override String[] names() { return names; }
                    @Override void extract(int page, OutputStream output) throws IOException { output.write(originals[page]); }
                    @Override public void close() { }
                });
        GalleryProvider2 translation = archive.createTranslationProvider(context);
        GalleryProvider2 superResolution = archive.createTranslationProvider(context);
        File output = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-save-test-").toFile();
        try {
            for (int page = 0; page < names.length; page++) {
                Image image = archive.decodeImage(shortReads(originals[page]), page);
                assertNotNull(image);
                image.recycle();
                GalleryProvider2.SaveResult saved = archive.saveWithResult(page, UniFile.fromFile(output), "saved-" + page);
                assertNotNull(saved);
                assertEquals("saved-" + page + names[page].substring(names[page].lastIndexOf('.')), saved.file.getName());
                assertArrayEquals(originals[page], Files.readAllBytes(new File(saved.file.getUri().getPath()).toPath()));
                for (GalleryProvider2 processing : new GalleryProvider2[]{translation, superResolution}) {
                    File source = new File(output, "processing.image");
                    assertTrue(processing.save(page, UniFile.fromFile(source)));
                    assertArrayEquals(originals[page], Files.readAllBytes(source.toPath()));
                    assertEquals(names[page], processing.getResultPageName(page));
                }
                assertEquals(names[page], archive.getResultPageName(page));
            }
        } finally {
            archive.stop(); translation.stop(); superResolution.stop();
            for (File file : output.listFiles()) file.delete();
            output.delete();
        }
    }

    @Test public void mixedPagesContinueThroughTheArchivePipeAfterStaticImageTrailingBytes() throws Exception {
        Settings.putExperimentalAnimatedWebpEnabled(true);
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        padded.write(staticImage(Bitmap.CompressFormat.PNG));
        padded.write(new byte[16384]);
        byte[][] pages = {padded.toByteArray(), animation("disposal.gif"),
                staticImage(Bitmap.CompressFormat.WEBP), animation("blend.webp")};
        LinkedBlockingQueue<Image> decoded = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> failures = new LinkedBlockingQueue<>();
        ArchiveGalleryProvider archive = new ArchiveGalleryProvider(context,
                Uri.fromFile(new File(context.getCacheDir(), "mixed-pipe.cbz")), () -> new ArchivePageSource() {
                    @Override String[] names() { return new String[]{"1.png", "2.gif", "3.webp", "4.webp"}; }
                    @Override void extract(int page, OutputStream output) throws IOException { output.write(pages[page]); }
                    @Override public void close() { }
                }) {
                    @Override public void notifyPageSucceed(int index, Image image) { decoded.add(image); }
                    @Override public void notifyPageFailed(int index, String error) { failures.add(index + ": " + error); }
                };
        try {
            archive.start();
            for (int page = 0; page < pages.length; page++) {
                archive.request(page);
                Image image = decoded.poll(5, TimeUnit.SECONDS);
                assertNotNull("Page " + page + " blocked or failed: " + failures, image);
                try { assertEquals(page % 2 == 1, image.getControllableAnimationSource()); }
                finally { image.recycle(); }
            }
            assertTrue(failures.toString(), failures.isEmpty());
        } finally {
            archive.stop();
            for (Image image : decoded) image.recycle();
        }
    }

    private File[] temporaryPages() {
        return context.getCacheDir().listFiles((dir, name) -> name.startsWith("archive-reader-"));
    }

    /** Exercise native-decoder selection and its fallback without loading Android JNI on Windows. */
    @Implements(value = Image1.Companion.class, isInAndroidSdk = false)
    public static class NativeDecoder {
        static int calls;
        static int sample;
        static byte[] bytes;
        @Implementation protected Image1 decode(InputStream input, boolean partially, int sampleSize) throws IOException {
            assertTrue(input instanceof FileInputStream);
            assertFalse(partially);
            calls++;
            sample = sampleSize;
            bytes = input.readAllBytes();
            return null;
        }
    }
}
