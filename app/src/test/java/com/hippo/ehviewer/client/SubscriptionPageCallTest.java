package com.hippo.ehviewer.client;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;

public class SubscriptionPageCallTest {
    private MockWebServer server;
    private OkHttpClient client;
    private ExecutorService worker;
    @Before public void setUp() throws IOException {
        server = new MockWebServer(); server.start();
        client = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
        worker = Executors.newSingleThreadExecutor();
    }
    @After public void tearDown() throws Exception {
        worker.shutdownNow(); client.connectionPool().evictAll(); server.shutdown();
    }
    private SubscriptionPageCall call(long refresh, long budget, Semaphore spares) {
        return new SubscriptionPageCall(client, new Request.Builder().url(server.url("/search")).build(),
                refresh, budget, spares);
    }
    private String body(SubscriptionPageCall call) throws IOException {
        try (Response response = call.execute()) { return response.body().string(); }
    }
    @Test public void fastPageDoesNotIssueRefresh() throws Exception {
        server.enqueue(new MockResponse().setBody("original"));
        assertEquals("original", body(call(200, 2000, new Semaphore(2))));
        Thread.sleep(250);
        assertEquals(1, server.getRequestCount());
    }
    @Test public void stalledBodyRefreshWinsAndCancelsOriginal() throws Exception {
        server.enqueue(new MockResponse().setBody("stalled").setBodyDelay(5, TimeUnit.SECONDS));
        String refreshed = "<div class=\"itg\">refreshed</div>";
        server.enqueue(new MockResponse().setBody(refreshed));
        Semaphore spares = new Semaphore(2);
        SubscriptionPageCall call = call(100, 2000, spares);
        assertEquals(refreshed, worker.submit(() -> body(call)).get(3, TimeUnit.SECONDS));
        assertFalse("Internal cancellation must not become user cancellation", call.isCanceled());
        assertEquals(2, server.getRequestCount());
        awaitPermits(spares, 2);
    }
    @Test public void originalCanWinAfterRefreshStarts() throws Exception {
        server.enqueue(new MockResponse().setBody("original").setBodyDelay(250, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("late").setBodyDelay(5, TimeUnit.SECONDS));
        Semaphore spares = new Semaphore(2);
        assertEquals("original", body(call(100, 2000, spares)));
        assertEquals(2, server.getRequestCount());
        awaitPermits(spares, 2);
    }
    @Test public void failedRefreshDoesNotDiscardOriginal() throws Exception {
        server.enqueue(new MockResponse().setBody("original").setBodyDelay(250, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        assertEquals("original", body(call(100, 2000, new Semaphore(2))));
    }
    @Test public void http200ChallengeDoesNotDiscardOriginal() throws Exception {
        server.enqueue(new MockResponse().setBody("original").setBodyDelay(250, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("<html>Just a moment...</html>"));
        assertEquals("original", body(call(100, 2000, new Semaphore(2))));
    }
    @Test public void spareLimitKeepsOriginalAndDoesNotQueueRefresh() throws Exception {
        server.enqueue(new MockResponse().setBody("original").setBodyDelay(250, TimeUnit.MILLISECONDS));
        assertEquals("original", body(call(50, 2000, new Semaphore(0))));
        assertEquals(1, server.getRequestCount());
    }
    @Test public void externalCancellationStopsBothRequestsAndReleasesSpare() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Semaphore spares = new Semaphore(2);
        SubscriptionPageCall call = call(75, 2000, spares);
        Future<String> result = worker.submit(() -> body(call));
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        call.cancel();
        try { result.get(1, TimeUnit.SECONDS); fail("Cancelled request succeeded"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        assertTrue(call.isCanceled());
        awaitPermits(spares, 2);
    }
    @Test public void bothStalledRequestsShareOneBudget() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Semaphore spares = new Semaphore(2);
        Future<String> result = worker.submit(() -> body(call(75, 500, spares)));
        try { result.get(2, TimeUnit.SECONDS); fail("Stalled request succeeded"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        awaitPermits(spares, 2);
    }
    @Test public void fastHttpFailureRemainsAvailableToRetryPolicy() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("limited"));
        try (Response response = call(200, 2000, new Semaphore(2)).execute()) {
            assertEquals(429, response.code()); assertEquals("5", response.header("Retry-After"));
        }
        Thread.sleep(250);
        assertEquals(1, server.getRequestCount());
    }
    @Test public void cancellationBeforeExecuteNeverSendsRequest() throws Exception {
        SubscriptionPageCall call = call(50, 500, new Semaphore(2)); call.cancel();
        try { call.execute(); fail("Cancelled request succeeded"); } catch (IOException expected) { }
        assertEquals(0, server.getRequestCount());
    }
    private void awaitPermits(Semaphore spares, int expected) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (spares.availablePermits() != expected && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(expected, spares.availablePermits());
    }
}
