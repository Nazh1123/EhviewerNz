/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Tracks unread subscription galleries and incrementally checks subscription sources.
 *
 * <p>Each logical query commits a complete publication window and its identity ledger atomically.
 * Browsing acknowledgement is separate and only successful responses acknowledge captured updates.
 * Request plans and display caches never define the durable identity of a query.</p>
 */
public final class SubscriptionUpdateManager {

    public static final long CANCEL_RETRY_DELAY_MS = 3L * 60L * 1000L;
    public static final long CHECK_REUSE_WINDOW_MS = 60L * 1000L;

    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final String KEY_UNREAD_RATING_POLICY = "subscription_unread_rating_policy";

    private static final String KEY_LAST_CHECK_TIME =
            "subscription_updates_last_check_time";
    private static final String KEY_CANCEL_RETRY_NOT_BEFORE_TIME =
            "subscription_updates_cancel_retry_not_before_time";
    private static final String KEY_UNREAD_EH_GIDS =
            "subscription_updates_unread_eh_gids";
    private static final String KEY_UNREAD_BOOKMARK_GIDS =
            "subscription_updates_unread_bookmark_gids";

    private enum Group {
        EH,
        BOOKMARK
    }

    public interface Listener {
        void onSubscriptionUpdateStateChanged();

        void onSubscriptionUpdateCheckFinished(@NonNull CheckResult result);
    }

    public interface CheckCompletion {
        void onCompleted(@Nullable RecentCheckResult result);
    }

    public static final class Snapshot {
        public final boolean ehEnabled;
        public final boolean bookmarkEnabled;
        public final int ehCount;
        public final int bookmarkCount;
        public final int globalCount;

        Snapshot(boolean ehEnabled, boolean bookmarkEnabled, int ehCount,
                 int bookmarkCount, int globalCount) {
            this.ehEnabled = ehEnabled;
            this.bookmarkEnabled = bookmarkEnabled;
            this.ehCount = ehCount;
            this.bookmarkCount = bookmarkCount;
            this.globalCount = globalCount;
        }
    }

    public static final class CheckResult {
        public final boolean manual;
        public final boolean failed;
        public final int newEhCount;
        public final int newBookmarkCount;
        public final Snapshot snapshot;

        CheckResult(boolean manual, boolean failed, int newEhCount,
                    int newBookmarkCount, Snapshot snapshot) {
            this.manual = manual;
            this.failed = failed;
            this.newEhCount = newEhCount;
            this.newBookmarkCount = newBookmarkCount;
            this.snapshot = snapshot;
        }

        public boolean hasNewGalleries() {
            return newEhCount > 0 || newBookmarkCount > 0;
        }
    }

    /**
     * A successful source response retained from an update check or interactive refresh. It can
     * represent either the EH subscription or a planned group of bookmark searches.
     */
    public static final class RecentCheckSource {
        public final int ratingMinimumHundredths;
        public final boolean requiresDisplayPreparation;
        @NonNull public final List<GalleryInfo> galleryInfoList;
        public final GallerySeenState observed;
        public final List<GalleryInfo> rawGalleries;
        public final Map<Long, String> rawPublicationTimes;
        public final int initialResultCount;
        public final int pageIndex;
        @Nullable public final String nextHref;
        @Nullable public final String boundaryPosted;
        public final long boundaryGid;
        public final boolean hasBoundary;
        public final boolean exhausted;
        public final long completedRealtime;
        public final long completedTimeMillis;
        private final long startedRealtime;
        private final long startedTimeMillis;

        private final ListUrlBuilder mBuilder = new ListUrlBuilder();
        @Nullable private final String mBookmarkSourceKey;
        private final List<QuickSearch> mBookmarks;

        private RecentCheckSource(ListUrlBuilder builder, @Nullable BookmarkSubscriptionPlanner.Source plan,
                GalleryListParser.Result page, int rating, long started,
                boolean hasBoundary, boolean exhausted) {
            completedRealtime = SystemClock.elapsedRealtime();
            completedTimeMillis = System.currentTimeMillis();
            startedRealtime = started;
            startedTimeMillis = completedTimeMillis - Math.max(0L, completedRealtime - started);
            ratingMinimumHundredths = rating;
            requiresDisplayPreparation = page.subscriptionRawGalleries != null
                    && page.subscriptionDisplayGalleries == null;
            mBuilder.set(builder);
            mBookmarkSourceKey = plan != null ? plan.getCacheKey() : null;
            mBookmarks = plan == null ? List.of() : plan.getBookmarks().stream()
                    .map(RecentCheckSource::copyBookmark).toList();
            List<GalleryInfo> display = page.subscriptionDisplayGalleries != null ? page.subscriptionDisplayGalleries
                    : page.subscriptionRawGalleries != null ? page.subscriptionRawGalleries : page.galleryInfoList;
            List<GalleryInfo> raw = page.rawGalleries != null ? page.rawGalleries
                    : page.subscriptionRawGalleries != null ? page.subscriptionRawGalleries : page.galleryInfoList;
            galleryInfoList = List.copyOf(SubscriptionGallerySnapshot.copy(display));
            rawGalleries = raw == display ? galleryInfoList : List.copyOf(SubscriptionGallerySnapshot.copy(raw));
            rawPublicationTimes = Collections.unmodifiableMap(new HashMap<>(page.rawPublicationTimes));
            observed = page.rawSeenState.observe(page.galleryInfoList);
            initialResultCount = page.rawResultCount;
            pageIndex = builder.getPageIndex();
            nextHref = page.nextHref;
            boundaryPosted = page.rawTailPosted;
            boundaryGid = page.rawTailGid;
            this.hasBoundary = hasBoundary;
            this.exhausted = exhausted;
        }

