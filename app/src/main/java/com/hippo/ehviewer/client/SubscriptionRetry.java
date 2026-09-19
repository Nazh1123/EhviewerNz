package com.hippo.ehviewer.client;

import java.io.IOException;
import com.hippo.network.StatusCodeException;

/** Retry transport failures locally, without restarting successful sources. */
public final class SubscriptionRetry {
    private SubscriptionRetry() {}
    public static long delayMillis(Exception error, int retries) {
        boolean transientFailure = error instanceof IOException;
        if (error instanceof StatusCodeException status) {
            transientFailure = isTransientStatus(status.getResponseCode());
        }
        return transientFailure && retries >= 0 && retries < 2 ? (retries == 0 ? 2000 : 5000) : -1;
    }
    static boolean isTransientStatus(int code) {
        return code == 408 || code == 500 || code == 502 || code == 503 || code == 504;
    }
}
