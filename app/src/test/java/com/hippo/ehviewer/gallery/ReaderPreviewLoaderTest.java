package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import com.hippo.unifile.UniFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ReaderPreviewLoaderTest {
    @Test public void slowNeighborDoesNotBlockANewCenterAndSourcesRemainIndependent() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster();
        String identity = UUID.randomUUID().toString();
        CountDownLatch neighborStarted = new CountDownLatch(1);
        CountDownLatch resumeNeighbor = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public int getPreviewConcurrency() { return 2; }
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                opened.incrementAndGet();
                return new ReaderPreviewSource() {
                    @Override public ReaderPreviewBitmapCache.Result load(int page, int edge) throws IOException {
                        calls.computeIfAbsent(page, key -> new AtomicInteger()).incrementAndGet();
                        if (page == 49) {
                            neighborStarted.countDown();
                            try { assertTrue(resumeNeighbor.await(10, TimeUnit.SECONDS)); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                        }
                        return ReaderPreviewBitmapCache.load(app, identity + page, edge,
                                () -> new ByteArrayInputStream(png));
                    }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
        ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { });
        try {
            loader.request(new int[] {50, 49, 51, 48, 52}, 128, true);
            assertTrue(neighborStarted.await(3, TimeUnit.SECONDS));
            waitFor(() -> loader.get(50) != null);
            loader.request(new int[] {100, 99, 101, 98, 102}, 128, true);
            waitFor(() -> loader.get(100) != null);
            assertEquals("New center waited for a stale neighbor", 1, resumeNeighbor.getCount());
            assertEquals(2, opened.get());
            // A claimed slow page in an overlapping update is not loaded by the other source too.
            loader.request(new int[] {49, 48, 50, 47, 51}, 128, true);
            resumeNeighbor.countDown();
            waitFor(() -> loader.get(49) != null && !loader.isLoading());
            assertEquals(1, calls.get(49).get());
        } finally { resumeNeighbor.countDown(); loader.close(); }
        waitFor(() -> closed.get() == opened.get());
    }

    @Test public void pendingWindowLeavesCapacityForLatestTargetInsteadOfAllOldNeighbors() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster();
        String identity = UUID.randomUUID().toString();
        CountDownLatch windowStarted = new CountDownLatch(2), resumeCenter = new CountDownLatch(1);
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    calls.computeIfAbsent(page, key -> new AtomicInteger()).incrementAndGet();
                    if (page == 50 || page == 49) {
                        windowStarted.countDown();
                        try { assertTrue(resumeCenter.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    }
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge, () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.request(new int[] {50, 49, 51, 48, 52}, 128, true);
            assertTrue(windowStarted.await(3, TimeUnit.SECONDS));
            loader.request(new int[] {100, 99, 101, 98, 102}, 128, true);
            waitFor(() -> loader.get(100) != null && loader.get(102) != null);
            assertEquals("New center waited for an obsolete decode", 1, resumeCenter.getCount());
            for (int neighbor : new int[] {51, 48, 52}) assertFalse(calls.containsKey(neighbor));
        } finally { resumeCenter.countDown(); }
    }

    @Test public void failedPageDoesNotRetryForeverOrPreventOtherCardsLoading() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster();
        String identity = UUID.randomUUID().toString();
        AtomicInteger attempts = new AtomicInteger();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    if (page == 0) { attempts.incrementAndGet(); throw new IOException("Broken image fixture"); }
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge,
                            () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.request(new int[] {0, 1, 2}, 128, true);
            waitFor(() -> loader.get(1) != null && loader.get(2) != null && !loader.isLoading());
            assertEquals(1, attempts.get());
            loader.request(new int[] {0, 1, 2}, 128, true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, attempts.get());
            assertNull(loader.get(0));
        }
    }

    private static byte[] poster() {
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png));
        return png.toByteArray();
    }

    private static void waitFor(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("Preview work did not finish", ready.getAsBoolean());
    }
}
