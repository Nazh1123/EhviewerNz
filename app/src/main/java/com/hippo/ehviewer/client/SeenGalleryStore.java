package com.hippo.ehviewer.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bookmark queries are retained; other queries are evicted in least-recently-used order. */
final class SeenGalleryStore {
    private final Map<String, Long> bookmarks = new LinkedHashMap<>();
    private final Map<String, Long> searches = new LinkedHashMap<>();
    private final Set<String> subscribed = new HashSet<>();

    SeenGalleryStore(String serialized) {
        if (serialized == null) return;
        for (String line : serialized.split("\n")) {
            String[] parts = line.split(":", 3);
            if (parts.length != 3 || !parts[1].matches("[0-9]+/[0-9a-f]{64}")) continue;
            try {
                long gid = Long.parseLong(parts[2]);
                if (gid > 0 && (parts[0].equals("B") || parts[0].equals("U") || parts[0].equals("S"))) {
                    (parts[0].equals("S") ? searches : bookmarks).put(parts[1], gid);
                    if (parts[0].equals("U")) subscribed.add(parts[1]);
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    static int limit(int historySize) {
        return Math.max(0, Math.min(historySize / 2, 500));
    }

    void reconcile(Set<String> bookmarkQueries, int limit) {
        for (String key : new ArrayList<>(bookmarks.keySet())) {
            if (!bookmarkQueries.contains(query(key))) {
                put(searches, key, bookmarks.remove(key));
                subscribed.remove(key);
            }
        }
        for (String key : new ArrayList<>(searches.keySet())) {
            if (bookmarkQueries.contains(query(key))) put(bookmarks, key, searches.remove(key));
        }
        trim(limit);
    }

    long get(String key) {
        if (bookmarks.containsKey(key)) return bookmarks.get(key);
        Long gid = searches.remove(key);
        if (gid == null) return 0;
        searches.put(key, gid);
        return gid;
    }

    void record(String key, long gid, boolean bookmark, int limit) {
        if (gid <= 0) return;
        long previous = Math.max(bookmarks.getOrDefault(key, 0L), searches.getOrDefault(key, 0L));
        bookmarks.remove(key);
        searches.remove(key);
        if (!bookmark) subscribed.remove(key);
        put(bookmark ? bookmarks : searches, key, Math.max(previous, gid));
        trim(limit);
    }

    void syncSubscribed(String site, long baseline) {
        for (String key : subscribed) {
            if (key.startsWith(site + "/")) put(bookmarks, key, baseline);
        }
    }

    void retainSubscriptions(Set<String> queries) {
        subscribed.removeIf(key -> !queries.contains(query(key)));
    }

    void recordSubscribed(String key, long baseline, int limit) {
        record(key, baseline, true, limit);
        if (bookmarks.containsKey(key)) subscribed.add(key);
    }

    private static String query(String key) { return key.substring(key.indexOf('/') + 1); }

    private static void put(Map<String, Long> map, String key, long gid) {
        long latest = Math.max(map.getOrDefault(key, 0L), gid);
        map.remove(key);
        map.put(key, latest);
    }

    void trim(int limit) {
        while (searches.size() > limit) searches.remove(searches.keySet().iterator().next());
    }

    String serialize() {
        StringBuilder result = new StringBuilder();
        bookmarks.forEach((key, gid) -> result.append(subscribed.contains(key) ? "U:" : "B:")
                .append(key).append(':').append(gid).append('\n'));
        searches.forEach((key, gid) -> result.append("S:").append(key).append(':').append(gid).append('\n'));
        return result.toString();
    }
}
