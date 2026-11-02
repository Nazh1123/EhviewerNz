package com.hippo.ehviewer.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A completed source advances independently; failed or cancelled pages never advance it. */
final class SubscriptionProgressStore {
    private final Map<String, GallerySeenState> completed = new LinkedHashMap<>();

    static String key(String context, String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((context + ":" + query).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : digest) key.append(String.format(Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    SubscriptionProgressStore(String serialized) {
        if (serialized == null) return;
        for (String line : serialized.split("\n")) {
            String[] parts = line.split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}")) continue;
            GallerySeenState state = GallerySeenState.parse(parts[1]);
            if (state.initialized()) completed.put(parts[0], state);
        }
    }

    GallerySeenState get(String key, GallerySeenState fallback) {
        return fallback.merge(completed.getOrDefault(key, GallerySeenState.empty()));
    }

    void complete(String key, GallerySeenState cursor) {
        if (!cursor.initialized()) return;
        GallerySeenState latest = completed.getOrDefault(key, GallerySeenState.empty()).merge(cursor);
        snapshot(key, latest);
    }

    /** An exact handoff snapshot detects whether native progress has subsequently changed. */
    void snapshot(String key, GallerySeenState latest) {
        if (!latest.initialized()) return;
        completed.remove(key);
        completed.put(key, latest);
        while (completed.size() > 2048) completed.remove(completed.keySet().iterator().next());
    }

    String serialize() {
        StringBuilder result = new StringBuilder();
        completed.forEach((key, cursor) -> result.append(key).append(':').append(cursor.serialize()).append('\n'));
        return result.toString();
    }
}
