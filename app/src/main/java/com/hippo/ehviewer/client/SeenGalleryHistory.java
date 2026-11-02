package com.hippo.ehviewer.client;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordinary and subscribed searches share a logical-query identity, never a group watermark. */
public final class SeenGalleryHistory {
    private SeenGalleryHistory() { }

    public record Search(String key, GallerySeenState baseline, boolean bookmarked, boolean subscribed,
                         SearchUpdateSession session) {
        public Search(String key, GallerySeenState baseline, boolean bookmarked, boolean subscribed) {
            this(key, baseline, bookmarked, subscribed, null);
        }
        public Set<Long> newGids() { return session == null ? Set.of() : session.displayNewGids(); }
    }

    public static boolean supports(ListUrlBuilder builder) {
        if (builder == null) return false;
        int mode = builder.getMode();
        return mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_TAG
                || mode == ListUrlBuilder.MODE_UPLOADER || mode == ListUrlBuilder.MODE_FILTER;
    }

    static String queryKey(ListUrlBuilder builder) { return SearchUpdateStore.queryKey(builder); }

    public static Search open(ListUrlBuilder builder) {
        if (!supports(builder)) return null;
        Search search = open(builder, EhDB.getAllQuickSearch());
        return Settings.getShowViewedGalleries() || search.subscribed ? search : null;
    }

    static Search open(ListUrlBuilder builder, List<QuickSearch> bookmarks) {
        String query = queryKey(builder);
        boolean bookmarked = false, subscribed = false;
        for (QuickSearch bookmark : bookmarks) {
            ListUrlBuilder search = new ListUrlBuilder(); search.set(bookmark);
            if (supports(search) && query.equals(queryKey(search))) {
                bookmarked = true; subscribed |= bookmark.subscribed;
            }
        }
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            SearchUpdateStore.Snapshot snapshot = store.read(SubscriptionSearchContext.key(), query);
            return new Search(query, snapshot.displayWatermark(), bookmarked, subscribed,
                    new SearchUpdateSession(builder, null, false));
        }
    }

    public static boolean record(Search search, List<GalleryInfo> galleries) {
        GalleryListParser.Result result = new GalleryListParser.Result();
        result.galleryInfoList = galleries; result.rawGalleries = galleries;
        if (!galleries.isEmpty()) result.rawTailPosted = galleries.get(galleries.size() - 1).posted;
        return record(search, result, false);
    }

    public static boolean record(Search search, GalleryListParser.Result result, boolean hasMore) {
        if (search == null || search.session == null
                || !Settings.getShowViewedGalleries() && !search.subscribed) return false;
        search.session.observe(result, hasMore);
        search.session.commit(true);
        trim(EhDB.getAllQuickSearch(), Settings.getHistoryInfoSize());
        return search.subscribed;
    }

    public static void onBookmarksChanged(List<QuickSearch> bookmarks) {
        trim(bookmarks, Settings.getHistoryInfoSize());
    }

    public static void trimToHistorySize(int historySize) { trim(EhDB.getAllQuickSearch(), historySize); }

    private static void trim(List<QuickSearch> bookmarks, int historySize) {
        Set<String> queries = new HashSet<>();
        for (QuickSearch bookmark : bookmarks) {
            ListUrlBuilder builder = new ListUrlBuilder(); builder.set(bookmark);
            if (supports(builder)) queries.add(queryKey(builder));
        }
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            store.trim(SubscriptionSearchContext.key(), queries, SeenGalleryStore.limit(historySize));
        }
    }
}
