package com.hippo.conaco;

import android.app.Application;
import android.os.Looper;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
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
public class ConacoNetworkAdmissionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockWebServer server;
    private Conaco<String> conaco;
    private AtomicInteger admissions;

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        Conaco.Builder<String> builder = new Conaco.Builder<>();
        builder.hasMemoryCache = true;
        builder.memoryCacheMaxSize = 1024;
        builder.hasDiskCache = true;
        builder.diskCacheDir = temporary.newFolder();
        builder.diskCacheMaxSize = 1024 * 1024;
        builder.okHttpClient = new OkHttpClient();
        builder.objectHelper = new TextHelper();
        conaco = builder.build();
        admissions = new AtomicInteger();
    }

    @After public void cleanup() throws Exception { server.shutdown(); }

    private Target load(boolean allow) {
        Target target = new Target();
        conaco.load(new ConacoTask.Builder<String>().setUnikery(target).setKey("preview")
                .setUrl(server.url("/thumb").toString()).setNetworkAdmission(() -> {
                    admissions.incrementAndGet(); return allow;
                }));
        return target;
    }

    private void await(Target target) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!target.done && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("Image load did not finish", target.done);
    }

    @Test public void memoryHitDoesNotAskForNetworkQuota() {
        conaco.getBeerBelly().putToMemory("preview", "cached");
        Target target = load(false);
        assertEquals("cached", target.value);
        assertEquals(0, admissions.get());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void diskHitDoesNotAskForNetworkQuota() throws Exception {
        OutputStreamPipe pipe = conaco.getBeerBelly().getDiskCache().getOutputStreamPipe("preview");
        try {
            pipe.obtain();
            try (java.io.OutputStream output = pipe.open()) { output.write("cached".getBytes(StandardCharsets.UTF_8)); }
        } finally { pipe.close(); pipe.release(); }
        assertEquals("cached", conaco.getBeerBelly().getFromDisk("preview", false));
        Target target = load(false);
        await(target);
        assertEquals("cached", target.value);
        assertEquals(0, admissions.get());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void rejectedAdmissionSendsNoHttpRequest() throws Exception {
        Target target = load(false);
        await(target);
        assertNull(target.value);
        assertEquals(1, admissions.get());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void allowedMissDownloadsAndSubsequentLoadUsesCache() throws Exception {
        server.enqueue(new MockResponse().setBody("poster"));
        Target target = load(true);
        await(target);
        assertEquals("poster", target.value);
        assertEquals(1, server.getRequestCount());
        Target second = load(false);
        await(second);
        assertEquals("poster", second.value);
        assertEquals(1, admissions.get());
        assertEquals(1, server.getRequestCount());
    }

    private static class Target implements Unikery<String> {
        int id = INVALID_ID;
        boolean done;
        String value;
        @Override public void setTaskId(int id) { this.id = id; }
        @Override public int getTaskId() { return id; }
        @Override public void onMiss(int source) { }
        @Override public void onRequest() { }
        @Override public void onProgress(long single, long received, long total) { }
        @Override public void onWait() { }
        @Override public boolean onGetValue(String value, int source) { this.value = value; done = true; return true; }
        @Override public void onFailure() { done = true; }
        @Override public void onCancel() { done = true; }
    }

    private static class TextHelper implements ValueHelper<String> {
        @Override public String decode(InputStreamPipe pipe) { return decode(pipe, false); }
        @Override public String decode(InputStreamPipe pipe, boolean hardware) {
            try {
                pipe.obtain();
                return new String(pipe.open().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) { throw new AssertionError(e); }
            finally { pipe.close(); pipe.release(); }
        }
        @Override public int sizeOf(String key, String value) { return value.length(); }
        @Override public void onAddToMemoryCache(String value) { }
        @Override public void onRemoveFromMemoryCache(String key, String value) { }
        @Override public boolean useMemoryCache(String key, String value) { return true; }
    }
}