        private static QuickSearch copyBookmark(QuickSearch bookmark) {
            QuickSearch copy = new QuickSearch(bookmark.id, bookmark.name, bookmark.mode, bookmark.category,
                    bookmark.keyword, bookmark.advanceSearch, bookmark.minRating, bookmark.pageFrom,
                    bookmark.pageTo, bookmark.time, bookmark.subscribed);
            copy.minRatingHundredths = bookmark.minRatingHundredths;
            copy.ratingOverride = bookmark.ratingOverride;
            return copy;
        }

        public boolean isEhSubscription() {
            return mBuilder.getMode() == ListUrlBuilder.MODE_SUBSCRIPTION;
        }

        public boolean matchesBookmarkSource(BookmarkSubscriptionPlanner.Source source) {
            return source.getCacheKey().equals(mBookmarkSourceKey);
        }

        public boolean isFresh() {
            long age = SystemClock.elapsedRealtime() - startedRealtime;
            return age >= 0L && age <= CHECK_REUSE_WINDOW_MS;
        }

        public static RecentCheckSource fromDisplay(ListUrlBuilder builder,
                @Nullable BookmarkSubscriptionPlanner.Source plan,
                GalleryListParser.Result page, int ratingMinimumHundredths) {
            return fromPage(builder, plan, page, ratingMinimumHundredths, SystemClock.elapsedRealtime());
        }

        private static RecentCheckSource fromPage(ListUrlBuilder builder,
                @Nullable BookmarkSubscriptionPlanner.Source plan, GalleryListParser.Result page,
                int ratingMinimumHundredths, long startedRealtime) {
            if (page.firstPageForCache != null) page = page.firstPageForCache;
            boolean exhausted = TextUtils.isEmpty(page.nextHref)
                    && !(page.pages > 0 && builder.getPageIndex() + 1 < page.pages);
            return new RecentCheckSource(builder, plan, page, ratingMinimumHundredths, startedRealtime,
                    page.rawResultCount > 0, exhausted);
        }

        boolean sameSource(RecentCheckSource other) {
            return isEhSubscription() ? other.isEhSubscription()
                    : mBookmarkSourceKey != null && mBookmarkSourceKey.equals(other.mBookmarkSourceKey);
        }

        RecentCheckSource(JSONObject saved, @Nullable SubscriptionGalleryTable galleryTable) {
            ratingMinimumHundredths = saved.getIntValue("rating");
            requiresDisplayPreparation = saved.getBooleanValue("prepare");
            mBuilder.setMode(saved.getBooleanValue("eh")
                    ? ListUrlBuilder.MODE_SUBSCRIPTION : ListUrlBuilder.MODE_NORMAL);
            mBookmarkSourceKey = saved.getString("bookmark");
            if (!isEhSubscription() && mBookmarkSourceKey == null) throw new IllegalArgumentException("Missing source");
            galleryInfoList = readGalleries(saved.getJSONArray("galleries"), galleryTable);
            rawGalleries = readGalleries(saved.getJSONArray("raw"), galleryTable);
            observed = GallerySeenState.parse(saved.getString("observed"));
            Map<Long, String> times = new HashMap<>();
            JSONObject savedTimes = saved.getJSONObject("publicationTimes");
            if (savedTimes != null) savedTimes.forEach((gid, posted) -> times.put(Long.parseLong(gid), (String) posted));
            rawPublicationTimes = Collections.unmodifiableMap(times);
            initialResultCount = saved.getIntValue("count");
            pageIndex = saved.getIntValue("pageIndex");
            JSONArray bookmarks = saved.getJSONArray("bookmarks");
            mBookmarks = bookmarks == null ? List.of() : bookmarks.toJavaList(QuickSearch.class);
            if (!isEhSubscription() && !mBookmarks.isEmpty()) {
                mBuilder.set(continuationPlan().createBuilder());
            }
            mBuilder.setPageIndex(pageIndex);
            nextHref = saved.getString("next");
            boundaryPosted = saved.getString("boundaryPosted");
            boundaryGid = saved.getLongValue("boundaryGid");
            hasBoundary = saved.getBooleanValue("hasBoundary");
            exhausted = saved.getBooleanValue("exhausted");
            completedTimeMillis = saved.getLongValue("completed");
            startedTimeMillis = saved.getLongValue("started");
            long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            if (completedTimeMillis <= 0 || startedTimeMillis <= 0 || completedTimeMillis > now
                    || startedTimeMillis > completedTimeMillis) throw new IllegalArgumentException("Invalid result time");
            completedRealtime = elapsed - (now - completedTimeMillis);
            startedRealtime = elapsed - (now - startedTimeMillis);
        }

        JSONObject toJson(SubscriptionGalleryTable galleryTable) {
            JSONObject saved = new JSONObject();
            saved.put("eh", isEhSubscription()); saved.put("bookmark", mBookmarkSourceKey);
            saved.put("rating", ratingMinimumHundredths); saved.put("prepare", requiresDisplayPreparation);
            saved.put("galleries", galleryTable.references(galleryInfoList));
            saved.put("raw", galleryTable.references(rawGalleries));
            JSONObject times = new JSONObject();
            rawPublicationTimes.forEach((gid, posted) -> times.put(Long.toString(gid), posted));
            saved.put("observed", observed.serialize()); saved.put("publicationTimes", times);
            saved.put("count", initialResultCount); saved.put("next", nextHref);
            saved.put("pageIndex", pageIndex); saved.put("bookmarks", mBookmarks);
            saved.put("boundaryPosted", boundaryPosted); saved.put("boundaryGid", boundaryGid);
            saved.put("hasBoundary", hasBoundary); saved.put("exhausted", exhausted);
            saved.put("completed", completedTimeMillis); saved.put("started", startedTimeMillis);
            return saved;
        }

        static List<GalleryInfo> readGalleries(JSONArray saved, @Nullable SubscriptionGalleryTable galleryTable) {
            if (saved == null) throw new IllegalArgumentException("Missing galleries");
            List<GalleryInfo> galleries = new ArrayList<>(saved.size());
            for (int i = 0; i < saved.size(); i++) {
                galleries.add(galleryTable == null ? SubscriptionGalleryTable.readGallery(saved.getJSONObject(i))
                        : galleryTable.gallery(saved.getInteger(i)));
            }
            return List.copyOf(galleries);
        }

