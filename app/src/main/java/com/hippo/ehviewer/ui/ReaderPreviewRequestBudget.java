package com.hippo.ehviewer.ui;

import java.util.ArrayDeque;

/** Sliding window shared by metadata and image admissions; cache hits never call acquire. */
final class ReaderPreviewRequestBudget {
    private final int limit;
    private final long windowMs;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();

    ReaderPreviewRequestBudget(int limit, long windowMs) {
        this.limit = limit; this.windowMs = windowMs;
    }

    synchronized boolean acquire(long now) {
        prune(now);
        if (starts.size() >= limit) return false;
        starts.addLast(now);
        return true;
    }

    synchronized long retryAfter(long now) {
        prune(now);
        return starts.size() < limit ? 0 : Math.max(1, starts.peekFirst() + windowMs - now);
    }

    private void prune(long now) {
        while (!starts.isEmpty() && now - starts.peekFirst() >= windowMs) starts.removeFirst();
    }
}
