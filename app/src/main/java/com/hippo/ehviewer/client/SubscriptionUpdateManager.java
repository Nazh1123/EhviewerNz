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

import com.alibaba.fastjson.JSON;
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
import java.util.concurrent.ExecutorService;

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

        private RecentCheckSource(ListUrlBuilder builder, @Nullable BookmarkSubscriptionPlanner.Source plan,
                GalleryListParser.Result page, int rating, long started) {
            completedRealtime = SystemClock.elapsedRealtime();
            completedTimeMillis = System.currentTimeMillis();
            startedRealtime = started;
            startedTimeMillis = completedTimeMillis - Math.max(0L, completedRealtime - started);
            ratingMinimumHundredths = rating;
            requiresDisplayPreparation = page.subscriptionRawGalleries != null
                    && page.subscriptionDisplayGalleries == null;
            mBuilder.set(builder);
            mBookmarkSourceKey = plan != null ? plan.getCacheKey() : null;
            galleryInfoList = Collections.unmodifiableList(SubscriptionGallerySnapshot.copy(
                    page.subscriptionDisplayGalleries != null ? page.subscriptionDisplayGalleries
                    : page.subscriptionRawGalleries != null ? page.subscriptionRawGalleries : page.galleryInfoList));
            rawGalleries = Collections.unmodifiableList(SubscriptionGallerySnapshot.copy(
                    page.rawGalleries != null ? page.rawGalleries
                    : page.subscriptionRawGalleries != null ? page.subscriptionRawGalleries : page.galleryInfoList));
            rawPublicationTimes = Collections.unmodifiableMap(new HashMap<>(page.rawPublicationTimes));
            observed = page.rawSeenState.observe(page.galleryInfoList);
            initialResultCount = page.rawResultCount;
            pageIndex = builder.getPageIndex();
            nextHref = page.nextHref;
            boundaryPosted = page.rawTailPosted;
            boundaryGid = page.rawTailGid;
            hasBoundary = page.rawResultCount > 0;
            exhausted = TextUtils.isEmpty(page.nextHref)
                    && !(page.pages > 0 && pageIndex + 1 < page.pages);
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
            return new RecentCheckSource(builder, plan, page, ratingMinimumHundredths, startedRealtime);
        }

        boolean sameSource(RecentCheckSource other) {
            return isEhSubscription() ? other.isEhSubscription()
                    : mBookmarkSourceKey != null && mBookmarkSourceKey.equals(other.mBookmarkSourceKey);
        }

        RecentCheckSource(JSONObject saved) {
            ratingMinimumHundredths = saved.getIntValue("rating");
            requiresDisplayPreparation = saved.getBooleanValue("prepare");
            mBuilder.setMode(saved.getBooleanValue("eh")
                    ? ListUrlBuilder.MODE_SUBSCRIPTION : ListUrlBuilder.MODE_NORMAL);
            mBookmarkSourceKey = saved.getString("bookmark");
            if (!isEhSubscription() && mBookmarkSourceKey == null) throw new IllegalArgumentException("Missing source");
            galleryInfoList = Collections.unmodifiableList(readGalleries(saved.getJSONArray("galleries")));
            rawGalleries = Collections.unmodifiableList(readGalleries(saved.getJSONArray("raw")));
            observed = GallerySeenState.parse(saved.getString("observed"));
            Map<Long, String> times = new HashMap<>();
            JSONObject savedTimes = saved.getJSONObject("publicationTimes");
            if (savedTimes != null) savedTimes.forEach((gid, posted) -> times.put(Long.parseLong(gid), (String) posted));
            rawPublicationTimes = Collections.unmodifiableMap(times);
            initialResultCount = saved.getIntValue("count");
            pageIndex = 0;
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

        JSONObject toJson() {
            JSONObject saved = new JSONObject();
            saved.put("eh", isEhSubscription()); saved.put("bookmark", mBookmarkSourceKey);
            saved.put("rating", ratingMinimumHundredths); saved.put("prepare", requiresDisplayPreparation);
            saved.put("galleries", writeGalleries(galleryInfoList)); saved.put("raw", writeGalleries(rawGalleries));
            JSONObject times = new JSONObject();
            rawPublicationTimes.forEach((gid, posted) -> times.put(Long.toString(gid), posted));
            saved.put("observed", observed.serialize()); saved.put("publicationTimes", times);
            saved.put("count", initialResultCount); saved.put("next", nextHref);
            saved.put("boundaryPosted", boundaryPosted); saved.put("boundaryGid", boundaryGid);
            saved.put("hasBoundary", hasBoundary); saved.put("exhausted", exhausted);
            saved.put("completed", completedTimeMillis); saved.put("started", startedTimeMillis);
            return saved;
        }

        private static JSONArray writeGalleries(List<GalleryInfo> galleries) {
            JSONArray saved = new JSONArray();
            for (GalleryInfo gallery : galleries) {
                JSONObject item = (JSONObject) JSON.toJSON(gallery);
                if (Float.isFinite(gallery.apiRating)) item.put("apiRating", gallery.apiRating);
                saved.add(item);
            }
            return saved;
        }

        private static List<GalleryInfo> readGalleries(JSONArray saved) {
            if (saved == null) throw new IllegalArgumentException("Missing galleries");
            List<GalleryInfo> galleries = new ArrayList<>();
            for (int i = 0; i < saved.size(); i++) {
                JSONObject item = saved.getJSONObject(i);
                GalleryInfo gallery = item.toJavaObject(GalleryInfo.class);
                if (gallery.gid <= 0) throw new IllegalArgumentException("Invalid gallery");
                gallery.apiRating = item.containsKey("apiRating") ? item.getFloatValue("apiRating") : Float.NaN;
                galleries.add(gallery);
            }
            return galleries;
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

        RecentCheckResult(@NonNull List<RecentCheckSource> sources, String contextKey) {
            this(sources, contextKey, SubscriptionSearchContext.cacheKey());
        }

        RecentCheckResult(@NonNull List<RecentCheckSource> sources, String contextKey, String displayKey) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.completedTimeMillis = sources.stream().mapToLong(source -> source.completedTimeMillis)
                    .min().orElse(System.currentTimeMillis());
            this.contextKey = contextKey;
            this.displayKey = displayKey;
            this.allowExpired = false;
        }

        private RecentCheckResult(RecentCheckResult result) {
            sources = result.sources;
            completedTimeMillis = result.completedTimeMillis;
            contextKey = result.contextKey;
            displayKey = result.displayKey;
            allowExpired = true;
        }

        public boolean matchesContext() { return contextKey.equals(SubscriptionSearchContext.key())
                && displayKey.equals(SubscriptionSearchContext.cacheKey()); }

        public boolean canReuseSource(RecentCheckSource source) {
            return allowExpired || source.isFresh();
        }

        boolean hasSourceForMode(int mode) {
            for (RecentCheckSource source : sources) {
                if (canReuseSource(source) && (mode == ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION
                        || (mode == ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION
                        && !source.isEhSubscription()))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Source {
        int ratingMinimumHundredths;
        final Group group;
        @Nullable final BookmarkSubscriptionPlanner.Source plan;
        final ListUrlBuilder builder = new ListUrlBuilder();
        SearchUpdateSession session;
        String progressKey;
        long startedRealtime;
        int retries;

        int pageIndex;
        @Nullable String nextHref;
        boolean queued;
        boolean loading;
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
    private final ExecutorService mExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Source> mQueue = new ArrayDeque<>();
    private final ArrayList<Source> mSources = new ArrayList<>();
    private final Set<Long> mEhUnreadGids;
    private final Set<Long> mBookmarkUnreadGids;
    private String mSearchContext;

    @Nullable private Listener mListener;
    @Nullable private CheckResult mPendingCheckResult;
    @Nullable private String mPendingCheckContext;
    private final SubscriptionResultStore mResultStore;
    private String mCheckDisplayKey;
    private String mRatingPolicy;
    private int mGeneration;
    private int mActiveRequests;
    private int mCompletedSources;
    private int mEhSourceCount;
    private int mBookmarkSourceCount;
    private int mEhFailureCount;
    private int mBookmarkFailureCount;
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
        mExecutor = EhApplication.getExecutorService(applicationContext);
        mEhUnreadGids = parseGids(Settings.getString(KEY_UNREAD_EH_GIDS, ""));
        mBookmarkUnreadGids = parseGids(
                Settings.getString(KEY_UNREAD_BOOKMARK_GIDS, ""));
        String scope = SubscriptionSearchContext.key();
        String owner = Settings.getString("search_updates_legacy_owner", "");
        if (owner.isEmpty()) { Settings.putString("search_updates_legacy_owner", scope); owner = scope; }
        if (owner.equals(scope)) try (SearchUpdateStore store = SearchUpdateStore.open()) {
            store.migrateUnread(scope, "eh", mEhUnreadGids);
            store.migrateUnread(scope, "bookmark", mBookmarkUnreadGids);
        }
        mRatingPolicy = Settings.getString(KEY_UNREAD_RATING_POLICY, "true:0");
        syncRatingPolicy();
    }

    private void syncRatingPolicy() {
        String policy = Settings.getEnablePreciseRatingFilter() + ":" + Settings.getPersistentMinRatingHundredths();
        if (policy.equals(mRatingPolicy)) return;
        mRatingPolicy = policy;
        clearEhUnread();
        clearBookmarkUnread();
        Settings.putString(KEY_UNREAD_RATING_POLICY, policy);
        cancelCheck();
        mResultStore.clear();
        notifyStateChanged();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
        dispatchPendingCheckResult();
    }

    private void dispatchPendingCheckResult() {
        Listener listener = mListener;
        CheckResult result = mPendingCheckResult;
        if (listener == null || result == null) return;
        mPendingCheckResult = null;
        if (!SubscriptionSearchContext.key().equals(mPendingCheckContext)) return;
        // A subscription may have been opened while this activity was stopped.
        Snapshot snapshot = getSnapshot();
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

    private void deliverCheckCompletions() {
        if (mCheckCompletions.isEmpty()) return;
        List<CheckCompletion> completions = new ArrayList<>(mCheckCompletions);
        mCheckCompletions.clear();
        RecentCheckResult saved = mResultStore.get();
        RecentCheckResult result = saved != null && saved.matchesContext()
                ? new RecentCheckResult(saved) : null;
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
        RecentCheckResult result = mResultStore.get();
        if (result == null) {
            return null;
        }
        if (!result.matchesContext()) {
            return null;
        }
        if (!result.hasSourceForMode(mode)) {
            return null;
        }
        return result;
    }

    /** Explicit reuse keeps the last compatible result until another check replaces it. */
    @Nullable
    public RecentCheckResult getReusableCheckResult(int mode) {
        syncRatingPolicy();
        RecentCheckResult saved = mResultStore.get();
        if (saved == null || !saved.matchesContext()) return null;
        RecentCheckResult result = new RecentCheckResult(saved);
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

    public void resetCheckTimer() {
        Settings.putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis());
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);
    }

    @NonNull
    public Snapshot getSnapshot() {
        boolean ehEnabled = Settings.getAutoSubscriptionUpdatesEh();
        boolean bookmarkEnabled = Settings.getAutoSubscriptionUpdatesBookmark();
        reloadUnread();
        int globalCount = getUnreadGalleryGids(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION).size();
        return new Snapshot(ehEnabled, bookmarkEnabled,
                mEhUnreadGids.size(), mBookmarkUnreadGids.size(), globalCount);
    }

    /** Copies the same GIDs counted by the sidebar before opening clears its badge. */
    @NonNull
    public Set<Long> getUnreadGalleryGids(int mode) {
        syncRatingPolicy();
        reloadUnread();
        Set<Long> gids = new HashSet<>();
        switch (mode) {
            case ListUrlBuilder.MODE_SUBSCRIPTION:
                gids.addAll(mEhUnreadGids);
                break;
            case ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION:
                gids.addAll(mBookmarkUnreadGids);
                break;
            case ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION:
                if (Settings.getAutoSubscriptionUpdatesEh()
                        && Settings.getAutoSubscriptionUpdatesBookmark()) {
                    gids.addAll(mEhUnreadGids);
                    gids.addAll(mBookmarkUnreadGids);
                }
                break;
        }
        return gids;
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
        reloadUnread();

        mChecking = true;
        mManual = manual;
        mGeneration++;
        mActiveRequests = 0;
        mCompletedSources = 0;
        mEhSourceCount = 0;
        mBookmarkSourceCount = 0;
        mEhFailureCount = 0;
        mBookmarkFailureCount = 0;
        mBookmarkPreparationFailed = false;
        mQueue.clear();
        mSources.clear();
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);


        notifyStateChanged();
        final int generation = mGeneration;
        if (mBookmarkEnabled) {
            mExecutor.execute(() -> {
                List<QuickSearch> quickSearches = null;
                Exception error = null;
                try {
                    quickSearches = EhDB.getSubscribedQuickSearch();
                } catch (Exception e) {
                    error = e;
                }
                List<QuickSearch> result = quickSearches;
                Exception failure = error;
                mMainHandler.post(() -> prepareSources(generation, result, failure));
            });
        } else {
            prepareSources(generation, null, null);
        }
        return true;
    }

    public void cancelCheck() {
        if (!mChecking) {
            return;
        }
        if (mSearchContext.equals(SubscriptionSearchContext.key())) cacheRecentCheckResult();
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
        notifyStateChanged();
        deliverCheckCompletions();
    }

    public void onSubscriptionOpened(int mode) {
        // The ordinary EH entry always fetches a new list. Aggregated entries may reuse results.
        if (!mChecking && mode == ListUrlBuilder.MODE_SUBSCRIPTION) resetCheckTimer();
        // Opening/failure does not acknowledge anything. Successful sources commit browsing below.
        notifyStateChanged();
    }

    private void reloadUnread() {
        String scope = SubscriptionSearchContext.key();
        List<String> queries = new ArrayList<>();
        if (Settings.getAutoSubscriptionUpdatesBookmark()) {
            for (QuickSearch bookmark : EhDB.getSubscribedQuickSearch()) {
                ListUrlBuilder builder = new ListUrlBuilder(); builder.set(bookmark);
                queries.add(SearchUpdateStore.queryKey(builder));
            }
        }
        queries.add("legacy-unread:bookmark");
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            mEhUnreadGids.clear();
            mEhUnreadGids.addAll(store.unread(scope, List.of("eh-subscription", "legacy-unread:eh")));
            mBookmarkUnreadGids.clear();
            mBookmarkUnreadGids.addAll(store.unread(scope, queries));
        }
    }

    public void onSearchStatesChanged() {
        reloadUnread();
        notifyStateChanged();
    }

    /** Compatibility display adapter; matching uses query-local SQLite state. */
    public static GallerySeenState getEhDisplayBaseline() {
        try (SearchUpdateStore store = SearchUpdateStore.open()) {
            return store.read(SubscriptionSearchContext.key(), "eh-subscription").displayWatermark();
        }
    }

    private void prepareSources(int generation, @Nullable List<QuickSearch> quickSearches,
                                @Nullable Exception preparationFailure) {
        if (!mChecking || generation != mGeneration) {
            return;
        }

        if (mEhEnabled) {
            addSource(new Source());
            mEhSourceCount++;
        }

        if (mBookmarkEnabled) {
            if (preparationFailure != null) {
                mBookmarkPreparationFailed = true;
            } else if (quickSearches != null) {
                List<Source> nativeSources = new ArrayList<>();
                for (BookmarkSubscriptionPlanner.Source plan : SearchUpdateSession.plan(quickSearches)) {
                    Source source = new Source(plan);
                    restoreSourceProgress(source);
                    nativeSources.add(source);
                }
                for (Source source : nativeSources) {
                    addSource(source);
                    mBookmarkSourceCount++;
                }
            }
        }

        if (mSources.isEmpty()) {
            finishCheck();
            return;
        }
        pumpRequests(generation);
    }

    private void addSource(@NonNull Source source) {
        if (source.progressKey == null) restoreSourceProgress(source);
        source.queued = true;
        mSources.add(source);
        mQueue.addLast(source);
    }

    private void restoreSourceProgress(@NonNull Source source) {
        source.progressKey = SubscriptionProgressStore.key(mSearchContext,
                source.plan == null ? "eh-subscription" : source.plan.getCacheKey());
        source.session = new SearchUpdateSession(source.builder, source.plan);
    }

    private void pumpRequests(int generation) {
        while (mChecking && generation == mGeneration
                && mActiveRequests < MAX_CONCURRENT_REQUESTS && !mQueue.isEmpty()) {
            Source source = mQueue.removeFirst();
            source.queued = false;
            source.loading = true;
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
        if (!mChecking || generation != mGeneration || source.complete) {
            return;
        }
        finishRequest(source);
        source.retries = 0;

        if (result.customErrorString != null) {
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
        boolean hasMore = hasHref || hasIndexedPage;
        source.session.observe(result, hasMore);
        boolean shouldContinue = hasMore && !source.session.complete();

        if (shouldContinue) {
            source.pageIndex++;
            source.queued = true;
            mQueue.addLast(source);
        } else {
            completeSource(source, true);
        }
        pumpRequests(generation);
    }

    private void onPageFailure(int generation, @NonNull Source source, Exception error) {
        if (!mChecking || generation != mGeneration || source.complete) {
            return;
        }
        finishRequest(source);
        long delay = SubscriptionRetry.delayMillis(error, source.retries);
        if (delay >= 0) {
            source.retries++;
            source.queued = true;
            mMainHandler.postDelayed(() -> {
                if (!mChecking || generation != mGeneration) return;
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
        source.loading = false;
        source.request = null;
        mActiveRequests = Math.max(0, mActiveRequests - 1);
    }

    private void completeSource(@NonNull Source source, boolean success) {
        if (source.complete) {
            return;
        }
        source.complete = true;
        source.successful = success;
        source.loading = false;
        source.queued = false;
        if (success && mSearchContext.equals(SubscriptionSearchContext.key())) {
            source.session.commit(false);
            reloadUnread();
        }
        mCompletedSources++;
        if (!success) {
            if (source.group == Group.EH) mEhFailureCount++;
            else mBookmarkFailureCount++;
        }
    }

    private void maybeFinishCheck() {
        if (!mChecking || mActiveRequests != 0 || !mQueue.isEmpty()
                || mCompletedSources != mSources.size()) {
            return;
        }
        finishCheck();
    }

    private void finishCheck() {
        if (!mChecking) {
            return;
        }

        boolean sameContext = mSearchContext.equals(SubscriptionSearchContext.key());
        boolean ehSuccess = sameContext && (!mEhEnabled
                || (mEhFailureCount == 0 && mCompletedSourcesFor(Group.EH) == mEhSourceCount));
        boolean bookmarkSuccess = sameContext && (!mBookmarkEnabled
                || (!mBookmarkPreparationFailed && mBookmarkFailureCount == 0
                && mCompletedSourcesFor(Group.BOOKMARK) == mBookmarkSourceCount));

        if (sameContext) {
            cacheRecentCheckResult();
        }

        resetCheckTimer();
        mChecking = false;
        reloadUnread();
        Set<Long> newEh = new HashSet<>(), newBookmark = new HashSet<>();
        for (Source source : mSources) if (source.successful) {
            (source.group == Group.EH ? newEh : newBookmark).addAll(source.session.newGids());
        }
        newEh.retainAll(mEhUnreadGids);
        newBookmark.retainAll(mBookmarkUnreadGids);
        int newEhCount = newEh.size(), newBookmarkCount = newBookmark.size();
        CheckResult result = new CheckResult(mManual,
                !ehSuccess || !bookmarkSuccess, newEhCount, newBookmarkCount,
                getSnapshot());
        notifyStateChanged();
        mPendingCheckResult = result;
        mPendingCheckContext = mSearchContext;
        dispatchPendingCheckResult();
        deliverCheckCompletions();
    }

    private int mCompletedSourcesFor(@NonNull Group group) {
        int count = 0;
        for (Source source : mSources) {
            if (source.group == group && source.complete) {
                count++;
            }
        }
        return count;
    }

    private void cacheRecentCheckResult() {
        ArrayList<RecentCheckSource> sources = new ArrayList<>();
        for (Source source : mSources) {
            if (source.successful && source.firstPage != null) {
                sources.add(source.firstPage);
            }
        }
        if (mSearchContext.equals(SubscriptionSearchContext.key())
                && mCheckDisplayKey.equals(SubscriptionSearchContext.cacheKey())) {
            mResultStore.record(mSearchContext, mCheckDisplayKey, sources);
        }
    }

    private void clearEhUnread() {
        if (!mEhUnreadGids.isEmpty()) {
            mEhUnreadGids.clear();
            persistGids(KEY_UNREAD_EH_GIDS, mEhUnreadGids);
        }
    }

    private void clearBookmarkUnread() {
        if (!mBookmarkUnreadGids.isEmpty()) {
            mBookmarkUnreadGids.clear();
            persistGids(KEY_UNREAD_BOOKMARK_GIDS, mBookmarkUnreadGids);
        }
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
