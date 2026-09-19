package com.hippo.ehviewer.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A completed source advances independently; failed or cancelled pages never advance it. */
final class SubscriptionProgressStore {
    record Progress(long cursor, boolean initialized) {}
    private final Map<String, Long> completed = new LinkedHashMap<>();

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
            try {
                long gid = Long.parseLong(parts[1]);
                if (gid >= 0) completed.put(parts[0], gid);
            } catch (NumberFormatException ignored) { }
        }
    }

    Progress get(String key, long fallbackCursor, boolean fallbackInitialized) {
        Long cursor = completed.get(key);
        return new Progress(Math.max(fallbackCursor, cursor == null ? 0 : cursor),
                fallbackInitialized || cursor != null);
    }

    void complete(String key, long cursor) {
        long latest = Math.max(completed.getOrDefault(key, 0L), cursor);
        completed.remove(key);
        completed.put(key, latest);
        while (completed.size() > 2048) completed.remove(completed.keySet().iterator().next());
    }

    String serialize() {
        StringBuilder result = new StringBuilder();
        completed.forEach((key, cursor) -> result.append(key).append(':').append(cursor).append('\n'));
        return result.toString();
    }
}
