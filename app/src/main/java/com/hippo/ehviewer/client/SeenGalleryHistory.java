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
        public Set<Long> newGids() { return session == null ? Set.of() : session.queryDisplayNewGids(); }
    }

    public static boolean supports(ListUrlBuilder builder) {
        if (builder == null) return false;
        int mode = builder.getMode();
        return mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_TAG
                || mode == ListUrlBuilder.MODE_UPLOADER || mode == ListUrlBuilder.MODE_FILTER;
    }

    static String queryKey(ListUrlBuilder builder) { return SearchUpdateStore.queryKey(builder); }

    public static Search open(ListUrlBuilder builder) {
        return supports(builder) ? open(builder, SubscriptionSearchContext.key()) : null;
    }

    public static Search open(ListUrlBuilder builder, String scope) {
        if (!supports(builder)) return null;
        boolean showViewed = Settings.getShowViewedGalleries();
        List<QuickSearch> bookmarks = showViewed ? EhDB.getAllQuickSearch() : EhDB.getSubscribedQuickSearch();
        return open(builder, bookmarks, showViewed, scope);
    }

    private static Search open(ListUrlBuilder builder, List<QuickSearch> bookmarks, boolean showViewed, String scope) {
        String query = queryKey(builder);
        boolean bookmarked = false, subscribed = false;
        for (QuickSearch bookmark : bookmarks) {
            ListUrlBuilder search = new ListUrlBuilder(); search.set(bookmark);
            if (supports(search) && query.equals(queryKey(search))) {
                bookmarked = true; subscribed |= bookmark.subscribed;
            }
        }
        if (!showViewed && !subscribed) return null;
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            SearchUpdateStore.Snapshot snapshot = store.read(scope, query);
            return new Search(query, snapshot.displayWatermark(), bookmarked, subscribed,
                    new SearchUpdateSession(snapshot));
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
        captureSave(search).run();
        return search.subscribed;
    }

    public static Runnable captureSave(Search search) {
        Runnable commit = search.session.captureBrowsingCommit();
        String scope = search.session.scope();
        return () -> {
            commit.run();
            if (scope.equals(SubscriptionSearchContext.key())) {
                trim(scope, EhDB.getAllQuickSearch(), Settings.getHistoryInfoSize());
            }
        };
    }

    public static void onBookmarksChanged(List<QuickSearch> bookmarks) {
        trim(SubscriptionSearchContext.key(), bookmarks, Settings.getHistoryInfoSize());
    }

    public static void trimToHistorySize(int historySize) {
        trim(SubscriptionSearchContext.key(), EhDB.getAllQuickSearch(), historySize);
    }

    private static void trim(String scope, List<QuickSearch> bookmarks, int historySize) {
        Set<String> queries = new HashSet<>();
        for (QuickSearch bookmark : bookmarks) {
            ListUrlBuilder builder = new ListUrlBuilder(); builder.set(bookmark);
            if (supports(builder)) queries.add(queryKey(builder));
        }
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            store.trim(scope, queries, SeenGalleryStore.limit(historySize));
        }
    }
}