        public ListUrlBuilder continuationBuilder() { return mBuilder.clone(); }

        public long startedTimeMillis() { return startedTimeMillis; }

        public long startedRealtime() { return startedRealtime; }

        @Nullable public BookmarkSubscriptionPlanner.Source continuationPlan() {
            if (isEhSubscription()) return null;
            List<BookmarkSubscriptionPlanner.Source> plans = BookmarkSubscriptionPlanner.planExact(mBookmarks);
            if (plans.size() != 1) throw new IllegalArgumentException("Missing continuation plan");
            return plans.get(0);
        }

        List<QuickSearch> bookmarks() { return mBookmarks; }

        RecentCheckSource withSortedGalleries() {
            List<GalleryInfo> sorted = SubscriptionGallerySnapshot.copy(galleryInfoList);
            sorted.sort(SubscriptionDisplaySnapshot.NEWEST_FIRST);
            return new RecentCheckSource(this, sorted);
        }

        private RecentCheckSource(RecentCheckSource source, List<GalleryInfo> galleries) {
            mBuilder.set(source.mBuilder);
            mBookmarkSourceKey = source.mBookmarkSourceKey;
            mBookmarks = source.mBookmarks;
            ratingMinimumHundredths = source.ratingMinimumHundredths;
            requiresDisplayPreparation = false;
            galleryInfoList = List.copyOf(galleries);
            rawGalleries = source.rawGalleries;
            rawPublicationTimes = source.rawPublicationTimes;
            observed = source.observed;
            initialResultCount = source.initialResultCount;
            pageIndex = source.pageIndex;
            nextHref = source.nextHref;
            boundaryPosted = source.boundaryPosted;
            boundaryGid = source.boundaryGid;
            hasBoundary = source.hasBoundary;
            exhausted = source.exhausted;
            completedRealtime = source.completedRealtime;
            completedTimeMillis = source.completedTimeMillis;
            startedRealtime = source.startedRealtime;
            startedTimeMillis = source.startedTimeMillis;
        }

        public static RecentCheckSource fromCursor(ListUrlBuilder builder,
                @Nullable BookmarkSubscriptionPlanner.Source plan, List<GalleryInfo> buffer,
                int pageIndex, @Nullable String nextHref, @Nullable String boundaryPosted,
                long boundaryGid, boolean hasBoundary, boolean exhausted, int ratingMinimumHundredths) {
            GalleryListParser.Result page = new GalleryListParser.Result();
            page.galleryInfoList = buffer;
            page.rawResultCount = hasBoundary ? buffer.size() : 0;
            page.rawTailPosted = boundaryPosted; page.rawTailGid = boundaryGid;
            page.nextHref = nextHref;
            ListUrlBuilder cursorBuilder = builder.clone(); cursorBuilder.setPageIndex(pageIndex);
            return new RecentCheckSource(cursorBuilder, plan, page, ratingMinimumHundredths,
                    SystemClock.elapsedRealtime(), hasBoundary, exhausted);
        }

        public GalleryListParser.Result asPageResult() {
            GalleryListParser.Result page = new GalleryListParser.Result();
            page.galleryInfoList = SubscriptionGallerySnapshot.copy(galleryInfoList);
            page.rawGalleries = SubscriptionGallerySnapshot.copy(rawGalleries);
            page.rawPublicationTimes.putAll(rawPublicationTimes);
            page.rawSeenState = observed;
            page.rawResultCount = initialResultCount;
            page.rawTailPosted = boundaryPosted; page.rawTailGid = boundaryGid;
            page.nextHref = nextHref;
            page.pages = exhausted ? 1 : 2;
            page.nextPage = -1;
            return page;
        }
    }

    /** A compatible snapshot shared by bookmark and global subscription entry points. */
    public static final class RecentCheckResult {
        @NonNull public final List<RecentCheckSource> sources;
        public final long completedTimeMillis;
        final String contextKey;
        final String displayKey;
        private final boolean allowExpired;
        final Map<Integer, SubscriptionDisplaySnapshot> displaySnapshots = new HashMap<>();

        RecentCheckResult(@NonNull List<RecentCheckSource> sources, String contextKey) {
            this(sources, contextKey, SubscriptionSearchContext.cacheKey());
        }

        RecentCheckResult(@NonNull List<RecentCheckSource> sources, String contextKey, String displayKey) {
            this(sources, contextKey, displayKey, false);
        }

        private RecentCheckResult(List<RecentCheckSource> sources, String contextKey, String displayKey,
                boolean allowExpired) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.completedTimeMillis = sources.stream().mapToLong(source -> source.completedTimeMillis)
                    .min().orElse(System.currentTimeMillis());
            this.contextKey = contextKey;
            this.displayKey = displayKey;
            this.allowExpired = allowExpired;
        }

        private RecentCheckResult(RecentCheckResult result) {
            sources = result.sources;
            completedTimeMillis = result.completedTimeMillis;
            contextKey = result.contextKey;
            displayKey = result.displayKey;
            allowExpired = true;
            displaySnapshots.putAll(result.displaySnapshots);
        }

        @Nullable public SubscriptionDisplaySnapshot displaySnapshot(int mode) {
            SubscriptionDisplaySnapshot snapshot = displaySnapshots.get(mode);
            return snapshot != null && (allowExpired || snapshot.isFresh()) ? snapshot : null;
        }

        public boolean matchesContext() { return contextKey.equals(SubscriptionSearchContext.key())
                && displayKey.equals(SubscriptionSearchContext.cacheKey()); }

        public boolean canReuseSource(RecentCheckSource source) {
            return allowExpired || source.isFresh();
        }

        boolean hasSourceForMode(int mode) {
            return displaySnapshot(mode) != null;
        }

