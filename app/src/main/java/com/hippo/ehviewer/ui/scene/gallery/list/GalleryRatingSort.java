package com.hippo.ehviewer.ui.scene.gallery.list;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.SearchRating;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class GalleryRatingSort {
    static final int MAX_PAGES = 200;
    static final int MAX_GALLERIES = 10000;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    record Entry(GalleryInfo gallery, float rating) { }

    static List<Entry> snapshot(List<GalleryInfo> source) {
        List<Entry> entries = new ArrayList<>(source.size());
        for (GalleryInfo gallery : source) entries.add(new Entry(gallery, gallery.rating));
        return entries;
    }

    static List<GalleryInfo> sort(List<Entry> entries) {
        entries.sort((a, b) -> Float.compare(score(b.rating), score(a.rating)));
        List<GalleryInfo> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) result.add(entry.gallery);
        return result;
    }

    private static float score(float rating) {
        return Float.isNaN(rating) || Float.isInfinite(rating) || rating < 0 || rating > 5 ? -1 : rating;
    }

    /** Filter an already sorted order using the current projection, retaining its live objects. */
    static List<GalleryInfo> project(List<GalleryInfo> source, List<GalleryInfo> order) {
        return project(source, order, 0);
    }

    static List<GalleryInfo> project(List<GalleryInfo> source, List<GalleryInfo> order, int minimumRating) {
        Map<Long, GalleryInfo> remaining = new HashMap<>();
        for (GalleryInfo gallery : source) remaining.put(gallery.gid, gallery);
        List<GalleryInfo> result = new ArrayList<>(source.size());
        for (GalleryInfo gallery : order) {
            GalleryInfo visible = remaining.remove(gallery.gid);
            if (visible != null && SearchRating.matches(visible.rating, minimumRating)) result.add(visible);
        }
        return result;
    }

    static String cutoff(ZonedDateTime now, int months) {
        if (months < 1 || months > 240) throw new IllegalArgumentException("months");
        return now.minusMonths(months).withZoneSameInstant(ZoneOffset.UTC).format(TIME);
    }

    static boolean validTime(String value) {
        if (value == null) return false;
        try { LocalDateTime.parse(value, TIME); return true; }
        catch (java.time.DateTimeException error) { return false; }
    }

    static boolean reached(String oldest, String cutoff) {
        return validTime(oldest) && oldest.compareTo(cutoff) <= 0;
    }
}
