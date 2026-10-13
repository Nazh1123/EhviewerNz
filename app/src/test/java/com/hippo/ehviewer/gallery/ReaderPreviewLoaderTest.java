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
    @Test public void resizingInvalidatesCachedPosters() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster(1024, 768);
        String identity = UUID.randomUUID().toString();
        AtomicInteger reads = new AtomicInteger();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    reads.incrementAndGet();
                    return ReaderPreviewBitmapCache.load(app, identity, edge, () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.request(new int[] {0}, 128);
            waitFor(() -> loader.get(0) != null && !loader.isLoading());
            assertEquals(128, loader.get(0).getWidth());
            android.util.LruCache<Integer, Bitmap> detailed = org.robolectric.util.ReflectionHelpers.getField(loader, "posters");
            detailed.evictAll();
            assertNull("Evicted posters have no extra small-image fallback", loader.get(0));
            loader.request(new int[] {0}, 256);
            waitFor(() -> loader.get(0) != null && loader.get(0).getWidth() == 256 && !loader.isLoading());
            assertEquals(2, reads.get());
            loader.request(new int[] {0}, 128);
            waitFor(() -> loader.get(0) != null && loader.get(0).getWidth() == 128 && !loader.isLoading());
            assertEquals(3, reads.get());
        }
    }

    @Test public void resizeDiscardsInFlightPostersAndCallbacks() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster(1024, 768);
        String identity = UUID.randomUUID().toString();
        CountDownLatch started = new CountDownLatch(1), finishOldSize = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        java.util.List<Integer> delivered = new java.util.ArrayList<>();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public int getPreviewConcurrency() { return 1; }
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    if (reads.incrementAndGet() == 1) {
                        started.countDown();
                        try { assertTrue(finishOldSize.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    }
                    return ReaderPreviewBitmapCache.load(app, identity, edge, () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider,
                (page, bitmap) -> delivered.add(bitmap.getWidth()))) {
            loader.request(new int[] {0}, 128);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            loader.request(new int[] {0}, 256);
            finishOldSize.countDown();
            waitFor(() -> !delivered.isEmpty() && !loader.isLoading());
            assertEquals(java.util.Collections.singletonList(256), delivered);
            assertEquals(256, loader.get(0).getWidth());
            assertEquals(2, reads.get());
        } finally { finishOldSize.countDown(); }
    }

    @Test public void onlyRequestedPagesAreLoaded() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster(256, 128);
        String identity = UUID.randomUUID().toString();
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    calls.computeIfAbsent(page, key -> new AtomicInteger()).incrementAndGet();
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge,
                            () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            assertFalse(loader.isLoading());
            assertTrue(calls.isEmpty());
            loader.request(new int[] {30, 29, 31}, 128);
            waitFor(() -> loader.get(29) != null && loader.get(30) != null
                    && loader.get(31) != null && !loader.isLoading());
            assertEquals(3, calls.size());
            assertNull(loader.get(0));
            assertNull(loader.get(63));
            loader.request(new int[] {30, 29, 31}, 128);
            assertFalse(loader.isLoading());
            for (AtomicInteger count : calls.values()) assertEquals(1, count.get());
        }
    }

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
            loader.request(new int[] {50, 49, 51, 48, 52}, 128);
            assertTrue(neighborStarted.await(3, TimeUnit.SECONDS));
            waitFor(() -> loader.get(50) != null);
            loader.request(new int[] {100, 99, 101, 98, 102}, 128);
            waitFor(() -> loader.get(100) != null);
            assertEquals("New center waited for a stale neighbor", 1, resumeNeighbor.getCount());
            assertEquals(2, opened.get());
            // A claimed slow page in an overlapping update is not loaded by the other source too.
            loader.request(new int[] {49, 48, 50, 47, 51}, 128);
            resumeNeighbor.countDown();
            waitFor(() -> loader.get(49) != null && !loader.isLoading());
            assertEquals(1, calls.get(49).get());
        } finally { resumeNeighbor.countDown(); loader.close(); }
        waitFor(() -> closed.get() == opened.get());
    }

    @Test public void allWorkersLoadNeighborsWhileTheCenterIsStillPending() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster();
        String identity = UUID.randomUUID().toString();
        CountDownLatch windowStarted = new CountDownLatch(4), resumeWindow = new CountDownLatch(1);
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    calls.computeIfAbsent(page, key -> new AtomicInteger()).incrementAndGet();
                    windowStarted.countDown();
                    try { assertTrue(resumeWindow.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge,
                            () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.request(new int[] {50, 49, 51, 48, 52}, 128);
            assertTrue("Neighbors waited for the center instead of using all four workers",
                    windowStarted.await(3, TimeUnit.SECONDS));
            assertNull(loader.get(50));
            assertEquals(4, calls.size());
            assertTrue(calls.containsKey(50));
            assertTrue(calls.containsKey(49));
            assertTrue(calls.containsKey(51));
            assertTrue(calls.containsKey(48));
            assertFalse(calls.containsKey(52));
            // After the viewport changes, a freed worker must take the new window,
            // rather than the fifth page from the obsolete window.
            loader.request(new int[] {100, 99, 101, 98, 102}, 128);
            resumeWindow.countDown();
            waitFor(() -> loader.get(100) != null && loader.get(102) != null && !loader.isLoading());
            assertFalse(calls.containsKey(52));
            for (AtomicInteger count : calls.values()) assertEquals(1, count.get());
        } finally { resumeWindow.countDown(); }
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
            loader.request(new int[] {0, 1, 2}, 128);
            waitFor(() -> loader.get(1) != null && loader.get(2) != null && !loader.isLoading());
            assertEquals(1, attempts.get());
            loader.request(new int[] {0, 1, 2}, 128);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, attempts.get());
            assertNull(loader.get(0));
            // The UI first prioritizes the center, then expands to the visible neighborhood.
            // A failure which stays in both windows must not be retried on every expansion.
            loader.request(new int[] {0}, 128);
            loader.request(new int[] {0, 1, 2, 3}, 128);
            waitFor(() -> loader.get(3) != null && !loader.isLoading());
            assertEquals(1, attempts.get());
            loader.stop();
            loader.request(new int[] {0}, 128);
            waitFor(() -> attempts.get() == 2 && !loader.isLoading());
        }
    }

    @Test public void aLateFailureFromAnOldWindowDoesNotPoisonARevisit() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); byte[] png = poster();
        String identity = UUID.randomUUID().toString();
        CountDownLatch started = new CountDownLatch(1), failOldPage = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    if (page == 0 && attempts.incrementAndGet() == 1) {
                        started.countDown();
                        try { assertTrue(failOldPage.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                        throw new IOException("Transient failure from the previous viewport");
                    }
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge, () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.request(new int[] {0}, 128); assertTrue(started.await(3, TimeUnit.SECONDS));
            loader.request(new int[] {10}, 128); waitFor(() -> loader.get(10) != null);
            failOldPage.countDown(); waitFor(() -> !loader.isLoading());
            loader.request(new int[] {0}, 128); waitFor(() -> loader.get(0) != null);
            assertEquals(2, attempts.get());
        } finally { failOldPage.countDown(); }
    }

    private static byte[] poster() {
        return poster(64, 48);
    }

    private static byte[] poster(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
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
