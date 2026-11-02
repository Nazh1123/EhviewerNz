package com.hippo.ehviewer.client;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BrowsingPriorityNetworkTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockWebServer server;
    private final AtomicLong now = new AtomicLong();
    private BrowsingPriorityPolicy policy;

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        policy = new BrowsingPriorityPolicy(now::get, (lock, nanos) -> now.addAndGet(nanos));
        policy.setEnabled(true);
        policy.setCellular(true);
    }

    @After public void cleanup() throws Exception { server.shutdown(); }

    private OkHttpClient client(boolean browsing) {
        return new OkHttpClient.Builder()
                .eventListenerFactory(call -> BrowsingPriority.listener(policy, call, browsing))
                .addInterceptor(BrowsingPriority.downloadInterceptor(policy)).build();
    }

    private Request request() { return new Request.Builder().url(server.url("/gallery")).build(); }

    @Test public void responseHeadersDoNotEndBrowsingAndEarlyCloseReleasesIt() throws Exception {
        server.enqueue(new MockResponse().setBody("thumbnail").setBodyDelay(1, TimeUnit.SECONDS));
        try (Response response = client(true).newCall(request()).execute()) {
            assertTrue(policy.isThrottling());
            now.addAndGet(TimeUnit.SECONDS.toNanos(10));
            assertTrue(policy.isThrottling());
        }
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertFalse(policy.isThrottling());
    }

    @Test public void cacheHitDoesNotCreateBrowsingDemand() throws Exception {
        server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("cached"));
        try (Cache cache = new Cache(temporary.newFolder(), 1024 * 1024)) {
            OkHttpClient client = client(true).newBuilder().cache(cache).build();
            try (Response response = client.newCall(request()).execute()) {
                assertEquals("cached", response.body().string());
            }
            now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
            assertFalse(policy.isThrottling());
            try (Response response = client.newCall(request()).execute()) {
                assertNotNull(response.cacheResponse());
                assertEquals("cached", response.body().string());
                assertFalse(policy.isThrottling());
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void backgroundMetadataDoesNotClaimBrowsingPriority() throws Exception {
        server.enqueue(new MockResponse().setBody("metadata"));
        try (Response response = client(false).newCall(request()).execute()) {
            assertFalse(policy.isThrottling());
            assertEquals("metadata", response.body().string());
        }
        assertFalse(policy.isThrottling());
    }

    @Test public void redirectsKeepBrowsingActiveUntilTheFinalBodyEnds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/final")).setBody("redirect"));
        server.enqueue(new MockResponse().setBody("thumbnail"));
        try (Response response = client(true).newCall(request()).execute()) {
            assertTrue(policy.isThrottling());
            assertEquals("thumbnail", response.body().string());
        }
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertFalse(policy.isThrottling());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void failedRequestAndCancellationReleaseBrowsing() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        Call failed = client(true).newBuilder().retryOnConnectionFailure(false).build().newCall(request());
        assertThrows(IOException.class, failed::execute);
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertFalse(policy.isThrottling());

        server.enqueue(new MockResponse().setBody("thumbnail").setBodyDelay(1, TimeUnit.SECONDS));
        Call cancelled = client(true).newCall(request());
        try (Response response = cancelled.execute()) {
            assertTrue(policy.isThrottling());
            cancelled.cancel();
            assertThrows(IOException.class, () -> response.body().bytes());
        }
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertFalse(policy.isThrottling());
    }

    @Test public void downloadBodyUsesSharedBudgetAndCanBecomeTheCurrentReadingPage() throws Exception {
        byte[] data = new byte[64 * 1024];
        server.enqueue(new MockResponse().setBody(new Buffer().write(data)));
        AutoCloseable browser = policy.beginBrowsing();
        AtomicBoolean visible = new AtomicBoolean();
        Call download = BrowsingPriority.downloadCall(client(false), request(), visible::get);
        try (Response response = download.execute()) {
            Buffer received = new Buffer();
            assertEquals(8192, response.body().source().read(received, 8192));
            assertEquals(8192, response.body().source().read(received, 8192));
            assertTrue(now.get() > 0);
            visible.set(true);
            assertEquals(8192, response.body().source().read(received, 8192));
            browser.close();
            now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
            // The promoted image itself now owns browsing demand until its body finishes.
            assertTrue(policy.isThrottling());
            long before = now.get();
            response.body().source().readAll(received);
            assertEquals(before, now.get());
            assertArrayEquals(data, received.readByteArray());
        }
        now.addAndGet(BrowsingPriorityPolicy.IDLE_NANOS);
        assertFalse(policy.isThrottling());
    }

    @Test public void deliberateThrottlingDoesNotConsumeTheConfiguredCallTimeout() throws Exception {
        policy = new BrowsingPriorityPolicy();
        policy.setEnabled(true);
        policy.setCellular(true);
        try (AutoCloseable ignored = policy.beginBrowsing()) {
            byte[] data = new byte[128 * 1024];
            server.enqueue(new MockResponse().setBody(new Buffer().write(data)));
            OkHttpClient client = client(false).newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build();
            Call download = BrowsingPriority.downloadCall(client, request(), () -> false);
            long start = System.nanoTime();
            try (Response response = download.execute()) {
                assertArrayEquals(data, response.body().bytes());
            }
            assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(600));
            assertFalse(download.isCanceled());
        }
    }

    @Test public void actualNetworkStallStillHonorsTheConfiguredCallTimeout() throws Exception {
        server.enqueue(new MockResponse().setBody("image").setHeadersDelay(1, TimeUnit.SECONDS));
        OkHttpClient client = client(false).newBuilder().callTimeout(150, TimeUnit.MILLISECONDS).build();
        Call download = BrowsingPriority.downloadCall(client, request(), () -> false);
        assertThrows(IOException.class, download::execute);
        assertTrue(download.isCanceled());
    }
}
