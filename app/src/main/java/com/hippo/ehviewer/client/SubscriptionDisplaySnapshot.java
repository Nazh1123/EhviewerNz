package com.hippo.ehviewer.client;

import android.os.SystemClock;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.SubscriptionUpdateManager.RecentCheckSource;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A completed first batch, including the exact cursors from which browsing can resume. */
public final class SubscriptionDisplaySnapshot {
    public record Cursor(RecentCheckSource page, int position) { }
    static final Comparator<GalleryInfo> NEWEST_FIRST = (a, b) -> compare(b, a);

    public final int mode;
    public final long completedTimeMillis;
    private final long freshnessTimeMillis;
    private final long freshnessRealtime;
    public final String contextKey;
    public final int batchSize;
    public final List<GalleryInfo> galleries;
    public final List<GalleryInfo> chronologicalGalleries;
    /** Number of entries before the viewed divider; size means no divider yet. */
    public final int unreadCount;
    public final List<Cursor> cursors;
    public final boolean hasMore;

    public SubscriptionDisplaySnapshot(int mode, long completed, String contextKey, int batchSize,
            List<GalleryInfo> batch, Set<Long> unread, List<Cursor> cursors) {
        this(mode, completed, contextKey, batchSize, batch, unread, cursors, cursors.stream()
                .mapToLong(cursor -> cursor.page.startedTimeMillis()).min().orElse(completed));
    }

