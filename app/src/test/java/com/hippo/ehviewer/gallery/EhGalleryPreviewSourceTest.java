package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.unifile.UniFile;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EhGalleryPreviewSourceTest {
    @Test public void downloadedPagesUseLocalFilesAndPreserveFilteredSourceIndices() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File folder = new File(context.getCacheDir(), "download-preview-" + UUID.randomUUID());
        assertTrue(folder.mkdir());
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        File downloaded = new File(folder, "00000121.png");
        try (FileOutputStream out = new FileOutputStream(downloaded)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        AtomicInteger listings = new AtomicInteger(), readerRequests = new AtomicInteger();
        EhGalleryProvider provider = new EhGalleryProvider(context, new GalleryInfo()) {
            @Override protected UniFile getPreviewDirectory() {
                listings.incrementAndGet();
                return UniFile.fromFile(folder);
            }
            @Override public int getSourcePage(int index) { return index + 120; }
            @Override protected void onRequest(int index) { readerRequests.incrementAndGet(); }
        };
        try (ReaderPreviewSource source = provider.createPreviewSource(context)) {
            assertEquals(downloaded.getPath(), provider.getPreviewFile(0).getUri().getPath());
            assertNotNull(source.load(0, 128));
            assertNull(source.load(1, 128));
            assertEquals(1, listings.get());
            assertEquals(0, readerRequests.get());
        } finally {
            // ImageDecoder maps the file in native Robolectric; defer its deletion on Windows.
            downloaded.deleteOnExit();
            folder.deleteOnExit();
        }
    }

    @Test public void completedDownloadInvalidatesPreviouslyMissingPreview() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File folder = new File(context.getCacheDir(), "new-download-preview-" + UUID.randomUUID());
        assertTrue(folder.mkdir());
        File downloaded = new File(folder, "00000001.png");
        AtomicInteger listings = new AtomicInteger();
        EhGalleryProvider provider = new EhGalleryProvider(context, new GalleryInfo()) {
            @Override protected UniFile getPreviewDirectory() {
                listings.incrementAndGet();
                return UniFile.fromFile(folder);
            }
        };
        try {
            assertNull(provider.getPreviewFile(0));
            assertTrue(downloaded.createNewFile());
            provider.onPageSuccess(0, 1, 1, 1);
            assertEquals(downloaded.getPath(), provider.getPreviewFile(0).getUri().getPath());
            assertEquals(2, listings.get());
            provider.getPreviewFile(0);
            assertEquals("Repeated lookup rescanned the download directory", 2, listings.get());
        } finally { downloaded.delete(); folder.delete(); }
    }

    @Test public void galleriesWithoutADownloadReturnAMissWithoutCreatingFiles() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger lookups = new AtomicInteger();
        EhGalleryProvider provider = new EhGalleryProvider(context, new GalleryInfo()) {
            @Override protected UniFile getPreviewDirectory() { lookups.incrementAndGet(); return null; }
            @Override protected void onRequest(int index) { fail("Preview requested a reader download"); }
        };
        try (ReaderPreviewSource source = provider.createPreviewSource(context)) {
            assertNull(source.load(0, 128));
            assertNull(source.load(99, 128));
            assertEquals(1, lookups.get());
        }
    }
}
