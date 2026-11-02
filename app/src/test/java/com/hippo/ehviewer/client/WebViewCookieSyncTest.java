package com.hippo.ehviewer.client;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Test;
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
public class WebViewCookieSyncTest {
    private final List<String> applied = new ArrayList<>();
    private final List<Runnable> callbacks = new ArrayList<>();
    private int flushes;
    private WebViewCookieSync sync;

    @Before public void setup() {
        sync = new WebViewCookieSync(new Handler(Looper.getMainLooper()), new WebViewCookieSync.Sink() {
            @Override public void setCookie(String url, String header, Runnable completed) {
                applied.add(header); callbacks.add(completed);
            }
            @Override public void flush() { flushes++; }
        });
    }

    private void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Test public void responsesReturnBeforeWebViewWorkAndEmptyHeadersDoNothing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("no cookies"));
            server.enqueue(new MockResponse().addHeader("Set-Cookie", "session=fresh; Path=/").setBody("with cookie"));
            server.start();
            OkHttpClient client = new OkHttpClient.Builder().addNetworkInterceptor(sync).build();
            for (String body : new String[] {"no cookies", "with cookie"}) {
                try (Response response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                    assertEquals(body, response.body().string());
                }
                assertTrue(applied.isEmpty());
                assertEquals(0, flushes);
                if (body.equals("no cookies")) { advance(2000); assertTrue(applied.isEmpty()); }
            }
            advance(50);
            assertEquals(Collections.singletonList("session=fresh; Path=/"), applied);
        }
    }

    @Test public void coalescesIdentityButRetainsOtherPathsAndWaitsForAllCallbacks() {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse("https://e-hentai.org/");
        sync.enqueue(url, Arrays.asList("session=old; Path=/", "session=other; Path=/other"), 0);
        sync.enqueue(url, Collections.singletonList("session=; Max-Age=0; Path=/"), 0);
        advance(50);
        assertEquals(2, applied.size());
        assertTrue(applied.contains("session=; Max-Age=0; Path=/"));
        assertFalse(applied.contains("session=old; Path=/"));
        callbacks.get(0).run();
        advance(1500);
        assertEquals(0, flushes);
        callbacks.get(1).run();
        advance(999);
        assertEquals(0, flushes);
        advance(1);
        assertEquals(1, flushes);
    }

    @Test public void newUpdatesDebouncePersistenceAndLogoutDropsOldResponsesAndCallbacks() {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse("https://e-hentai.org/");
        sync.enqueue(url, Collections.singletonList("session=old"), 0);
        advance(50);
        Runnable oldCallback = callbacks.get(0);
        sync.invalidate();
        sync.enqueue(url, Collections.singletonList("session=stale-response"), 0);
        sync.enqueue(url, Collections.singletonList("session=new"), 1);
        oldCallback.run();
        advance(50);
        assertEquals(Arrays.asList("session=old", "session=new"), applied);
        callbacks.get(1).run();
        advance(500);
        sync.enqueue(url, Collections.singletonList("session=newer"), 1);
        advance(50);
        callbacks.get(2).run();
        advance(999);
        assertEquals(0, flushes);
        advance(1);
        assertEquals(1, flushes);
        sync.enqueue(url, Collections.singletonList("session=queued"), 1);
        sync.invalidate();
        advance(2000);
        assertFalse(applied.contains("session=queued"));
    }

    @Test public void unavailableWebViewDoesNotFailTheNetworkResponse() throws Exception {
        sync = new WebViewCookieSync(new Handler(Looper.getMainLooper()), new WebViewCookieSync.Sink() {
            @Override public void setCookie(String url, String header, Runnable completed) { throw new IllegalStateException("No WebView"); }
            @Override public void flush() { throw new IllegalStateException("No WebView"); }
        });
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Set-Cookie", "session=fresh").setBody("detail"));
            server.start();
            OkHttpClient client = new OkHttpClient.Builder().addNetworkInterceptor(sync).build();
            try (Response response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                assertEquals("detail", response.body().string());
            }
            advance(2000);
        }
    }
}
