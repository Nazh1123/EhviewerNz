package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable publication-minute watermark retaining all boundary IDs or six recent IDs, when available.
 * GIDs identify galleries; their numeric order is never used to determine whether one is new.
 */
public final class GallerySeenState {
    private static final DateTimeFormatter POSTED = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm", Locale.US).withResolverStyle(ResolverStyle.STRICT);
    private static final GallerySeenState EMPTY = new GallerySeenState(false, 0, 0, Collections.emptyMap());
    private final boolean initialized;
    private final long minute;
    private final long trimmedThrough;
    private final Map<Long, Long> seen;

    private GallerySeenState(boolean initialized, long minute, long trimmedThrough, Map<Long, Long> entries) {
        this.initialized = initialized;
        this.minute = minute;
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(entries.entrySet());
        // Stable sort: same-minute IDs preserve response/merge order, not numeric GID order.
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        LinkedHashMap<Long, Long> retained = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> entry : sorted) {
            if (retained.size() >= 6 && entry.getValue() != minute) {
                // Do not rediscover already-seen IDs discarded from a partially retained minute.
                trimmedThrough = Math.max(trimmedThrough, entry.getValue());
                break;
            }
            retained.put(entry.getKey(), entry.getValue());
        }
        seen = Collections.unmodifiableMap(retained);
        this.trimmedThrough = trimmedThrough;
    }

    public static GallerySeenState empty() { return EMPTY; }
    public boolean initialized() { return initialized; }
    public long minute() { return minute; }
    public int retainedCount() { return seen.size(); }

    public static long postedMinute(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Math.max(0, LocalDateTime.parse(value.trim(), POSTED)
                    .toEpochSecond(ZoneOffset.UTC) / 60);
        } catch (DateTimeParseException ignored) { return 0; }
    }

    public long oldestMinute() {
        long oldest = minute;
        for (long value : seen.values()) oldest = Math.min(oldest, value);
        return oldest;
    }

    public boolean isNew(GalleryInfo gallery) {
        if (!initialized || seen.containsKey(gallery.gid)) return false;
        long posted = postedMinute(gallery.posted);
        // Unknown dates must not silently turn unseen galleries into read ones.
        return posted == 0 || minute == 0
                || (posted >= oldestMinute() && posted > trimmedThrough);
    }

    /** Scan through the whole oldest retained minute, including unseen IDs on later pages. */
    public boolean reachedOlderThanWindow(String tailPosted) {
        long tail = postedMinute(tailPosted);
        return initialized && minute > 0 && tail > 0 && tail < oldestMinute();
    }

    public GallerySeenState observe(List<GalleryInfo> galleries) {
        LinkedHashMap<Long, Long> entries = new LinkedHashMap<>(seen);
        long latest = minute;
        for (GalleryInfo gallery : galleries) {
            long posted = postedMinute(gallery.posted);
            if (gallery.gid <= 0 || posted == 0) continue;
            entries.merge(gallery.gid, posted, Math::max);
            latest = Math.max(latest, posted);
        }
        return new GallerySeenState(initialized || galleries.isEmpty() || latest > 0,
                latest, trimmedThrough, entries);
    }

    public GallerySeenState merge(GallerySeenState other) {
        LinkedHashMap<Long, Long> entries = new LinkedHashMap<>(seen);
        other.seen.forEach((gid, posted) -> entries.merge(gid, posted, Math::max));
        return new GallerySeenState(initialized || other.initialized,
                Math.max(minute, other.minute), Math.max(trimmedThrough, other.trimmedThrough), entries);
    }

    /** ASCII: version;epochMinute;trimmedMinuteDelta(0=none);gid@minutesBeforeWatermark,... */
    public String serialize() {
        if (!initialized) return "";
        StringBuilder result = new StringBuilder("2;").append(minute).append(';')
                .append(trimmedThrough == 0 ? 0 : minute - trimmedThrough).append(';');
        seen.forEach((gid, posted) -> result.append(gid).append('@')
                .append(minute - posted).append(','));
        return result.toString();
    }

    public static GallerySeenState parse(String serialized) {
        if (serialized == null) return empty();
        String[] parts = serialized.split(";", -1);
        if (parts.length != 4 || !parts[0].equals("2")) return empty();
        try {
            long latest = Long.parseLong(parts[1]);
            long trimmedDelta = Long.parseLong(parts[2]);
            if (latest < 0 || trimmedDelta < 0 || trimmedDelta > latest) return empty();
            LinkedHashMap<Long, Long> entries = new LinkedHashMap<>();
            for (String item : parts[3].split(",")) {
                if (item.isEmpty()) continue;
                String[] pair = item.split("@", -1);
                if (pair.length != 2) return empty();
                long gid = Long.parseLong(pair[0]);
                long delta = Long.parseLong(pair[1]);
                if (gid <= 0 || delta < 0 || delta >= latest) return empty();
                entries.put(gid, latest - delta);
            }
            if (latest > 0 && !entries.containsValue(latest)) return empty();
            return new GallerySeenState(true, latest,
                    trimmedDelta == 0 ? 0 : latest - trimmedDelta, entries);
        } catch (NumberFormatException ignored) { return empty(); }
    }
}
