package com.hippo.ehviewer.client;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.webkit.CookieManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Response;

/** Coalesces WebView cookie updates without delaying delivery of network responses. */
public final class WebViewCookieSync implements Interceptor {
    private static final long BATCH_DELAY_MS = 50;
    private static final long FLUSH_DELAY_MS = 1000;

    interface Sink {
        void setCookie(String url, String header, Runnable completed);
        void flush();
    }

    private static final class Holder {
        static final WebViewCookieSync INSTANCE = new WebViewCookieSync(newHandler(), new Sink() {
            @Override public void setCookie(String url, String header, Runnable completed) {
                CookieManager.getInstance().setCookie(url, header, accepted -> completed.run());
            }
            @Override public void flush() { CookieManager.getInstance().flush(); }
        });
    }

    public static WebViewCookieSync getInstance() { return Holder.INSTANCE; }

    private static Handler newHandler() {
        HandlerThread thread = new HandlerThread("WebView-Cookie-Sync", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return new Handler(thread.getLooper());
    }

    private final Handler handler;
    private final Sink sink;
    private final Map<String, Update> pending = new LinkedHashMap<>();
    private long generation;
    private int outstanding;
    private boolean drainScheduled;
    private final Runnable drain = this::drain;
    private final Runnable flush = this::flush;

    WebViewCookieSync(Handler handler, Sink sink) { this.handler = handler; this.sink = sink; }

    private static final class Update {
        final String url;
        final String header;
        Update(String url, String header) { this.url = url; this.header = header; }
    }

    @Override public Response intercept(Chain chain) throws IOException {
        long session;
        synchronized (this) { session = generation; }
        Response response = chain.proceed(chain.request());
        enqueue(response.request().url(), response.headers("Set-Cookie"), session);
        return response;
    }

    synchronized void enqueue(HttpUrl url, List<String> headers, long session) {
        if (session != generation || headers.isEmpty()) return;
        for (String header : headers) {
            Cookie cookie = Cookie.parse(url, header);
            if (cookie == null) continue;
            // Cookie identity includes path and domain; keep the latest value or deletion.
            String key = cookie.domain() + "\n" + cookie.path() + "\n" + cookie.name();
            pending.put(key, new Update(url.toString(), header));
        }
        if (pending.isEmpty()) return;
        handler.removeCallbacks(flush);
        if (!drainScheduled) {
            drainScheduled = true;
            handler.postDelayed(drain, BATCH_DELAY_MS);
        }
    }

    /** Drop queued and in-flight response updates belonging to the previous login session. */
    public synchronized void invalidate() {
        generation++;
        pending.clear();
        outstanding = 0;
        drainScheduled = false;
        handler.removeCallbacks(drain);
        handler.removeCallbacks(flush);
    }

    private void drain() {
        List<Update> updates;
        long session;
        synchronized (this) {
            drainScheduled = false;
            session = generation;
            updates = new ArrayList<>(pending.values());
            pending.clear();
            outstanding += updates.size();
        }
        for (Update update : updates) {
            synchronized (this) { if (session != generation) return; }
            try {
                sink.setCookie(update.url, update.header, () -> completed(session));
            } catch (RuntimeException | LinkageError failure) {
                Log.w("WebViewCookieSync", "WebView cookie sync unavailable", failure);
                completed(session);
            }
        }
    }

    private synchronized void completed(long session) {
        if (session != generation) return;
        outstanding--;
        if (outstanding == 0 && pending.isEmpty()) {
            handler.removeCallbacks(flush);
            handler.postDelayed(flush, FLUSH_DELAY_MS);
        }
    }

    private void flush() {
        synchronized (this) { if (outstanding != 0 || !pending.isEmpty()) return; }
        try {
            sink.flush();
        } catch (RuntimeException | LinkageError failure) {
            Log.w("WebViewCookieSync", "WebView cookie persistence unavailable", failure);
        }
    }
}
