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
    @Test public void resizingInvalidatesDetailOverviewAndPreparation() throws Exception {
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
            loader.prepare(new int[] {0}, 128, 1);
            waitFor(() -> loader.get(0) != null && !loader.isLoading());
            assertEquals(128, loader.get(0).getWidth());
            android.util.LruCache<Integer, Bitmap> detailed = org.robolectric.util.ReflectionHelpers.getField(loader, "posters");
            detailed.evictAll();
            assertEquals(96, loader.get(0).getWidth());
            loader.prepare(new int[] {0}, 256, 1);
            waitFor(() -> loader.get(0) != null && loader.get(0).getWidth() == 256 && !loader.isLoading());
            assertEquals(2, reads.get());
            loader.request(new int[] {0}, 128, true);
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
            loader.request(new int[] {0}, 128, true);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            loader.request(new int[] {0}, 256, true);
            finishOldSize.countDown();
            waitFor(() -> !delivered.isEmpty() && !loader.isLoading());
            assertEquals(java.util.Collections.singletonList(256), delivered);
            assertEquals(256, loader.get(0).getWidth());
            assertEquals(2, reads.get());
        } finally { finishOldSize.countDown(); }
    }

    @Test public void idlePreparationCoversDistantPagesAndOverviewSurvivesDetailEviction() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster(256, 128);
        String identity = UUID.randomUUID().toString();
        AtomicInteger reads = new AtomicInteger();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    reads.incrementAndGet();
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge,
                            () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.prepare(new int[] {30, 29, 31, 28, 32}, 128, 64);
            waitFor(() -> loader.get(0) != null && loader.get(63) != null && !loader.isLoading());
            assertEquals(64, reads.get());
            android.util.LruCache<Integer, Bitmap> detailed = org.robolectric.util.ReflectionHelpers.getField(loader, "posters");
            detailed.evictAll();
            for (int page = 0; page < 64; page++) {
                assertNotNull("Distant page " + page, loader.get(page));
                assertTrue(Math.max(loader.get(page).getWidth(), loader.get(page).getHeight()) <= 96);
            }
            loader.prepare(new int[] {30, 29, 31, 28, 32}, 128, 64);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("Completed preparation restarted after LRU eviction", loader.isLoading());
            assertEquals(64, reads.get());
        }
    }

    @Test public void draggingSuspendsGalleryPreparationAndPrioritizesTheNewTarget() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] png = poster();
        String identity = UUID.randomUUID().toString();
        CountDownLatch backgroundStarted = new CountDownLatch(2), resumeBackground = new CountDownLatch(1);
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        DirGalleryProvider provider = new DirGalleryProvider(UniFile.fromFile(context.getCacheDir())) {
            @Override public ReaderPreviewSource createPreviewSource(Context app) {
                return (page, edge) -> {
                    calls.computeIfAbsent(page, key -> new AtomicInteger()).incrementAndGet();
                    if (page == 1 || page == 2) {
                        backgroundStarted.countDown();
                        try { assertTrue(resumeBackground.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    }
                    return ReaderPreviewBitmapCache.load(app, identity + page, edge,
                            () -> new ByteArrayInputStream(png));
                };
            }
        };
        try (ReaderPreviewLoader loader = new ReaderPreviewLoader(context, provider, (page, bitmap) -> { })) {
            loader.prepare(new int[] {0}, 128, 64);
            assertTrue(backgroundStarted.await(3, TimeUnit.SECONDS));
            loader.request(new int[] {60}, 128, true);
            waitFor(() -> loader.get(60) != null);
            assertEquals(1, resumeBackground.getCount());
            resumeBackground.countDown();
            waitFor(() -> !loader.isLoading());
            assertEquals("Drag kept scanning obsolete pages", 4, calls.size());
            assertFalse(calls.containsKey(3));
        } finally { resumeBackground.countDown(); }
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
            // The UI first prioritizes the center, then expands to the visible neighborhood.
            // A failure which stays in both windows must not be retried on every expansion.
            loader.request(new int[] {0}, 128, true);
            loader.request(new int[] {0, 1, 2, 3}, 128, true);
            waitFor(() -> loader.get(3) != null && !loader.isLoading());
            assertEquals(1, attempts.get());
            loader.stop();
            loader.request(new int[] {0}, 128, true);
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
            loader.request(new int[] {0}, 128, true); assertTrue(started.await(3, TimeUnit.SECONDS));
            loader.request(new int[] {10}, 128, true); waitFor(() -> loader.get(10) != null);
            failOldPage.countDown(); waitFor(() -> !loader.isLoading());
            loader.request(new int[] {0}, 128, true); waitFor(() -> loader.get(0) != null);
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
