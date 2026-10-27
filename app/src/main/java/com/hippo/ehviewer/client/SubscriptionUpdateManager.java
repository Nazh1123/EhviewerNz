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
 * <p>Unread GIDs are append-only until the user opens the corresponding subscription page.
 * This intentionally favors request performance over correcting counts when a gallery is removed
 * or no longer matches a subscription. The user-viewed baseline and the internal checked cursor
 * are both monotonic; only a successful page refresh advances the former.</p>
 */
public final class SubscriptionUpdateManager {

    public static final long CANCEL_RETRY_DELAY_MS = 3L * 60L * 1000L;
    public static final long CHECK_REUSE_WINDOW_MS = 30L * 1000L;

    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final String KEY_HOMEPAGE_CHECKPOINT = "subscription_homepage_checkpoint_v1";
    private static final String KEY_SOURCE_PROGRESS = "subscription_source_progress_v2";
    private static final String KEY_UNREAD_RATING_POLICY = "subscription_unread_rating_policy";

    private static final String KEY_LAST_CHECK_TIME =
            "subscription_updates_last_check_time";
    private static final String KEY_CANCEL_RETRY_NOT_BEFORE_TIME =
            "subscription_updates_cancel_retry_not_before_time";
    private static final String KEY_LAST_SEEN_EH = "subscription_seen_eh_v2";
    private static final String KEY_LAST_SEEN_BOOKMARK = "subscription_seen_bookmark_v2";
    private static final String KEY_LAST_CHECKED_EH = "subscription_checked_eh_v2";
    private static final String KEY_LAST_CHECKED_BOOKMARK = "subscription_checked_bookmark_v2";
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
     * A successful source response retained from the most recent update check. The source can
     * represent either the EH subscription or a planned group of bookmark searches.
     */
    public static final class RecentCheckSource {
        public final int ratingMinimumHundredths;
        public final boolean requiresDisplayPreparation;
        @NonNull public final List<GalleryInfo> galleryInfoList;
        public final GallerySeenState observed;
        public final int initialResultCount;
        public final int pageIndex;
        @Nullable public final String nextHref;
        @Nullable public final String boundaryPosted;
        public final long boundaryGid;
        public final boolean hasBoundary;
        public final boolean exhausted;
        private final long startedRealtime;

        private final ListUrlBuilder mBuilder = new ListUrlBuilder();
        @Nullable private final String mBookmarkSourceKey;

        RecentCheckSource(@NonNull Source source) {
            observed = source.observed;
            ratingMinimumHundredths = source.ratingMinimumHundredths;
            requiresDisplayPreparation = source.requiresDisplayPreparation;
            mBuilder.set(source.builder);
            mBookmarkSourceKey = source.plan != null ? source.plan.getCacheKey() : null;
            galleryInfoList = Collections.unmodifiableList(
                    SubscriptionGallerySnapshot.copy(source.galleryInfoList));
            startedRealtime = source.startedRealtime;
            initialResultCount = source.initialResultCount;
            pageIndex = source.pageIndex;
            nextHref = source.nextHref;
            boundaryPosted = source.boundaryPosted;
            boundaryGid = source.boundaryGid;
            hasBoundary = source.hasBoundary;
            exhausted = source.exhausted;
        }

        public boolean isEhSubscription() {
            return mBuilder.getMode() == ListUrlBuilder.MODE_SUBSCRIPTION;
        }

        public boolean matchesBookmarkSource(BookmarkSubscriptionPlanner.Source source) {
            return source.getCacheKey().equals(mBookmarkSourceKey);
        }

        public boolean isFresh() {
            return isCheckResultFresh(startedRealtime, SystemClock.elapsedRealtime());
        }
    }

    /** A short-lived snapshot reusable by bookmark and global subscription entry points. */
    public static final class RecentCheckResult {
        @NonNull public final List<RecentCheckSource> sources;
        private final String contextKey;

        RecentCheckResult(@NonNull List<RecentCheckSource> sources, String contextKey) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.contextKey = contextKey;
        }

        public boolean matchesContext() { return contextKey.equals(SubscriptionSearchContext.key()); }