        public boolean hasCheckedResultForMode(int mode) {
            if (hasSourceForMode(mode)) return true;
            return sources.stream().anyMatch(source -> !source.requiresDisplayPreparation
                    && (mode == ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION
                    || (mode == ListUrlBuilder.MODE_SUBSCRIPTION && source.isEhSubscription())
                    || (mode == ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION && !source.isEhSubscription())));
        }
    }

    private static final class Source {
        int ratingMinimumHundredths;
        final Group group;
        @Nullable final BookmarkSubscriptionPlanner.Source plan;
        final ListUrlBuilder builder = new ListUrlBuilder();
        SearchUpdateSession session;
        long startedRealtime;
        int retries;

        int pageIndex;
        @Nullable String nextHref;
        boolean complete;
        boolean successful;
        @Nullable EhRequest request;
        final Set<String> visitedUrls = new HashSet<>();
        @Nullable RecentCheckSource firstPage;

        Source() {
            group = Group.EH;
            plan = null;
            builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
            builder.setPageIndex(0);
        }

        Source(@NonNull BookmarkSubscriptionPlanner.Source plan) {
            group = Group.BOOKMARK;
            this.plan = plan;
            builder.set(plan.createBuilder());
            builder.setPageIndex(0);
        }
    }

    private final EhClient mClient;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Source> mQueue = new ArrayDeque<>();
    private final ArrayList<Source> mSources = new ArrayList<>();
    private Unread mUnread = Unread.EMPTY;
    private String mSearchContext;
    @Nullable private String mUnreadScope;
    private int mUnreadGeneration;
    private int mAppliedUnreadGeneration;

    private record Unread(Set<Long> eh, Set<Long> bookmarks, Set<Long> global) {
        private static final Unread EMPTY = new Unread(Set.of(), Set.of(), Set.of());

        Set<Long> forMode(int mode, boolean includeGlobal) {
            Set<Long> ids = switch (mode) {
                case ListUrlBuilder.MODE_SUBSCRIPTION -> eh;
                case ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION -> bookmarks;
                case ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION -> includeGlobal ? global : Set.of();
                default -> Set.of();
            };
            return new HashSet<>(ids);
        }
    }

    @Nullable private Listener mListener;
    @Nullable private CheckResult mPendingCheckResult;
    @Nullable private String mPendingCheckContext;
    private final SubscriptionResultStore mResultStore;
    private String mCheckDisplayKey;
    private String mRatingPolicy;
    private volatile int mGeneration;
    private int mActiveRequests;
    private int mCompletedSources;
    private boolean mChecking;
    private boolean mManual;
    private boolean mEhEnabled;
    private boolean mBookmarkEnabled;
    private boolean mBookmarkPreparationFailed;
    private final List<CheckCompletion> mCheckCompletions = new ArrayList<>();

    public SubscriptionUpdateManager(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        mResultStore = new SubscriptionResultStore(applicationContext.getFilesDir());
        SearchUpdateStore.initialize(applicationContext);
        mClient = EhApplication.getEhClient(applicationContext);
        Set<Long> legacyEh = parseGids(Settings.getString(KEY_UNREAD_EH_GIDS, ""));
        Set<Long> legacyBookmarks = parseGids(Settings.getString(KEY_UNREAD_BOOKMARK_GIDS, ""));
        String scope = SubscriptionSearchContext.key();
        String owner = Settings.getString("search_updates_legacy_owner", "");
        if (owner.isEmpty()) { Settings.putString("search_updates_legacy_owner", scope); owner = scope; }
        if (owner.equals(scope)) {
            SearchUpdateStore.saveBrowsingInBackground(() -> {
                try (SearchUpdateStore store = SearchUpdateStore.open()) {
                    store.migrateUnread(scope, "eh", legacyEh);
                    store.migrateUnread(scope, "bookmark", legacyBookmarks);
                }
            });
        }
        mUnread = new Unread(legacyEh, legacyBookmarks, Set.of());
        mRatingPolicy = Settings.getString(KEY_UNREAD_RATING_POLICY, "true:0");
        syncRatingPolicy();
    }

    private void syncRatingPolicy() {
        String policy = Settings.getEnablePreciseRatingFilter() + ":" + Settings.getPersistentMinRatingHundredths();
        if (policy.equals(mRatingPolicy)) return;
        mRatingPolicy = policy;
        mUnreadGeneration++;
        if (!mUnread.eh.isEmpty()) persistGids(KEY_UNREAD_EH_GIDS, Set.of());
        if (!mUnread.bookmarks.isEmpty()) persistGids(KEY_UNREAD_BOOKMARK_GIDS, Set.of());
        mUnread = Unread.EMPTY;
        mUnreadScope = null;
        Settings.putString(KEY_UNREAD_RATING_POLICY, policy);
        cancelCheck();
        // Completed display snapshots deliberately survive changed browsing conditions.
        notifyStateChanged();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
        if (listener != null) onSearchStatesChanged();
    }

    private void dispatchPendingCheckResult(Snapshot snapshot) {
        Listener listener = mListener;
        CheckResult result = mPendingCheckResult;
        if (listener == null || result == null) return;
        mPendingCheckResult = null;
        if (!SubscriptionSearchContext.key().equals(mPendingCheckContext)) return;
        // A subscription may have been opened while this activity was stopped.
        listener.onSubscriptionUpdateCheckFinished(new CheckResult(result.manual, result.failed,
                Math.min(result.newEhCount, snapshot.ehCount),
                Math.min(result.newBookmarkCount, snapshot.bookmarkCount), snapshot));
    }

    public boolean isChecking() {
        syncRatingPolicy();
        return mChecking;
    }

    /** Subscribe to the existing round without cancelling it or submitting another request. */
    public boolean awaitCurrentCheck(CheckCompletion completion) {
        syncRatingPolicy();
        if (!mChecking) return false;
        mCheckCompletions.add(completion);
        return true;
    }

    private void deliverCheckCompletions(@Nullable RecentCheckResult result) {
        if (mCheckCompletions.isEmpty()) return;
        List<CheckCompletion> completions = new ArrayList<>(mCheckCompletions);
        mCheckCompletions.clear();
        mMainHandler.post(() -> {
            for (CheckCompletion completion : completions) completion.onCompleted(result);
        });
    }

    public long getLastCheckTime() {
        return Settings.getLong(KEY_LAST_CHECK_TIME, 0L);
    }

    public long getNextAutomaticCheckTime() {
        return calculateNextAutomaticCheckTime(getLastCheckTime(),
                Settings.getLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L));
    }

    /**
     * Returns fresh update-check data without consuming it for the other entry points.
     */
    @Nullable
    public RecentCheckResult getRecentCheckResult(int mode) {
        syncRatingPolicy();
        return resultForMode(mResultStore.get(), mode, false);
    }

    /** Explicit reuse keeps the last completed display, regardless of changed browsing conditions. */
    @Nullable
    public RecentCheckResult getReusableCheckResult(int mode) {
        syncRatingPolicy();
        return resultForMode(mResultStore.get(), mode, true);
    }

    public CompletableFuture<RecentCheckResult> loadSavedCheckResult(int mode, boolean allowExpired) {
        syncRatingPolicy();
        return mResultStore.getAsync().thenApply(saved -> resultForMode(saved, mode, allowExpired));
    }

    @Nullable
    private static RecentCheckResult resultForMode(@Nullable RecentCheckResult saved, int mode, boolean allowExpired) {
        if (saved == null) return null;
        RecentCheckResult result = allowExpired ? new RecentCheckResult(saved) : saved;
        return result.hasSourceForMode(mode) ? result : null;
    }

    /** A successful interactive refresh becomes the result reused on the next visit. */
    public void rememberDisplayedSource(String contextKey, String displayKey, RecentCheckSource source) {
        rememberDisplayedSources(contextKey, displayKey, Collections.singletonList(source));
    }

    public void rememberDisplayedSources(String contextKey, String displayKey, List<RecentCheckSource> sources) {
        syncRatingPolicy();
        if (sources.isEmpty() || !contextKey.equals(SubscriptionSearchContext.key())
                || !displayKey.equals(SubscriptionSearchContext.cacheKey())) return;
        mResultStore.record(contextKey, displayKey, sources);
        // Only a newly fetched list advances the timer; opening or preparing cached data does not.
        resetCheckTimer();
    }

    /** Only a wholly successful fresh first batch can replace an entry's frozen display. */
    public void rememberDisplayedSnapshots(List<SubscriptionDisplaySnapshot> snapshots,
            List<RecentCheckSource> sources) {
        if (snapshots.isEmpty() || !snapshots.get(0).contextKey.equals(SubscriptionSearchContext.key())) return;
        mResultStore.recordSnapshots(snapshots.get(0).contextKey, SubscriptionSearchContext.cacheKey(), sources, snapshots);
        resetCheckTimer();
    }

    /** Keep browsing acknowledgements out of the direct display path and never rewrite the snapshot. */
    public void acknowledgeSnapshot(SubscriptionDisplaySnapshot snapshot) {
        if (snapshot.capturedUnreadGids().isEmpty()) return;
        SearchUpdateStore.saveBrowsingInBackground(() -> {
            if (!snapshot.contextKey.equals(SubscriptionSearchContext.key())) return;
            try {
                Set<String> activeBookmarks = new HashSet<>();
                for (QuickSearch bookmark : EhDB.getSubscribedQuickSearch()) {
                    ListUrlBuilder builder = new ListUrlBuilder();
                    builder.set(bookmark);
                    activeBookmarks.add(SearchUpdateStore.queryKey(builder));
                }
                Set<String> queries = new HashSet<>();
                for (SubscriptionDisplaySnapshot.Cursor cursor : snapshot.cursors) {
                    RecentCheckSource page = cursor.page();
                    if (page.isEhSubscription()) {
                        queries.add(SearchUpdateStore.queryKey(page.continuationBuilder()));
                    } else {
                        for (QuickSearch bookmark : page.bookmarks()) {
                            ListUrlBuilder builder = new ListUrlBuilder();
                            builder.set(bookmark);
                            String query = SearchUpdateStore.queryKey(builder);
                            if (activeBookmarks.contains(query)) queries.add(query);
                        }
                    }
                }
                queries.add("legacy-unread:bookmark");
                if (snapshot.mode == ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION) queries.add("legacy-unread:eh");
                try (SearchUpdateStore store = SearchUpdateStore.open()) {
                    store.acknowledgeSnapshot(snapshot.contextKey, queries, snapshot.capturedUnreadGids());
                }
            } catch (RuntimeException e) {
                android.util.Log.w("SubscriptionResults", "Unable to acknowledge restored subscription results", e);
            }
        });
        onSearchStatesChanged();
    }

    public void resetCheckTimer() {
        Settings.putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis());
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);
    }

    @NonNull
    public Snapshot getSnapshot() {
        syncRatingPolicy();
        reloadUnread();
        return getCachedSnapshot();
    }

    public Snapshot getCachedSnapshot() {
        boolean ehEnabled = Settings.getAutoSubscriptionUpdatesEh();
        boolean bookmarkEnabled = Settings.getAutoSubscriptionUpdatesBookmark();
        Unread unread = cachedUnread();
        int globalCount = ehEnabled && bookmarkEnabled ? unread.global.size() : 0;
        return new Snapshot(ehEnabled, bookmarkEnabled, unread.eh.size(), unread.bookmarks.size(), globalCount);
    }

    /** Copies the same GIDs counted by the sidebar before opening clears its badge. */
    @NonNull
    public Set<Long> getUnreadGalleryGids(int mode) {
        syncRatingPolicy();
        reloadUnread();
        return getCachedUnreadGalleryGids(mode);
    }

    public Set<Long> getCachedUnreadGalleryGids(int mode) {
        return unreadForMode(cachedUnread(), mode);
    }

    public Set<Long> loadUnreadGalleryGids(String scope, int mode) {
        return unreadForMode(readUnread(scope), mode);
    }

    private static Set<Long> unreadForMode(Unread unread, int mode) {
        return unread.forMode(mode, Settings.getAutoSubscriptionUpdatesEh()
                && Settings.getAutoSubscriptionUpdatesBookmark());
    }

    private Unread cachedUnread() {
        return mUnreadScope != null && mUnreadScope.equals(SubscriptionSearchContext.key()) ? mUnread : Unread.EMPTY;
    }

    /** Returns false when another check is active or no source is enabled. */
    public boolean checkForUpdates(boolean manual) {
        syncRatingPolicy();
        if (mChecking) {
            return false;
        }
        if (!manual && System.currentTimeMillis()
                < Settings.getLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L)) {
            return false;
        }

        mEhEnabled = Settings.getAutoSubscriptionUpdatesEh();
        mBookmarkEnabled = Settings.getAutoSubscriptionUpdatesBookmark();
        if (!mEhEnabled && !mBookmarkEnabled) {
            return false;
        }

        mSearchContext = SubscriptionSearchContext.key();
        mCheckDisplayKey = SubscriptionSearchContext.cacheKey();

        mChecking = true;
        mManual = manual;
        mGeneration++;
        mActiveRequests = 0;
        mCompletedSources = 0;
        mBookmarkPreparationFailed = false;
        mQueue.clear();
        mSources.clear();
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);
        final int generation = mGeneration;
        String scope = mSearchContext;
        boolean ehEnabled = mEhEnabled, bookmarkEnabled = mBookmarkEnabled;
        notifyStateChanged();
        EhApplication.getSearchHistoryExecutor().execute(
                () -> prepareSources(generation, scope, ehEnabled, bookmarkEnabled));
        return true;
    }

    public void cancelCheck() {
        if (!mChecking) {
            return;
        }
        mGeneration++;
        for (Source source : mSources) {
            if (source.request != null) {
                source.request.cancel();
                source.request = null;
            }
        }
        mQueue.clear();
        mSources.clear();
        mActiveRequests = 0;
        mChecking = false;
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME,
                System.currentTimeMillis() + CANCEL_RETRY_DELAY_MS);
        deliverCheckCompletions(null);
        notifyStateChanged();
        onSearchStatesChanged();
    }

    public void onSubscriptionOpened(int mode) {
        // The ordinary EH entry always fetches a new list. Aggregated entries may reuse results.
        if (!mChecking && mode == ListUrlBuilder.MODE_SUBSCRIPTION) resetCheckTimer();
        // Opening/failure does not acknowledge anything. Successful sources commit browsing below.
        notifyStateChanged();
    }

    private static Unread readUnread(String scope) {
        List<String> queries = new ArrayList<>();
        if (Settings.getAutoSubscriptionUpdatesBookmark()) {
            for (QuickSearch bookmark : EhDB.getSubscribedQuickSearch()) {
                ListUrlBuilder builder = new ListUrlBuilder(); builder.set(bookmark);
                queries.add(SearchUpdateStore.queryKey(builder));
            }
        }
        queries.add("legacy-unread:bookmark");
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            Set<Long> eh = store.unread(scope, List.of("eh-subscription", "legacy-unread:eh"));
            Set<Long> bookmarks = store.unread(scope, queries);
            Set<Long> global = new HashSet<>(eh);
            global.addAll(bookmarks);
            return new Unread(eh, bookmarks, global);
        }
    }

    private void applyUnread(int generation, String scope, Unread unread) {
        mAppliedUnreadGeneration = generation;
        mUnreadScope = scope;
        mUnread = unread;
    }

    private void reloadUnread() {
        String scope = SubscriptionSearchContext.key();
        applyUnread(++mUnreadGeneration, scope, readUnread(scope));
    }

    public void onSearchStatesChanged() {
        String scope = SubscriptionSearchContext.key();
        int generation = ++mUnreadGeneration;
        EhApplication.getSearchHistoryExecutor().execute(() -> {
            try {
                Unread unread = readUnread(scope);
                onMain(() -> {
                    if (generation != mUnreadGeneration || !scope.equals(SubscriptionSearchContext.key())) return;
                    applyUnread(generation, scope, unread);
                    notifyStateChanged();
                    dispatchPendingCheckResult(getCachedSnapshot());
                });
            } catch (RuntimeException error) {
                android.util.Log.w("SubscriptionResults", "Unable to load unread subscription state", error);
            }
        });
    }

    /** Compatibility display adapter; matching uses query-local SQLite state. */
    public static GallerySeenState getEhDisplayBaseline() {
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            return store.read(SubscriptionSearchContext.key(), "eh-subscription").displayWatermark();
        }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mMainHandler.post(action);
    }

    private boolean canContinueCheck(int generation) {
        if (!mChecking || generation != mGeneration) return false;
        if (mSearchContext.equals(SubscriptionSearchContext.key())) return true;
        cancelCheck();
        return false;
    }

    private void prepareSources(int generation, String scope, boolean ehEnabled, boolean bookmarkEnabled) {
        if (generation != mGeneration) return;
        List<Source> sources = new ArrayList<>();
        if (ehEnabled) sources.add(new Source());
        boolean bookmarkPreparationFailed = false;
        if (bookmarkEnabled) {
            try {
                for (BookmarkSubscriptionPlanner.Source plan :
                        SearchUpdateSession.plan(EhDB.getSubscribedQuickSearch(), scope)) {
                    sources.add(new Source(plan));
                }
            } catch (RuntimeException error) {
                bookmarkPreparationFailed = true;
                android.util.Log.w("SubscriptionResults", "Unable to plan bookmark checks", error);
            }
        }
        for (Source source : sources) {
            if (generation != mGeneration) return;
            try {
                source.session = new SearchUpdateSession(source.builder, source.plan, true, scope);
            } catch (RuntimeException error) {
                android.util.Log.w("SubscriptionResults", "Unable to read subscription checkpoint", error);
            }
        }
        boolean preparationFailed = bookmarkPreparationFailed;
        onMain(() -> {
            if (!canContinueCheck(generation)) return;
            mBookmarkPreparationFailed = preparationFailed;
            mSources.addAll(sources);
            for (Source source : sources) {
                if (source.session != null) mQueue.addLast(source);
                else completeSource(source, false);
            }
            pumpRequests(generation);
        });
    }

    private void pumpRequests(int generation) {
        while (mChecking && generation == mGeneration
                && mActiveRequests < MAX_CONCURRENT_REQUESTS && !mQueue.isEmpty()) {
            Source source = mQueue.removeFirst();
            if (source.pageIndex == 0 && source.retries == 0) source.startedRealtime = SystemClock.elapsedRealtime();

            String url;
            if (source.pageIndex == 0) {
                source.builder.setPageIndex(0);
                url = source.builder.buildWithoutRatingFilter();
            } else if (!TextUtils.isEmpty(source.nextHref)) {
                url = source.nextHref;
            } else {
                source.builder.setPageIndex(source.pageIndex);
                url = source.builder.buildWithoutRatingFilter();
            }

            if (TextUtils.isEmpty(url)) {
                completeSource(source, false);
                continue;
            }

            if (source.pageIndex >= 1000
                    || (source.retries == 0 && !source.visitedUrls.add(url))) {
                completeSource(source, false);
                continue;
            }
            source.ratingMinimumHundredths = Math.max(Settings.getPersistentMinRatingHundredths(),
                    source.builder.getSubscriptionMinRatingHundredths());
            EhRequest request = new EhRequest()
                    .setBackground(true)
                    .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
                    .setArgs(SearchRating.withoutServerFilter(url), source.builder.getMode(), true,
                            source.plan != null && source.plan.isMergedUploaderSearch(),
                            source.ratingMinimumHundredths, false, null, null, true)
                    .setCallback(new EhClient.Callback<GalleryListParser.Result>() {
                        @Override
                        public void onSuccess(GalleryListParser.Result result) {
                            onPageSuccess(generation, source, result);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            onPageFailure(generation, source, e);
                        }

                        @Override
                        public void onCancel() {
                            // Cancellation invalidates the generation before cancelling requests.
                        }
                    });
            source.request = request;
            mActiveRequests++;
            mClient.execute(request);
        }
        maybeFinishCheck();
    }

    private void onPageSuccess(int generation, @NonNull Source source,
                               @NonNull GalleryListParser.Result result) {
        if (!canContinueCheck(generation) || source.complete) return;
        source.request = null;
        source.retries = 0;

        if (result.customErrorString != null) {
            finishRequest(source);
            completeSource(source, false);
            pumpRequests(generation);
            return;
        }
        if (source.pageIndex == 0) {
            source.firstPage = RecentCheckSource.fromPage(source.builder, source.plan, result,
                    source.ratingMinimumHundredths, source.startedRealtime);
        }
        source.nextHref = result.nextHref;
        boolean hasHref = !TextUtils.isEmpty(result.nextHref);
        boolean hasIndexedPage = result.pages > 0
                && source.pageIndex + 1 < result.pages;
        // Keep the source's slot until history is processed so queued pages stay bounded.
        EhApplication.getSearchHistoryExecutor().execute(
                () -> processPageHistory(generation, source, result, hasHref || hasIndexedPage));
    }

    private void processPageHistory(int generation, Source source, GalleryListParser.Result result, boolean hasMore) {
        if (generation != mGeneration) return;
        boolean shouldContinue = false, success = false;
        try {
            source.session.observe(result, hasMore);
            shouldContinue = hasMore && !source.session.complete();
            if (generation != mGeneration) return;
            if (!shouldContinue) source.session.commit(false);
            success = true;
        } catch (RuntimeException error) {
            android.util.Log.w("SubscriptionResults", "Unable to process subscription page history", error);
        }
        boolean loadNextPage = shouldContinue && success;
        boolean historySaved = success;
        onMain(() -> onPageHistoryProcessed(generation, source, loadNextPage, historySaved));
    }

    private void onPageHistoryProcessed(int generation, Source source, boolean shouldContinue, boolean success) {
        if (!canContinueCheck(generation)) return;
        finishRequest(source);
        if (shouldContinue) {
            source.pageIndex++;
            mQueue.addLast(source);
        } else {
            completeSource(source, success);
        }
        pumpRequests(generation);
    }

    private void onPageFailure(int generation, @NonNull Source source, Exception error) {
        if (!canContinueCheck(generation) || source.complete) return;
        finishRequest(source);
        long delay = SubscriptionRetry.delayMillis(error, source.retries);
        if (delay >= 0) {
            source.retries++;
            mMainHandler.postDelayed(() -> {
                if (!canContinueCheck(generation)) return;
                mQueue.addLast(source);
                pumpRequests(generation);
            }, delay);
            pumpRequests(generation);
            return;
        }
        completeSource(source, false);
        pumpRequests(generation);
    }

    private void finishRequest(@NonNull Source source) {
        source.request = null;
        mActiveRequests = Math.max(0, mActiveRequests - 1);
    }

    private void completeSource(@NonNull Source source, boolean success) {
        if (source.complete) {
            return;
        }
        source.complete = true;
        source.successful = success;
        mCompletedSources++;
    }

    private void maybeFinishCheck() {
        if (!mChecking || mActiveRequests != 0 || !mQueue.isEmpty()
                || mCompletedSources != mSources.size()) {
            return;
        }
        int generation = mGeneration;
        int unreadGeneration = ++mUnreadGeneration;
        String scope = mSearchContext, displayKey = mCheckDisplayKey;
        boolean ehEnabled = mEhEnabled, bookmarkEnabled = mBookmarkEnabled;
        boolean failed = mBookmarkPreparationFailed || mSources.stream().anyMatch(source -> !source.successful);
        List<Source> sources = List.copyOf(mSources);
        // The worker owns the completed scan until it has built the smaller result.
        mSources.clear();
        EhApplication.getSearchHistoryExecutor().execute(() -> finishCheckInBackground(
                generation, unreadGeneration, scope, displayKey, ehEnabled, bookmarkEnabled, failed, sources));
    }

    private void finishCheckInBackground(int generation, int unreadGeneration, String scope, String displayKey,
            boolean ehEnabled, boolean bookmarkEnabled, boolean sourceFailed, List<Source> sources) {
        if (generation != mGeneration) return;
        Set<Long> newEh = new HashSet<>(), newBookmark = new HashSet<>();
        RecentCheckResult completedRound = null;
        Unread unread = null;
        boolean failed = sourceFailed;
        try {
            for (Source source : sources) if (source.successful) {
                (source.group == Group.EH ? newEh : newBookmark).addAll(source.session.newGids());
            }
            if (!failed) completedRound = buildRecentCheckResult(sources, scope, displayKey, ehEnabled, bookmarkEnabled);
            unread = readUnread(scope);
        } catch (RuntimeException error) {
            failed = true;
            android.util.Log.w("SubscriptionResults", "Unable to finish subscription check", error);
        }
        RecentCheckResult recent = completedRound;
        Unread refreshed = unread;
        boolean checkFailed = failed;
        onMain(() -> finishCheck(generation, unreadGeneration, refreshed, recent, checkFailed, newEh, newBookmark));
    }

    private void finishCheck(int generation, int unreadGeneration, @Nullable Unread unread,
                             @Nullable RecentCheckResult completedRound, boolean failed,
                             Set<Long> newEh, Set<Long> newBookmark) {
        if (!canContinueCheck(generation)) return;
        if (completedRound != null) {
            mResultStore.recordSnapshots(mSearchContext, mCheckDisplayKey, completedRound.sources,
                    new ArrayList<>(completedRound.displaySnapshots.values()));
        }
        // A newer unread refresh may already have updated the sidebar during this check.
        if (unread != null && unreadGeneration > mAppliedUnreadGeneration) {
            applyUnread(unreadGeneration, mSearchContext, unread);
        }
        resetCheckTimer();
        mChecking = false;
        deliverCheckCompletions(completedRound);
        Unread currentUnread = cachedUnread();
        newEh.retainAll(currentUnread.eh);
        newBookmark.retainAll(currentUnread.bookmarks);
        CheckResult result = new CheckResult(mManual, failed, newEh.size(), newBookmark.size(), getCachedSnapshot());
        mPendingCheckResult = result;
        mPendingCheckContext = mSearchContext;
        notifyStateChanged();
        dispatchPendingCheckResult(result.snapshot);
    }

    private static RecentCheckResult buildRecentCheckResult(List<Source> checkedSources, String scope,
            String displayKey, boolean ehEnabled, boolean bookmarkEnabled) {
        ArrayList<RecentCheckSource> sources = new ArrayList<>();
        Set<Long> unread = new HashSet<>(), bookmarkUnread = new HashSet<>();
        for (Source source : checkedSources) {
            if (source.firstPage != null) sources.add(source.firstPage);
            Set<Long> sourceUnread = source.session.displayNewGids();
            unread.addAll(sourceUnread);
            if (source.plan != null) bookmarkUnread.addAll(sourceUnread);
        }
        // Waiting pages receive this round only. The store retains earlier snapshots for explicit reuse.
        RecentCheckResult completedRound = new RecentCheckResult(sources, scope, displayKey, true);
        if (bookmarkEnabled) {
            int mode = ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION;
            completedRound.displaySnapshots.put(mode,
                    SubscriptionDisplaySnapshot.merge(mode, sources, bookmarkUnread, scope));
        }
        // An unchecked source cannot silently disappear from a new global snapshot.
        if (ehEnabled && (bookmarkEnabled || EhDB.getSubscribedQuickSearch().isEmpty())) {
            int mode = ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION;
            completedRound.displaySnapshots.put(mode, SubscriptionDisplaySnapshot.merge(mode, sources, unread, scope));
        }
        return completedRound;
    }

    private void notifyStateChanged() {
        Listener listener = mListener;
        if (listener != null) {
            listener.onSubscriptionUpdateStateChanged();
        }
    }

    static Set<Long> parseGids(@Nullable String value) {
        HashSet<Long> gids = new HashSet<>();
        if (value == null || value.isEmpty()) {
            return gids;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            try {
                long gid = Long.parseLong(part);
                if (gid > 0L) {
                    gids.add(gid);
                }
            } catch (NumberFormatException ignored) {
                // Ignore a damaged entry and preserve the rest of the unread state.
            }
        }
        return gids;
    }

    static String serializeGids(@NonNull Set<Long> gids) {
        StringBuilder builder = new StringBuilder();
        for (long gid : gids) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(gid);
        }
        return builder.toString();
    }

    private static void persistGids(@NonNull String key, @NonNull Set<Long> gids) {
        Settings.putString(key, serializeGids(gids));
    }

    static long calculateNextAutomaticCheckTime(long lastCheckTime,
                                                 long cancelRetryNotBeforeTime) {
        long intervalCheckTime = lastCheckTime > 0L
                ? lastCheckTime + Settings.getAutoSubscriptionUpdateIntervalMillis() : 0L;
        return Math.max(intervalCheckTime, cancelRetryNotBeforeTime);
    }

    static boolean isCheckResultFresh(long cachedRealtime, long nowRealtime) {
        return cachedRealtime >= 0L && nowRealtime >= cachedRealtime
                && nowRealtime - cachedRealtime <= CHECK_REUSE_WINDOW_MS;
    }

    static boolean shouldUseHomepageScan(int bookmarkCount, long lastCheckTime, long now) {
        if (bookmarkCount <= 8 || lastCheckTime <= 0 || now < lastCheckTime) return false;
        double minutes = (now - lastCheckTime) / 60_000.0;
        return minutes < 400 && minutes * 0.3 < bookmarkCount;
    }
}
