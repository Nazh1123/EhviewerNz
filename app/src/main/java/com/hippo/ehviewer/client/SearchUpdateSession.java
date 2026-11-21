package com.hippo.ehviewer.client;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** One execution source may serve several logical queries, without sharing their checkpoints. */
public final class SearchUpdateSession {
    private final String scope;
    private final BookmarkSubscriptionPlanner.Source plan;
    private final Map<String, SearchUpdateStore.Pass> passes = new LinkedHashMap<>();
    private final Map<QuickSearch, String> owners = new LinkedHashMap<>();
    private final boolean subscription;

    /** An empty child's unbounded historical frontier must not force a populated union to EOF. */
    public static List<BookmarkSubscriptionPlanner.Source> plan(List<QuickSearch> bookmarks) {
        return plan(bookmarks, SubscriptionSearchContext.key());
    }

    public static List<BookmarkSubscriptionPlanner.Source> plan(List<QuickSearch> bookmarks, String scope) {
        List<QuickSearch> normal = new ArrayList<>();
        Map<String, List<QuickSearch>> empty = new LinkedHashMap<>();
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            for (QuickSearch bookmark : bookmarks) {
                if (!bookmark.subscribed || !BookmarkSubscriptionPlanner.isSupported(bookmark)) continue;
                ListUrlBuilder builder = new ListUrlBuilder(); builder.set(bookmark);
                String key = SearchUpdateStore.queryKey(builder);
                if (store.isInitializedEmpty(scope, key)) {
                    empty.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bookmark);
                } else normal.add(bookmark);
            }
        }
        List<BookmarkSubscriptionPlanner.Source> result = new ArrayList<>(BookmarkSubscriptionPlanner.planExact(normal));
        for (List<QuickSearch> group : empty.values()) result.addAll(BookmarkSubscriptionPlanner.planExact(group));
        return result;
    }

    public SearchUpdateSession(ListUrlBuilder builder, BookmarkSubscriptionPlanner.Source plan) {
        this(builder, plan, true);
    }

    public SearchUpdateSession(ListUrlBuilder builder, BookmarkSubscriptionPlanner.Source plan, boolean subscription) {
        this(builder, plan, subscription, SubscriptionSearchContext.key());
    }

    public SearchUpdateSession(ListUrlBuilder builder, BookmarkSubscriptionPlanner.Source plan,
                               boolean subscription, String scope) {
        this.scope = scope;
        this.plan = plan;
        this.subscription = subscription;
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            if (plan == null) {
                String key = SearchUpdateStore.queryKey(builder);
                passes.put(key, new SearchUpdateStore.Pass(store.read(scope, key)));
            } else for (QuickSearch bookmark : plan.getBookmarks()) {
                ListUrlBuilder query = new ListUrlBuilder(); query.set(bookmark);
                String key = SearchUpdateStore.queryKey(query);
                owners.put(bookmark, key);
                passes.computeIfAbsent(key, ignored -> new SearchUpdateStore.Pass(store.read(scope, key)));
            }
        }
    }

    SearchUpdateSession(SearchUpdateStore.Snapshot baseline) {
        scope = baseline.scope;
        plan = null;
        subscription = false;
        passes.put(baseline.query, new SearchUpdateStore.Pass(baseline));
    }

    public void observe(GalleryListParser.Result result, boolean hasMore) {
        observe(result, hasMore, loadPageConfirmations(scope, result));
    }

    public static Set<Long> loadPageConfirmations(String scope, GalleryListParser.Result result) {
        Set<Long> ids = new HashSet<>();
        for (GalleryInfo gallery : rawGalleries(result)) ids.add(gallery.gid);
        for (GalleryInfo gallery : qualifiedGalleries(result)) ids.add(gallery.gid);
        if (ids.isEmpty()) return Set.of();
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            return store.confirmed(scope, ids);
        }
    }

    public void observe(GalleryListParser.Result result, boolean hasMore, Set<Long> confirmed) {
        List<GalleryInfo> raw = rawGalleries(result);
        List<GalleryInfo> qualified = qualifiedGalleries(result);
        for (Map.Entry<String, SearchUpdateStore.Pass> entry : passes.entrySet()) {
            List<GalleryInfo> ownRaw = new ArrayList<>(), ownQualified = new ArrayList<>();
            Set<Long> ownConfirmed = new HashSet<>();
            for (GalleryInfo gallery : raw) if (matches(entry.getKey(), gallery)) {
                ownRaw.add(identity(result, gallery));
                if (confirmed.contains(gallery.gid)) ownConfirmed.add(gallery.gid);
            }
            for (GalleryInfo gallery : qualified) if (matches(entry.getKey(), gallery)) {
                ownQualified.add(identity(result, gallery));
                if (confirmed.contains(gallery.gid)) ownConfirmed.add(gallery.gid);
            }
            String tail = result.rawTailPosted;
            if (tail == null && !raw.isEmpty()) tail = raw.get(raw.size() - 1).posted;
            entry.getValue().observe(ownRaw, ownQualified, tail, hasMore, ownConfirmed);
        }
    }

    private static List<GalleryInfo> rawGalleries(GalleryListParser.Result result) {
        return result.rawGalleries != null ? result.rawGalleries
                : result.subscriptionRawGalleries != null ? result.subscriptionRawGalleries : result.galleryInfoList;
    }

    private static List<GalleryInfo> qualifiedGalleries(GalleryListParser.Result result) {
        return result.subscriptionDisplayGalleries != null ? result.subscriptionDisplayGalleries : result.galleryInfoList;
    }

    private static GalleryInfo identity(GalleryListParser.Result page, GalleryInfo gallery) {
        GalleryInfo identity = new GalleryInfo();
        identity.gid = gallery.gid;
        identity.posted = page.rawPublicationTimes.getOrDefault(gallery.gid, gallery.posted);
        return identity;
    }

    private boolean matches(String key, GalleryInfo gallery) {
        if (plan == null) return true;
        for (QuickSearch bookmark : plan.matchingGalleryBookmarks(gallery)) {
            if (key.equals(owners.get(bookmark))) return true;
        }
        return false;
    }

    public boolean complete() {
        for (SearchUpdateStore.Pass pass : passes.values()) if (!pass.complete()) return false;
        return true;
    }

    public Set<Long> newGids() {
        Set<Long> result = new HashSet<>();
        for (SearchUpdateStore.Pass pass : passes.values()) result.addAll(pass.newGids());
        return result;
    }

    public Set<Long> displayNewGids() {
        Set<Long> result = new HashSet<>();
        for (SearchUpdateStore.Pass pass : passes.values()) result.addAll(pass.displayNewGids());
        return result;
    }

    public Set<Long> queryDisplayNewGids() {
        Set<Long> result = new HashSet<>();
        for (SearchUpdateStore.Pass pass : passes.values()) result.addAll(pass.queryDisplayNewGids());
        return result;
    }

    public String scope() { return scope; }

    /** Capture the accepted page before the display thread can append another one. */
    public Runnable captureBrowsingCommit() {
        Map<String, SearchUpdateStore.Pass> captured = new LinkedHashMap<>();
        passes.forEach((query, pass) -> captured.put(query, pass.snapshotForCommit()));
        return () -> commit(captured, SearchUpdateStore::commitCapturedPage);
    }

    public void commit(boolean browsing) {
        commit(passes, (store, pass) -> store.commit(pass, browsing));
    }

    private void commit(Map<String, SearchUpdateStore.Pass> pending,
                        BiConsumer<SearchUpdateStore, SearchUpdateStore.Pass> save) {
        if (!scope.equals(SubscriptionSearchContext.key())) return;
        Set<String> active = new HashSet<>();
        if (plan != null && subscription) for (QuickSearch bookmark : EhDB.getSubscribedQuickSearch()) {
            ListUrlBuilder builder = new ListUrlBuilder(); builder.set(bookmark);
            active.add(SearchUpdateStore.queryKey(builder));
        }
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            for (Map.Entry<String, SearchUpdateStore.Pass> entry : pending.entrySet()) {
                if (plan != null && subscription && !active.contains(entry.getKey())) continue;
                save.accept(store, entry.getValue());
            }
        }
    }

}