        boolean hasSourceForMode(int mode) {
            for (RecentCheckSource source : sources) {
                if (source.isFresh() && (mode == ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION
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
        boolean requiresDisplayPreparation;
        final Group group;
        @Nullable final BookmarkSubscriptionPlanner.Source plan;
        final ListUrlBuilder builder = new ListUrlBuilder();
        GallerySeenState cursor;
        String progressKey;
        long startedRealtime;
        int retries;
        final Set<Long> discoveredGids = new HashSet<>();
        final ArrayList<GalleryInfo> galleryInfoList = new ArrayList<>();

        int pageIndex;
        int initialResultCount;
        @Nullable String nextHref;
        @Nullable String boundaryPosted;
        long boundaryGid;
        GallerySeenState observed = GallerySeenState.empty();
        boolean hasBoundary;
        boolean exhausted;
        boolean queued;
        boolean loading;
        boolean complete;
        boolean successful;
        @Nullable EhRequest request;
        @Nullable SubscriptionHomepageMatcher homepageMatcher;
        @Nullable SubscriptionHomepageScan homepageScan;
        @Nullable String homepageContext;
        final List<Source> homepageSources = new ArrayList<>();
        final Set<String> visitedUrls = new HashSet<>();

        Source(GallerySeenState cursor) {
            group = Group.EH;
            plan = null;
            this.cursor = cursor;
            builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
            builder.setPageIndex(0);
        }

        Source(GallerySeenState cursor, SubscriptionHomepageMatcher matcher) {
            group = Group.BOOKMARK;
            plan = null;
            this.cursor = cursor;
            homepageMatcher = matcher;
            builder.setCategory(EhConfig.ALL_CATEGORY);
        }

        Source(GallerySeenState cursor,
               @NonNull BookmarkSubscriptionPlanner.Source plan) {
            group = Group.BOOKMARK;
            this.plan = plan;
            this.cursor = cursor;
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
    private final SubscriptionProgressStore mProgress;
    private String mSearchContext;
    private int mOldEhCount;
    private int mOldBookmarkCount;

    @Nullable private Listener mListener;
    @Nullable private RecentCheckResult mRecentCheckResult;
    private long mRecentCheckCompletedRealtime;
    private String mRatingPolicy;
    private int mGeneration;
    private int mActiveRequests;
    private int mCompletedSources;
    private int mEhSourceCount;
    private int mBookmarkSourceCount;
    private int mEhFailureCount;
    private int mBookmarkFailureCount;
    private GallerySeenState mEhCursor;
    private GallerySeenState mBookmarkCursor;
    private GallerySeenState mEhObserved;
    private GallerySeenState mBookmarkObserved;
    private boolean mChecking;
    private boolean mManual;
    private boolean mEhEnabled;
    private boolean mBookmarkEnabled;
    private boolean mBookmarkPreparationFailed;

    public SubscriptionUpdateManager(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        mClient = EhApplication.getEhClient(applicationContext);
        mExecutor = EhApplication.getExecutorService(applicationContext);
        mEhUnreadGids = parseGids(Settings.getString(KEY_UNREAD_EH_GIDS, ""));
        mBookmarkUnreadGids = parseGids(
                Settings.getString(KEY_UNREAD_BOOKMARK_GIDS, ""));
        mProgress = new SubscriptionProgressStore(Settings.getString(KEY_SOURCE_PROGRESS, ""));
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
        mRecentCheckResult = null;
        mRecentCheckCompletedRealtime = 0L;
        notifyStateChanged();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public boolean isChecking() {
        syncRatingPolicy();
        return mChecking;
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
        RecentCheckResult result = mRecentCheckResult;
        if (result == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (!result.matchesContext() || !isCheckResultFresh(mRecentCheckCompletedRealtime, now)) {
            mRecentCheckResult = null;
            mRecentCheckCompletedRealtime = 0L;
            return null;
        }
        if (!result.hasSourceForMode(mode)) {
            return null;
        }
        return result;
    }

    public void resetCheckTimer() {
        Settings.putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis());
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);
    }

    @NonNull
    public Snapshot getSnapshot() {
        boolean ehEnabled = Settings.getAutoSubscriptionUpdatesEh();
        boolean bookmarkEnabled = Settings.getAutoSubscriptionUpdatesBookmark();
        int globalCount = getUnreadGalleryGids(ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION).size();
        return new Snapshot(ehEnabled, bookmarkEnabled,
                mEhUnreadGids.size(), mBookmarkUnreadGids.size(), globalCount);
    }

    /** Copies the same GIDs counted by the sidebar before opening clears its badge. */
    @NonNull
    public Set<Long> getUnreadGalleryGids(int mode) {
        syncRatingPolicy();
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

        mRecentCheckResult = null;
        mRecentCheckCompletedRealtime = 0L;
        mSearchContext = SubscriptionSearchContext.key();
        mOldEhCount = mEhUnreadGids.size();
        mOldBookmarkCount = mBookmarkUnreadGids.size();

        mChecking = true;
        mManual = manual;
        mGeneration++;
        mActiveRequests = 0;
        mCompletedSources = 0;
        mEhSourceCount = 0;
        mBookmarkSourceCount = 0;
        mEhFailureCount = 0;
        mBookmarkFailureCount = 0;
        mEhObserved = GallerySeenState.empty();
        mBookmarkObserved = GallerySeenState.empty();
        mBookmarkPreparationFailed = false;
        mQueue.clear();
        mSources.clear();
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);

        mEhCursor = getLastSeenEhState().merge(readState(KEY_LAST_CHECKED_EH));
        mBookmarkCursor = getLastSeenBookmarkState().merge(readState(KEY_LAST_CHECKED_BOOKMARK));

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
    }

    public void onSubscriptionOpened(int mode) {
        cancelCheck();
        resetCheckTimer();
        switch (mode) {
            case ListUrlBuilder.MODE_SUBSCRIPTION:
                clearEhUnread();
                break;
            case ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION:
                clearBookmarkUnread();
                break;
            case ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION:
                clearEhUnread();
                clearBookmarkUnread();
                break;
            default:
                return;
        }
        notifyStateChanged();
    }

    public void recordEhPageViewed(@Nullable List<GalleryInfo> galleries) {
        if (galleries == null) return;
        recordGlobalPageViewed(GallerySeenState.empty().observe(galleries), null);
    }

    public void recordBookmarkPageViewed(GallerySeenState state) {
        recordGlobalPageViewed(null, state);
    }

    public static GallerySeenState getLastSeenEhState() { return readState(KEY_LAST_SEEN_EH); }
    public static GallerySeenState getLastSeenBookmarkState() { return readState(KEY_LAST_SEEN_BOOKMARK); }

    /** First opening can follow a completed check without any previous page-view record. */
    public static GallerySeenState getEhDisplayBaseline() {
        GallerySeenState viewed = getLastSeenEhState();
        return viewed.initialized() ? viewed : readState(KEY_LAST_CHECKED_EH);
    }

    public static GallerySeenState getBookmarkDisplayBaseline() {
        GallerySeenState viewed = getLastSeenBookmarkState();
        return viewed.initialized() ? viewed : readState(KEY_LAST_CHECKED_BOOKMARK);
    }

    /** null means this response did not observe that subscription group. */
    public void recordGlobalPageViewed(@Nullable GallerySeenState eh, @Nullable GallerySeenState bookmark) {
        if (eh != null && eh.initialized()) {
            clearEhUnread();
            mergeState(KEY_LAST_SEEN_EH, eh);
            mergeState(KEY_LAST_CHECKED_EH, eh);
        }
        if (bookmark != null && bookmark.initialized()) {
            clearBookmarkUnread();
            mergeState(KEY_LAST_SEEN_BOOKMARK, bookmark);
            mergeState(KEY_LAST_CHECKED_BOOKMARK, bookmark);
        }
        notifyStateChanged();
    }

    private static String stateKey(String key) { return key + "_" + Settings.getGallerySite(); }

    private static GallerySeenState readState(String key) {
        // v1 GIDs have no publication time. First successful v2 response seeds the new state.
        return GallerySeenState.parse(Settings.getString(stateKey(key), ""));
    }

    private static void mergeState(String key, GallerySeenState state) {
        GallerySeenState merged = readState(key).merge(state);
        Settings.putString(stateKey(key), merged.serialize());
    }

    private void prepareSources(int generation, @Nullable List<QuickSearch> quickSearches,
                                @Nullable Exception preparationFailure) {
        if (!mChecking || generation != mGeneration) {
            return;
        }

        if (mEhEnabled) {
            addSource(new Source(mEhCursor));
            mEhSourceCount++;
        }

        if (mBookmarkEnabled) {
            if (preparationFailure != null) {
                mBookmarkPreparationFailed = true;
            } else if (quickSearches != null) {
                List<Source> nativeSources = new ArrayList<>();
                for (BookmarkSubscriptionPlanner.Source plan : BookmarkSubscriptionPlanner.plan(quickSearches)) {
                    Source source = new Source(mBookmarkCursor, plan);
                    restoreSourceProgress(source);
                    nativeSources.add(source);
                }
                // Homepage scanning is disabled; every bookmark source remains a native search.
                /*
                if (Settings.getSubscriptionHomepageScan() && shouldUseHomepageScan(
                        quickSearches.size(), getLastCheckTime(), System.currentTimeMillis())) {
                    List<QuickSearch> homepageBookmarks = new ArrayList<>();
                    List<Source> homepageSources = new ArrayList<>();
                    List<GallerySeenState> boundaries = new ArrayList<>();
                    Map<QuickSearch, GallerySeenState> cursors = new HashMap<>();
                    for (Source source : nativeSources) {
                        // Keep the same native plan/key in both modes. A mixed plan stays native.
                        boolean supported = !(source.cursor.initialized() && source.cursor.minute() == 0);
                        for (QuickSearch bookmark : source.plan.getBookmarks()) {
                            supported &= BookmarkSubscriptionPlanner.homepageTerms(bookmark) != null;
                        }
                        if (!supported) continue;
                        homepageSources.add(source);
                        boundaries.add(source.cursor);
                        GallerySeenState synced = mProgress.get(homepageSyncKey(source), GallerySeenState.empty());
                        boolean nativeAdvanced = !source.cursor.initialized()
                                || !synced.serialize().equals(source.cursor.serialize());
                        for (QuickSearch bookmark : source.plan.getBookmarks()) {
                            homepageBookmarks.add(bookmark);
                            // Native searches may have advanced since the saved homepage checkpoint.
                            // Continuous homepage passes retain the homepage's wider complete-minute window.
                            if (nativeAdvanced) cursors.put(bookmark, source.cursor);
                        }
                    }
                    SubscriptionHomepageMatcher matcher = new SubscriptionHomepageMatcher(homepageBookmarks, cursors);
                    if (!matcher.isEmpty()) {
                        Source homepage = new Source(mBookmarkCursor, matcher);
                        homepage.homepageSources.addAll(homepageSources);
                        homepage.homepageContext = SubscriptionProgressStore.key(mSearchContext, matcher.fingerprint());
                        String saved = Settings.getString(KEY_HOMEPAGE_CHECKPOINT, "");
                        String prefix = homepage.homepageContext + "\n";
                        homepage.homepageScan = new SubscriptionHomepageScan(saved.startsWith(prefix)
                                ? saved.substring(prefix.length()) : "",
                                boundaries);
                        addSource(homepage);
                        mBookmarkSourceCount++;
                        nativeSources.removeAll(homepageSources);
                    }
                }
                */
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
                source.homepageScan != null ? "homepage:" + source.homepageContext
                        : source.plan == null ? "eh-subscription" : source.plan.getCacheKey());
        GallerySeenState completed = mProgress.get(source.progressKey, GallerySeenState.empty());
        if (source.homepageScan == null && completed.initialized()) {
            // Another source's checked watermark must not evict this source's six recent IDs.
            GallerySeenState viewed = source.group == Group.EH
                    ? getLastSeenEhState() : getLastSeenBookmarkState();
            source.cursor = completed.merge(viewed);
        }
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

            if (source.homepageScan != null && (source.pageIndex >= 1000
                    || (source.retries == 0 && !source.visitedUrls.add(url)))) {
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
                            source.ratingMinimumHundredths, false, null, source.cursor)
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
            /* Homepage request entry disabled.
            if (source.homepageScan != null) {
                request.setMethod(EhClient.METHOD_GET_SUBSCRIPTION_HOMEPAGE)
                        .setArgs(SearchRating.withoutServerFilter(url), source.homepageMatcher, source.homepageScan);
            }
            */
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

        if (source.pageIndex == 0) {
            source.initialResultCount = result.rawResultCount;
        }
        if (source.homepageScan == null) {
            source.requiresDisplayPreparation |= result.subscriptionRawGalleries != null;
            source.galleryInfoList.addAll(result.subscriptionRawGalleries != null
                    ? result.subscriptionRawGalleries : result.galleryInfoList);
        }
        if (result.customErrorString != null) {
            completeSource(source, false);
            pumpRequests(generation);
            return;
        }
        if (source.homepageMatcher != null) source.homepageMatcher.observe(result.galleryInfoList);
        // Even an unmatched homepage page establishes a publication-time display baseline.
        source.observed = source.observed.merge(result.rawSeenState).observe(result.galleryInfoList);
        for (GalleryInfo gallery : result.galleryInfoList) {
            if (source.homepageScan != null ? source.homepageScan.isNew(gallery)
                    : source.cursor.isNew(gallery)) source.discoveredGids.add(gallery.gid);
        }

        source.nextHref = result.nextHref;
        boolean hasHref = !TextUtils.isEmpty(result.nextHref);
        boolean hasIndexedPage = result.pages > 0
                && source.pageIndex + 1 < result.pages;
        boolean hasMore = hasHref || hasIndexedPage;
        if (result.rawResultCount > 0) {
            source.boundaryPosted = result.rawTailPosted;
            source.boundaryGid = result.rawTailGid;
            source.hasBoundary = true;
        }
        source.exhausted = !hasMore;
        boolean reachedCursor = result.rawResultCount == 0
                || source.cursor.reachedOlderThanWindow(result.rawTailPosted);
        long tailMinute = GallerySeenState.postedMinute(result.rawTailPosted);
        boolean seedNeedsMore = source.observed.retainedCount() < 6
                || (source.observed.minute() > 0 && tailMinute == source.observed.minute());
        boolean shouldContinue = hasMore && !reachedCursor
                && (source.cursor.initialized() || (tailMinute > 0 && seedNeedsMore));

        if (source.homepageScan != null) {
            try {
                if (result.subscriptionRawGalleries == null) throw new IllegalStateException("Missing homepage order");
                shouldContinue = source.homepageScan.shouldContinue(result.subscriptionRawGalleries, hasMore);
            } catch (RuntimeException error) {
                completeSource(source, false);
                pumpRequests(generation);
                return;
            }
        }
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
            Set<Long> unread = source.group == Group.EH ? mEhUnreadGids : mBookmarkUnreadGids;
            if (unread.addAll(source.discoveredGids)) persistGids(
                    source.group == Group.EH ? KEY_UNREAD_EH_GIDS : KEY_UNREAD_BOOKMARK_GIDS, unread);
            if (source.homepageScan != null) {
                String checkpoint = source.homepageScan.checkpoint();
                if (!checkpoint.isEmpty()) Settings.putString(KEY_HOMEPAGE_CHECKPOINT,
                        source.homepageContext + "\n" + checkpoint);
                for (Source nativeSource : source.homepageSources) {
                    GallerySeenState observed = source.homepageMatcher.observed(nativeSource.plan.getBookmarks());
                    // First checks seed history; later checks retain each search's own overlap.
                    GallerySeenState completed = nativeSource.cursor.initialized()
                            ? nativeSource.cursor.merge(observed) : source.observed;
                    mProgress.complete(nativeSource.progressKey, completed);
                    mProgress.snapshot(homepageSyncKey(nativeSource),
                            mProgress.get(nativeSource.progressKey, GallerySeenState.empty()));
                }
                Settings.putString(KEY_SOURCE_PROGRESS, mProgress.serialize());
            } else {
                mProgress.complete(source.progressKey, source.cursor.merge(source.observed));
                Settings.putString(KEY_SOURCE_PROGRESS, mProgress.serialize());
            }
        }
        mCompletedSources++;
        if (source.group == Group.EH) {
            if (success) {
                mEhObserved = mEhObserved.merge(source.observed);
            } else {
                mEhFailureCount++;
            }
        } else {
            if (success) {
                mBookmarkObserved = mBookmarkObserved.merge(source.observed);
            } else {
                mBookmarkFailureCount++;
            }
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

        if (mEhEnabled && ehSuccess) {
            mergeState(KEY_LAST_CHECKED_EH, mEhObserved);
        }
        if (mBookmarkEnabled && bookmarkSuccess) {
            mergeState(KEY_LAST_CHECKED_BOOKMARK, mBookmarkObserved);
        }

        if (sameContext) {
            cacheRecentCheckResult();
        }

        resetCheckTimer();
        mChecking = false;
        int newEhCount = Math.max(0, mEhUnreadGids.size() - mOldEhCount);
        int newBookmarkCount = Math.max(0,
                mBookmarkUnreadGids.size() - mOldBookmarkCount);
        CheckResult result = new CheckResult(mManual,
                !ehSuccess || !bookmarkSuccess, newEhCount, newBookmarkCount,
                getSnapshot());
        notifyStateChanged();
        Listener listener = mListener;
        if (listener != null) {
            listener.onSubscriptionUpdateCheckFinished(result);
        }
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

    private String homepageSyncKey(Source source) {
        return SubscriptionProgressStore.key(mSearchContext, "homepage-sync:" + source.plan.getCacheKey());
    }

    private void cacheRecentCheckResult() {
        ArrayList<RecentCheckSource> sources = new ArrayList<>();
        for (Source source : mSources) {
            if (source.successful && source.homepageScan == null) {
                sources.add(new RecentCheckSource(source));
            }
        }
        if (sources.isEmpty()) {
            mRecentCheckResult = null;
            mRecentCheckCompletedRealtime = 0L;
        } else {
            mRecentCheckResult = new RecentCheckResult(sources, mSearchContext);
            mRecentCheckCompletedRealtime = SystemClock.elapsedRealtime();
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
