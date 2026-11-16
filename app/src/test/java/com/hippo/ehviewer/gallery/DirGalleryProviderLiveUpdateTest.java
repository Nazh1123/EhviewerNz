package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.graphics.Bitmap;
import com.hippo.lib.glview.image.ImageWrapper;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;
import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE)
public class DirGalleryProviderLiveUpdateTest {
    private final LiveProvider provider = new LiveProvider();
    private Thread decoder;

    @After public void cleanup() throws Exception {
        if (decoder != null) { decoder.interrupt(); decoder.join(5000); assertFalse(decoder.isAlive()); }
        provider.stop();
    }

    @Test public void appendRetainsCachedImagesAndPublishesOnlyAnIncrementalUpdate() throws Exception {
        start("10.png", "20.png");
        provider.request(0);
        assertEquals(Integer.valueOf(0), provider.successes.poll(5, TimeUnit.SECONDS));
        assertEquals(1, provider.decodes);
        provider.update("10.png", "20.png", "30.png");
        assertArrayEquals(new int[]{0, 1}, (int[]) provider.changes.poll(5, TimeUnit.SECONDS));
        assertEquals(3, provider.size());
        assertTrue(provider.evictions.isEmpty());
        provider.request(0);
        assertEquals(Integer.valueOf(0), provider.successes.poll(5, TimeUnit.SECONDS));
        assertEquals(1, provider.decodes);
    }

    @Test public void outOfOrderDownloadsInvalidateOnlyShiftedCachePositions() throws Exception {
        start("10.png", "30.png");
        provider.update("10.png", "20.png", "30.png");
        assertArrayEquals(new int[]{0, 2}, (int[]) provider.changes.poll(5, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(1), provider.evictions.poll());
        assertTrue(provider.evictions.isEmpty());
        assertEquals("20.png", provider.getFilename(1));
    }

    @Test public void removingAnImageUsesTheFullRefreshPath() throws Exception {
        start("10.png", "20.png");
        provider.update("20.png");
        assertEquals("full", provider.changes.poll(5, TimeUnit.SECONDS));
        assertEquals(1, provider.size());
    }

    @Test public void missingSlotsWaitAndCompleteIndividuallyWithoutChangingThePageCount() throws Exception {
        provider.fixed = true;
        start("10.png", null, null);
        provider.request(0);
        assertEquals(Integer.valueOf(0), provider.successes.poll(5, TimeUnit.SECONDS));
        provider.request(2);
        assertEquals(Integer.valueOf(2), provider.missing.poll(5, TimeUnit.SECONDS));
        assertEquals(3, provider.size());
        assertNull(provider.getFilename(2));
        assertNull(provider.getPreviewFile(2));
        assertFalse(provider.save(2, (UniFile) null));
        provider.update("10.png", null, "30.png");
        assertEquals(Integer.valueOf(2), provider.changes.poll(5, TimeUnit.SECONDS));
        assertEquals(3, provider.size());
        provider.request(2); // The visible slot requests its source on the page-specific callback.
        assertEquals(Integer.valueOf(2), provider.successes.poll(5, TimeUnit.SECONDS));
        provider.request(0);
        assertEquals(Integer.valueOf(0), provider.successes.poll(5, TimeUnit.SECONDS));
        assertEquals(2, provider.decodes); // The original visible image stayed cached.
        assertTrue(provider.changes.isEmpty());
    }

    @Test public void fixedSelectionCanStartEntirelyEmptyAndFillOutOfOrder() throws Exception {
        provider.fixed = true;
        start(null, null, null);
        assertEquals(3, provider.size());
        provider.update(null, "20.png", null);
        assertEquals(Integer.valueOf(1), provider.changes.poll(5, TimeUnit.SECONDS));
        provider.update("10.png", "20.png", null);
        assertEquals(Integer.valueOf(0), provider.changes.poll(5, TimeUnit.SECONDS));
        assertEquals(3, provider.size());
    }

    private void start(String... names) throws Exception {
        provider.update(names);
        decoder = new Thread(provider);
        decoder.start();
        assertEquals("full", provider.changes.poll(5, TimeUnit.SECONDS));
    }

    private static class LiveProvider extends DirGalleryProvider {
        volatile UniFile[] files;
        volatile long version;
        volatile int decodes;
        boolean fixed;
        final BlockingQueue<Object> changes = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> successes = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> evictions = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> missing = new LinkedBlockingQueue<>();
        void update(String... names) {
            UniFile[] next = new UniFile[names.length];
            for (int i = 0; i < names.length; i++) next[i] = names[i] != null ? UniFile.fromFile(new File(names[i])) : null;
            files = next;
            version++;
        }
        @Override protected UniFile[] loadImageFiles() { return files; }
        @Override protected long imageFilesVersion() { return version; }
        @Override protected boolean hasFixedPagePositions() { return fixed; }
        @Override protected void onMissingFile(int index) { missing.add(index); super.onMissingFile(index); }
        @Override public void notifyDataChanged() { changes.add("full"); }
        @Override public void notifyDataChanged(int[] positions) { changes.add(positions); }
        @Override public void notifyDataChanged(int index) { changes.add(index); }
        @Override public void removeCache(int index) { evictions.add(index); super.removeCache(index); }
        @Override protected Image decodeImage(UniFile file, int index) {
            decodes++;
            return Image.create(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888));
        }
        @Override public void notifyPageSucceed(int index, ImageWrapper image) {
            super.notifyPageSucceed(index, image);
            successes.add(index);
        }
    }
}
