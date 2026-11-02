package com.hippo.ehviewer.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.preference.PreferenceManager;

import com.hippo.ehviewer.Settings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.AsyncTimeout;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Event-driven browsing detection and cooperative download throttling on cellular data only. */
public final class BrowsingPriority {
    private static final BrowsingPriorityPolicy POLICY = new BrowsingPriorityPolicy();
    // SharedPreferences retains listeners weakly.
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFERENCES =
            (preferences, key) -> {
                if (Settings.KEY_MOBILE_BROWSING_PRIORITY.equals(key)) {
                    POLICY.setEnabled(Settings.getMobileBrowsingPriority());
                }
            };

    private BrowsingPriority() {}

    public static void initialize(Context context) {
        POLICY.setEnabled(Settings.getMobileBrowsingPriority());
        PreferenceManager.getDefaultSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener(PREFERENCES);
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        try {
            Network active = manager.getActiveNetwork();
            POLICY.setCellular(isCellular(manager.getNetworkCapabilities(active)));
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                private Network current = active;

                @Override public void onAvailable(Network network) {
                    current = network;
                    POLICY.setCellular(isCellular(manager.getNetworkCapabilities(network)));
                }

                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (network.equals(current)) POLICY.setCellular(isCellular(caps));
                }

                @Override public void onLost(Network network) {
                    if (network.equals(current)) {
                        current = null;
                        POLICY.setCellular(false);
                    }
                }
            });
        } catch (SecurityException e) {
            POLICY.setCellular(false);
        }
    }

    static boolean isCellular(NetworkCapabilities caps) {
        // Metered Wi-Fi is still Wi-Fi. VPNs exposing a cellular underlying transport also qualify.
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    public static OkHttpClient browsingClient(OkHttpClient base) {
        return base.newBuilder().eventListenerFactory(call -> listener(call, true)).build();
    }

    public static EventListener listener(Call call, boolean browsing) {
        return listener(POLICY, call, browsing);
    }

    static EventListener listener(BrowsingPriorityPolicy policy, Call call, boolean browsing) {
        Download traffic = call.request().tag(Download.class);
        return new EventListener() {
            private AutoCloseable activity;

            private synchronized void networkStarted() {
                if (browsing && activity == null) activity = policy.beginBrowsing();
                if (traffic != null) traffic.refreshBrowsing(policy);
            }

            private synchronized void finished() {
                closeActivity(activity);
                activity = null;
                if (traffic != null) traffic.finish();
            }

            @Override public void dnsStart(Call c, String domainName) { networkStarted(); }
            @Override public void connectStart(Call c, InetSocketAddress address, Proxy proxy) { networkStarted(); }
            @Override public void requestHeadersStart(Call c) { networkStarted(); }
            // callEnd follows consumption/closure of the body, including the final redirect response.
            // No network events means a cache hit, which must not create a browsing activity.
            @Override public void callEnd(Call c) { finished(); }
            @Override public void callFailed(Call c, IOException failure) { finished(); }
        };
    }

    public static Call downloadCall(OkHttpClient client, Request request, BooleanSupplier foreground) {
        Download traffic = new Download(foreground);
        Call call = client.newCall(request.newBuilder().tag(Download.class, traffic).build());
        // Preserve the user's total transfer timeout, excluding deliberate limiter waits.
        // The ordinary socket read/connect timeouts remain in force.
        if (client.callTimeoutMillis() > 0) {
            call.timeout().clearTimeout();
            traffic.budget = new DownloadBudget(call, client.callTimeoutMillis());
        }
        return call;
    }

    public static Interceptor downloadInterceptor() { return downloadInterceptor(POLICY); }

    static Interceptor downloadInterceptor(BrowsingPriorityPolicy policy) {
        return chain -> {
            Download traffic = chain.request().tag(Download.class);
            if (traffic == null) return chain.proceed(chain.request());
            if (traffic.budget != null) traffic.budget.resume();
            try {
                Response response = chain.proceed(chain.request());
                ResponseBody body = response.body();
                if (body == null) {
                    traffic.finish();
                    return response;
                }
                boolean network = response.networkResponse() != null;
                BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                    @Override public long read(Buffer sink, long byteCount) throws IOException {
                        try {
                            long allowed = byteCount;
                            if (network) {
                                traffic.refreshBrowsing(policy);
                                allowed = policy.readAllowance(byteCount, chain.call()::isCanceled,
                                        traffic.foreground, traffic);
                                traffic.refreshBrowsing(policy);
                            }
                            long read = super.read(sink, allowed);
                            if (read == -1) traffic.finish();
                            return read;
                        } catch (IOException | RuntimeException e) {
                            traffic.finish();
                            throw e;
                        }
                    }

                    @Override public void close() throws IOException {
                        try { super.close(); } finally { traffic.finish(); }
                    }
                });
                return response.newBuilder().body(new ResponseBody() {
                    @Override public MediaType contentType() { return body.contentType(); }
                    @Override public long contentLength() { return body.contentLength(); }
                    @Override public BufferedSource source() { return source; }
                }).build();
            } catch (IOException | RuntimeException e) {
                traffic.finish();
                throw e;
            }
        };
    }

    private static void closeActivity(AutoCloseable activity) {
        if (activity != null) {
            try { activity.close(); } catch (Exception ignored) { }
        }
    }

    private static final class Download implements BrowsingPriorityPolicy.WaitHooks {
        private final BooleanSupplier foreground;
        private AutoCloseable activity;
        private boolean finished;
        private DownloadBudget budget;

        Download(BooleanSupplier foreground) { this.foreground = foreground; }

        synchronized boolean refreshBrowsing(BrowsingPriorityPolicy policy) {
            boolean browsing = !finished && foreground.getAsBoolean();
            if (browsing && activity == null) activity = policy.beginBrowsing();
            if (!browsing && activity != null) {
                closeActivity(activity);
                activity = null;
            }
            return browsing;
        }

        synchronized void finish() {
            if (finished) return;
            finished = true;
            closeActivity(activity);
            activity = null;
            if (budget != null) budget.finish();
        }

        @Override public void pause() { if (budget != null) budget.pause(); }
        @Override public void resume() { if (budget != null) budget.resume(); }
    }

    /** Equivalent to a call timeout, but its clock stops during cooperative bandwidth waits. */
    private static final class DownloadBudget implements BrowsingPriorityPolicy.WaitHooks {
        private final Call call;
        private final AsyncTimeout timeout;
        private long remaining;
        private long started;
        private boolean running;
        private boolean finished;

        DownloadBudget(Call call, int millis) {
            this.call = call;
            remaining = TimeUnit.MILLISECONDS.toNanos(millis);
            timeout = new AsyncTimeout() {
                @Override protected void timedOut() { call.cancel(); }
            };
        }

        @Override public synchronized void resume() {
            if (finished || running) return;
            started = System.nanoTime();
            timeout.timeout(Math.max(1, remaining), TimeUnit.NANOSECONDS);
            timeout.enter();
            running = true;
        }

        @Override public synchronized void pause() {
            if (!running) return;
            if (timeout.exit()) call.cancel();
            remaining -= System.nanoTime() - started;
            running = false;
        }

        synchronized void finish() {
            finished = true;
            if (running) timeout.exit();
            running = false;
        }
    }
}