    public SubscriptionDisplaySnapshot(int mode, long completed, String contextKey, int batchSize,
            List<GalleryInfo> batch, Set<Long> unread, List<Cursor> cursors, long freshnessTimeMillis) {
        this(mode, completed, contextKey, batchSize, batch, unread, cursors, freshnessTimeMillis,
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - freshnessTimeMillis));
    }

    public SubscriptionDisplaySnapshot(int mode, long completed, String contextKey, int batchSize,
            List<GalleryInfo> batch, Set<Long> unread, List<Cursor> cursors,
            long freshnessTimeMillis, long freshnessRealtime) {
        this(mode, completed, contextKey, batchSize, batch, unread, cursors,
                freshnessTimeMillis, freshnessRealtime, true);
    }

    private SubscriptionDisplaySnapshot(int mode, long completed, String contextKey, int batchSize,
            List<GalleryInfo> batch, Set<Long> unread, List<Cursor> cursors,
            long freshnessTimeMillis, long freshnessRealtime, boolean copyBatch) {
        this.mode = mode;
        this.completedTimeMillis = completed;
        this.freshnessTimeMillis = freshnessTimeMillis;
        this.freshnessRealtime = freshnessRealtime;
        this.contextKey = contextKey;
        this.batchSize = Math.max(1, batchSize);
        chronologicalGalleries = List.copyOf(copyBatch ? SubscriptionGallerySnapshot.copy(batch) : batch);
        List<GalleryInfo> ordered = new ArrayList<>();
        for (GalleryInfo gallery : chronologicalGalleries) if (unread.contains(gallery.gid)) ordered.add(gallery);
        unreadCount = ordered.size();
        for (GalleryInfo gallery : chronologicalGalleries) if (!unread.contains(gallery.gid)) ordered.add(gallery);
        galleries = Collections.unmodifiableList(ordered);
        this.cursors = List.copyOf(cursors);
        hasMore = cursors.stream().anyMatch(cursor -> cursor.position < cursor.page.galleryInfoList.size()
                || !cursor.page.exhausted);
    }

    /** The same safe-prefix merge used by the existing cached/check-completed first batch. */
    public static SubscriptionDisplaySnapshot merge(int mode, List<RecentCheckSource> sources,
            Set<Long> unread, String contextKey) {
        List<RecentCheckSource> selected = sources.stream().filter(source ->
                mode == ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION || !source.isEhSubscription())
                .map(RecentCheckSource::withSortedGalleries).toList();
        int[] positions = new int[selected.size()];
        int batchSize = 0;
        for (RecentCheckSource source : selected) {
            batchSize = Math.max(batchSize, source.initialResultCount);
        }
        if (batchSize == 0) batchSize = 25;
        List<GalleryInfo> batch = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        while (batch.size() < batchSize) {
            int best = -1, unknown = -1;
            for (int i = 0; i < selected.size(); i++) {
                RecentCheckSource page = selected.get(i);
                if (positions[i] < page.galleryInfoList.size()) {
                    GalleryInfo gallery = page.galleryInfoList.get(positions[i]);
                    if (best < 0 || compare(gallery, selected.get(best).galleryInfoList.get(positions[best])) > 0) best = i;
                } else if (!page.exhausted && (unknown < 0 || !page.hasBoundary
                        || (selected.get(unknown).hasBoundary && compare(page.boundaryPosted, page.boundaryGid,
                        selected.get(unknown).boundaryPosted, selected.get(unknown).boundaryGid) > 0))) unknown = i;
            }
            if (unknown >= 0) {
                RecentCheckSource page = selected.get(unknown);
                if (best < 0 || !page.hasBoundary) break;
                GalleryInfo next = selected.get(best).galleryInfoList.get(positions[best]);
                if (compare(page.boundaryPosted, page.boundaryGid, next.posted, next.gid) > 0) break;
            }
            if (best < 0) break;
            GalleryInfo gallery = selected.get(best).galleryInfoList.get(positions[best]++);
            if (emitted.add(gallery.gid)) batch.add(gallery);
        }
        List<Cursor> cursors = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            cursors.add(new Cursor(selected.get(i), positions[i]));
        }
        long completed = selected.stream().mapToLong(source -> source.completedTimeMillis)
                .min().orElse(System.currentTimeMillis());
        return new SubscriptionDisplaySnapshot(mode, completed, contextKey,
                batchSize, batch, unread, cursors,
                selected.stream().mapToLong(RecentCheckSource::startedTimeMillis).min().orElse(completed),
                selected.stream().mapToLong(RecentCheckSource::startedRealtime).min()
                        .orElse(SystemClock.elapsedRealtime()), false);
    }

    private static int compare(GalleryInfo a, GalleryInfo b) { return compare(a.posted, a.gid, b.posted, b.gid); }

    private static int compare(String a, long agid, String b, long bgid) {
        int date = (a == null ? "" : a).compareTo(b == null ? "" : b);
        return date != 0 ? date : Long.compare(agid, bgid);
    }

    public Set<Long> unreadGids() {
        Set<Long> result = new HashSet<>();
        for (int i = 0; i < unreadCount; i++) result.add(galleries.get(i).gid);
        return result;
    }

    public boolean isFresh() {
        long age = SystemClock.elapsedRealtime() - freshnessRealtime;
        return age >= 0L && age <= SubscriptionUpdateManager.CHECK_REUSE_WINDOW_MS;
    }

    public boolean matchesContinuation(String context, List<QuickSearch> subscriptions, int ratingMinimum) {
        if (!contextKey.equals(context)) return false;
        List<QuickSearch> savedBookmarks = new ArrayList<>();
        for (Cursor cursor : cursors) {
            RecentCheckSource page = cursor.page();
            int minimum = Math.max(ratingMinimum, page.continuationBuilder().getSubscriptionMinRatingHundredths());
            if (page.ratingMinimumHundredths != minimum) return false;
            savedBookmarks.addAll(page.bookmarks());
        }
        return BookmarkSubscriptionPlanner.subscriptionKeys(savedBookmarks)
                .equals(BookmarkSubscriptionPlanner.subscriptionKeys(subscriptions));
    }

    JSONObject toJson(SubscriptionGalleryTable galleryTable) {
        JSONObject saved = new JSONObject();
        saved.put("mode", mode); saved.put("completed", completedTimeMillis);
        saved.put("freshness", freshnessTimeMillis);
        saved.put("context", contextKey); saved.put("batchSize", batchSize);
        saved.put("chronological", galleryTable.references(chronologicalGalleries));
        saved.put("unread", new JSONArray(new ArrayList<>(unreadGids())));
        JSONArray sources = new JSONArray();
        for (Cursor cursor : cursors) {
            JSONObject source = new JSONObject();
            source.put("page", cursor.page.toJson(galleryTable)); source.put("position", cursor.position);
            sources.add(source);
        }
        saved.put("cursors", sources);
        return saved;
    }

    static SubscriptionDisplaySnapshot fromJson(JSONObject saved, SubscriptionGalleryTable galleryTable) {
        Set<Long> unread = new HashSet<>();
        if (galleryTable == null) {
            JSONArray galleries = saved.getJSONArray("galleries");
            int count = saved.getIntValue("unreadCount");
            if (count < 0 || count > galleries.size()) throw new IllegalArgumentException("Invalid divider");
            for (int i = 0; i < count; i++) unread.add(galleries.getJSONObject(i).getLongValue("gid"));
        } else {
            unread.addAll(saved.getJSONArray("unread").toJavaList(Long.class));
        }
        List<Cursor> cursors = new ArrayList<>();
        JSONArray sources = saved.getJSONArray("cursors");
        for (int i = 0; i < sources.size(); i++) {
            JSONObject source = sources.getJSONObject(i);
            RecentCheckSource page = new RecentCheckSource(source.getJSONObject("page"), galleryTable);
            page.continuationPlan(); // Validate saved attribution before offering a reusable snapshot.
            int position = source.getIntValue("position");
            if (position < 0 || position > page.galleryInfoList.size()) throw new IllegalArgumentException("Invalid cursor");
            cursors.add(new Cursor(page, position));
        }
        int mode = saved.getIntValue("mode");
        if (mode != ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION && mode != ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION)
            throw new IllegalArgumentException("Invalid subscription mode");
        long freshness = saved.getLongValue("freshness");
        return new SubscriptionDisplaySnapshot(mode, saved.getLongValue("completed"), saved.getString("context"),
                saved.getIntValue("batchSize"), RecentCheckSource.readGalleries(saved.getJSONArray("chronological"), galleryTable),
                unread, cursors, freshness,
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - freshness), false);
    }
}
