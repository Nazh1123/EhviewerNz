package com.hippo.conaco;

import android.app.Application;
import android.os.Looper;
import com.hippo.beerbelly.BeerBelly;
import com.hippo.streampipe.InputStreamPipe;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConacoSharedMemoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicInteger decodes = new AtomicInteger();
    private final AtomicInteger references = new AtomicInteger();
    private final ValueHelper<String> helper = new ValueHelper<String>() {
        @Override public String decode(InputStreamPipe pipe) { return decode(pipe, false); }
        @Override public String decode(InputStreamPipe pipe, boolean hardware) {
            decodes.incrementAndGet();
            try {
                pipe.obtain();
                return new String(pipe.open().readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            finally { pipe.close(); pipe.release(); }
        }
        @Override public int sizeOf(String key, String value) { return value.length(); }
        @Override public void onAddToMemoryCache(String value) { references.incrementAndGet(); }
        @Override public void onRemoveFromMemoryCache(String key, String value) { references.decrementAndGet(); }
        @Override public boolean useMemoryCache(String key, String value) { return true; }
    };

    @Test public void cacheBudgetsAreSeparateAndEvictionReplacementAndClearBalanceOwnership() {
        BeerBelly.BeerBellyParams params = new BeerBelly.BeerBellyParams();
        params.hasMemoryCache = true;
        params.memoryCacheMaxSize = 8;
        ValueCache<String> cache = new ValueCache<>(params, helper, 24, key -> key.startsWith("sheet:"));
        cache.putToMemory("cover", "12345678");
        cache.putToMemory("sheet:a", "1234567890123456");
        assertEquals(24, cache.memorySize());
        assertEquals(2, references.get());
        cache.putToMemory("sheet:a", "abcdefghijklmnop");
        assertEquals(2, references.get());
        cache.putToMemory("sheet:b", "1234567890123456");
        assertNull(cache.getFromMemory("sheet:a"));
        assertEquals("12345678", cache.getFromMemory("cover"));
        assertFalse(cache.putToMemory("sheet:oversized", "1234567890123456789012345"));
        assertEquals(2, references.get());
        cache.removeFromMemory("sheet:b");
        assertEquals(1, references.get());
        cache.clearMemory();
        assertEquals(0, references.get());
        assertEquals(0, cache.memorySize());
    }

    @Test public void sameSheetWaitersShareOneDownloadAndDecodeAndCanCancelIndependently() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("abcdefghijklmnop"));
            server.start();
            Conaco.Builder<String> builder = new Conaco.Builder<>();
            builder.hasMemoryCache = true;
            builder.memoryCacheMaxSize = 8;
            builder.sharedMemoryCacheMaxSize = 24;
            builder.sharedMemoryCacheKey = key -> key.startsWith("sheet:");
            builder.hasDiskCache = true;
            builder.diskCacheDir = temporary.newFolder();
            builder.diskCacheMaxSize = 1024;
            builder.objectHelper = helper;
            builder.okHttpClient = new OkHttpClient();
            Conaco<String> loader = builder.build();
            Target first = load(loader, server);
            Target canceled = load(loader, server);
            Target second = load(loader, server);
            loader.cancel(canceled);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((first.value == null || second.value == null) && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals("abcdefghijklmnop", first.value);
            assertEquals(first.value, second.value);
            assertNull(canceled.value);
            assertEquals(1, server.getRequestCount());
            assertEquals(1, decodes.get());
            Target warm = load(loader, server);
            assertEquals(first.value, warm.value);
            assertEquals(1, decodes.get());
            loader.getBeerBelly().clearMemory();
            assertEquals(0, references.get());
        }
    }

    private Target load(Conaco<String> loader, MockWebServer server) {
        Target target = new Target();
        loader.load(new ConacoTask.Builder<String>().setUnikery(target).setKey("sheet:a")
                .setUrl(server.url("/sheet.webp").toString()));
        return target;
    }

    private static class Target implements Unikery<String> {
        int id = INVALID_ID;
        String value;
        @Override public void setTaskId(int id) { this.id = id; }
        @Override public int getTaskId() { return id; }
        @Override public void onMiss(int source) { }
        @Override public void onRequest() { }
        @Override public void onProgress(long single, long received, long total) { }
        @Override public void onWait() { }
        @Override public boolean onGetValue(String value, int source) { this.value = value; return true; }
        @Override public void onFailure() { }
        @Override public void onCancel() { }
    }
}
