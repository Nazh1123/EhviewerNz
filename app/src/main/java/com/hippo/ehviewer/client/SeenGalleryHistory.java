package com.hippo.ehviewer.client;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent search baselines, shared by bookmark and ordinary search entry points. */
public final class SeenGalleryHistory {
    private static final String KEY_RECORDS = "seen_gallery_searches_v1";

    private SeenGalleryHistory() { }

    public record Search(String key, long baseline, boolean bookmarked, boolean subscribed) { }

    public static boolean supports(ListUrlBuilder builder) {
        if (builder == null) return false;
        int mode = builder.getMode();
        return mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_TAG
                || mode == ListUrlBuilder.MODE_UPLOADER || mode == ListUrlBuilder.MODE_FILTER;
    }

    static String queryKey(ListUrlBuilder builder) {
        ListUrlBuilder first = builder.clone();
        first.setPageIndex(0);
        URI url = URI.create(first.build());
        return SubscriptionProgressStore.key("search", url.getRawPath()
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }

    public static Search open(ListUrlBuilder builder) {
        if (!Settings.getShowViewedGalleries() || !supports(builder)) return null;
        List<QuickSearch> bookmarks = EhDB.getAllQuickSearch();
        return open(builder, bookmarks);
    }

    static synchronized Search open(ListUrlBuilder builder, List<QuickSearch> bookmarks) {
        SeenGalleryStore store = load(bookmarks);
        String query = queryKey(builder);
        String key = Settings.getGallerySite() + "/" + query;
        boolean bookmarked = false;
        boolean subscribed = false;
        for (QuickSearch bookmark : bookmarks) {
            ListUrlBuilder search = new ListUrlBuilder();
            search.set(bookmark);
            if (supports(search) && query.equals(queryKey(search))) {
                bookmarked = true;
                subscribed |= bookmark.subscribed;
            }
        }
        long baseline = subscribed ? SubscriptionUpdateManager.getLastSeenBookmarkGid() : store.get(key);
        persist(store);
        return new Search(key, baseline, bookmarked, subscribed);
    }

    public static boolean record(Search search, long maxGid) {
        if (search == null || !Settings.getShowViewedGalleries() || maxGid <= 0) return false;
        // Re-resolve ownership: a bookmark may have been edited or deleted during the request.
        List<QuickSearch> bookmarks = EhDB.getAllQuickSearch();
        return record(search, maxGid, bookmarks);
    }

    private static synchronized boolean record(Search search, long maxGid, List<QuickSearch> bookmarks) {
        SeenGalleryStore store = load(bookmarks);
        String query = search.key().split("/", 2)[1];
        store.record(search.key(), maxGid, bookmarkQueries(bookmarks, false).contains(query),
                SeenGalleryStore.limit(Settings.getHistoryInfoSize()));
        persist(store);
        return bookmarkQueries(bookmarks, true).contains(query);
    }

    public static synchronized void onBookmarksChanged(List<QuickSearch> bookmarks) {
        persist(load(bookmarks));
    }

    public static synchronized void trimToHistorySize(int historySize) {
        SeenGalleryStore store = new SeenGalleryStore(Settings.getString(KEY_RECORDS, ""));
        store.trim(SeenGalleryStore.limit(historySize));
        persist(store);
    }

    private static SeenGalleryStore load(List<QuickSearch> bookmarks) {
        SeenGalleryStore store = new SeenGalleryStore(Settings.getString(KEY_RECORDS, ""));
        int limit = SeenGalleryStore.limit(Settings.getHistoryInfoSize());
        long baseline = SubscriptionUpdateManager.getLastSeenBookmarkGid();
        if (Settings.getShowViewedGalleries()) store.syncSubscribed(Integer.toString(Settings.getGallerySite()), baseline);
        store.reconcile(bookmarkQueries(bookmarks, false), limit);
        Set<String> subscribedQueries = bookmarkQueries(bookmarks, true);
        store.retainSubscriptions(subscribedQueries);
        if (Settings.getShowViewedGalleries()) {
            for (String query : subscribedQueries) {
                store.recordSubscribed(Settings.getGallerySite() + "/" + query, baseline, limit);
            }
        }
        return store;
    }

    private static Set<String> bookmarkQueries(List<QuickSearch> bookmarks, boolean subscribedOnly) {
        Set<String> queries = new HashSet<>();
        for (QuickSearch bookmark : bookmarks) {
            if (subscribedOnly && !bookmark.subscribed) continue;
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.set(bookmark);
            if (supports(builder)) queries.add(queryKey(builder));
        }
        return queries;
    }

    private static void persist(SeenGalleryStore store) {
        String serialized = store.serialize();
        if (!serialized.equals(Settings.getString(KEY_RECORDS, ""))) Settings.putString(KEY_RECORDS, serialized);
    }
}
