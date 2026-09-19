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
import java.util.HashSet;
import java.util.List;
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

    public static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    public static final long CANCEL_RETRY_DELAY_MS = 3L * 60L * 1000L;
    public static final long AUTOMATIC_CHECK_REUSE_WINDOW_MS = 30L * 1000L;

    private static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final String KEY_SOURCE_PROGRESS = "subscription_source_progress_v1";

    private static final String KEY_LAST_CHECK_TIME =
            "subscription_updates_last_check_time";
    private static final String KEY_CANCEL_RETRY_NOT_BEFORE_TIME =
            "subscription_updates_cancel_retry_not_before_time";
    private static final String KEY_LAST_SEEN_EH_GID =
            "subscription_updates_last_seen_eh_gid";
    private static final String KEY_LAST_SEEN_BOOKMARK_GID =
            "subscription_updates_last_seen_bookmark_gid";
    private static final String KEY_LAST_CHECKED_EH_GID =
            "subscription_updates_last_checked_eh_gid";
    private static final String KEY_LAST_CHECKED_BOOKMARK_GID =
            "subscription_updates_last_checked_bookmark_gid";
    private static final String KEY_EH_BASELINE_INITIALIZED =
            "subscription_updates_eh_baseline_initialized";
    private static final String KEY_BOOKMARK_BASELINE_INITIALIZED =
            "subscription_updates_bookmark_baseline_initialized";
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
     * A successful source response retained from the most recent automatic check. The source can
     * represent either the EH subscription or a planned group of bookmark searches.
     */
    public static final class AutomaticCheckSource {
        @NonNull public final List<GalleryInfo> galleryInfoList;
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

        AutomaticCheckSource(@NonNull Source source) {
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
            return isAutomaticCheckResultFresh(startedRealtime, SystemClock.elapsedRealtime());
        }
    }

    /** A short-lived snapshot reusable by both subscription entry points. */
    public static final class AutomaticCheckResult {
        @NonNull public final List<AutomaticCheckSource> sources;
        private final String contextKey;

        AutomaticCheckResult(@NonNull List<AutomaticCheckSource> sources, String contextKey) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.contextKey = contextKey;
        }

        public boolean matchesContext() { return contextKey.equals(SubscriptionSearchContext.key()); }

        boolean hasSourceForMode(int mode) {
            for (AutomaticCheckSource source : sources) {
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
        final Group group;
        @Nullable final BookmarkSubscriptionPlanner.Source plan;
        final ListUrlBuilder builder = new ListUrlBuilder();
        long cursorGid;
        boolean baselineInitialized;
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
        long maxObservedGid;
        boolean hasBoundary;
        boolean exhausted;
        boolean queued;
        boolean loading;
        boolean complete;
        boolean successful;
        @Nullable EhRequest request;

        Source(long cursorGid, boolean baselineInitialized) {
            group = Group.EH;
            plan = null;
            this.cursorGid = cursorGid;
            this.baselineInitialized = baselineInitialized;
            builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
            builder.setPageIndex(0);
        }

        Source(long cursorGid, boolean baselineInitialized,
               @NonNull BookmarkSubscriptionPlanner.Source plan) {
            group = Group.BOOKMARK;
            this.plan = plan;
            this.cursorGid = cursorGid;
            this.baselineInitialized = baselineInitialized;
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
    @Nullable private AutomaticCheckResult mRecentAutomaticCheckResult;
    private long mRecentAutomaticCheckCompletedRealtime;
    private int mGeneration;
    private int mActiveRequests;
    private int mCompletedSources;
    private int mEhSourceCount;
    private int mBookmarkSourceCount;
    private int mEhFailureCount;
    private int mBookmarkFailureCount;
    private long mEhCursorGid;
    private long mBookmarkCursorGid;
    private long mEhMaxObservedGid;
    private long mBookmarkMaxObservedGid;
    private boolean mChecking;
    private boolean mManual;
    private boolean mEhEnabled;
    private boolean mBookmarkEnabled;
    private boolean mEhBaselineInitialized;
    private boolean mBookmarkBaselineInitialized;
    private boolean mBookmarkPreparationFailed;

    public SubscriptionUpdateManager(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        mClient = EhApplication.getEhClient(applicationContext);
        mExecutor = EhApplication.getExecutorService(applicationContext);
        mEhUnreadGids = parseGids(Settings.getString(KEY_UNREAD_EH_GIDS, ""));
        mBookmarkUnreadGids = parseGids(
                Settings.getString(KEY_UNREAD_BOOKMARK_GIDS, ""));
        mProgress = new SubscriptionProgressStore(Settings.getString(KEY_SOURCE_PROGRESS, ""));
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public boolean isChecking() {
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
     * Returns fresh automatic-check data without consuming it for the other entry point.
     */
    @Nullable
    public AutomaticCheckResult takeRecentAutomaticCheckResult(int mode) {
        AutomaticCheckResult result = mRecentAutomaticCheckResult;
        if (result == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (!result.matchesContext() || !isAutomaticCheckResultFresh(mRecentAutomaticCheckCompletedRealtime, now)) {
            mRecentAutomaticCheckResult = null;
            mRecentAutomaticCheckCompletedRealtime = 0L;
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
        int globalCount = 0;
        if (ehEnabled && bookmarkEnabled) {
            HashSet<Long> globalGids = new HashSet<>(mEhUnreadGids);
            globalGids.addAll(mBookmarkUnreadGids);
            globalCount = globalGids.size();
        }
        return new Snapshot(ehEnabled, bookmarkEnabled,
                mEhUnreadGids.size(), mBookmarkUnreadGids.size(), globalCount);
    }

    /** Returns false when another check is active or no source is enabled. */
    public boolean checkForUpdates(boolean manual) {
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

        mRecentAutomaticCheckResult = null;
        mRecentAutomaticCheckCompletedRealtime = 0L;
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
        mEhMaxObservedGid = 0L;
        mBookmarkMaxObservedGid = 0L;
        mBookmarkPreparationFailed = false;
        mQueue.clear();
        mSources.clear();
        Settings.putLong(KEY_CANCEL_RETRY_NOT_BEFORE_TIME, 0L);

        mEhCursorGid = Math.max(
                Settings.getLong(KEY_LAST_SEEN_EH_GID, 0L),
                Settings.getLong(KEY_LAST_CHECKED_EH_GID, 0L));
        mBookmarkCursorGid = Math.max(
                Settings.getLong(KEY_LAST_SEEN_BOOKMARK_GID, 0L),
                Settings.getLong(KEY_LAST_CHECKED_BOOKMARK_GID, 0L));
        mEhBaselineInitialized = Settings.getBoolean(
                KEY_EH_BASELINE_INITIALIZED, false);
        mBookmarkBaselineInitialized = Settings.getBoolean(
                KEY_BOOKMARK_BASELINE_INITIALIZED, false);

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
        if (!mManual && mSearchContext.equals(SubscriptionSearchContext.key())) cacheAutomaticCheckResult();
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
        clearEhUnread();
        Settings.putBoolean(KEY_EH_BASELINE_INITIALIZED, true);
        long maxGid = findMaxGid(galleries);
        if (maxGid > 0L) {
            putMaxLong(KEY_LAST_SEEN_EH_GID, maxGid);
            putMaxLong(KEY_LAST_CHECKED_EH_GID, maxGid);
        }
        notifyStateChanged();
    }

    public void recordBookmarkPageViewed(long maxGid) {
        clearBookmarkUnread();
        Settings.putBoolean(KEY_BOOKMARK_BASELINE_INITIALIZED, true);
        if (maxGid > 0L) {
            putMaxLong(KEY_LAST_SEEN_BOOKMARK_GID, maxGid);
            putMaxLong(KEY_LAST_CHECKED_BOOKMARK_GID, maxGid);
        }
        notifyStateChanged();
    }

    public void recordGlobalPageViewed(long ehMaxGid, long bookmarkMaxGid) {
        clearEhUnread();
        clearBookmarkUnread();
        Settings.putBoolean(KEY_EH_BASELINE_INITIALIZED, true);
        Settings.putBoolean(KEY_BOOKMARK_BASELINE_INITIALIZED, true);
        if (ehMaxGid > 0L) {
            putMaxLong(KEY_LAST_SEEN_EH_GID, ehMaxGid);
            putMaxLong(KEY_LAST_CHECKED_EH_GID, ehMaxGid);
        }
        if (bookmarkMaxGid > 0L) {
            putMaxLong(KEY_LAST_SEEN_BOOKMARK_GID, bookmarkMaxGid);
            putMaxLong(KEY_LAST_CHECKED_BOOKMARK_GID, bookmarkMaxGid);
        }
        notifyStateChanged();
    }

    private void prepareSources(int generation, @Nullable List<QuickSearch> quickSearches,
                                @Nullable Exception preparationFailure) {
        if (!mChecking || generation != mGeneration) {
            return;
        }

        if (mEhEnabled) {
            addSource(new Source(mEhCursorGid, mEhBaselineInitialized));
            mEhSourceCount++;
        }

        if (mBookmarkEnabled) {
            if (preparationFailure != null) {
                mBookmarkPreparationFailed = true;
            } else if (quickSearches != null) {
                for (BookmarkSubscriptionPlanner.Source plan
                        : BookmarkSubscriptionPlanner.plan(quickSearches)) {
                    addSource(new Source(mBookmarkCursorGid,
                            mBookmarkBaselineInitialized, plan));
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
        source.progressKey = SubscriptionProgressStore.key(mSearchContext,
                source.plan == null ? "eh-subscription" : source.plan.getCacheKey());
        SubscriptionProgressStore.Progress progress = mProgress.get(source.progressKey,
                source.cursorGid, source.baselineInitialized);
        source.cursorGid = progress.cursor();
        source.baselineInitialized = progress.initialized();
        source.queued = true;
        mSources.add(source);
        mQueue.addLast(source);
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
                url = source.builder.build();
            } else if (!TextUtils.isEmpty(source.nextHref)) {
                url = source.nextHref;
            } else {
                source.builder.setPageIndex(source.pageIndex);
                url = source.builder.build();
            }

            if (TextUtils.isEmpty(url)) {
                completeSource(source, false);
                continue;
            }

            EhRequest request = new EhRequest()
                    .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
                    .setArgs(url, source.builder.getMode(), true,
                            source.plan != null && source.plan.isMergedUploaderSearch())
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

        if (source.pageIndex == 0) {
            source.initialResultCount = result.rawResultCount;
        }
        source.galleryInfoList.addAll(result.galleryInfoList);
        source.maxObservedGid = Math.max(source.maxObservedGid, result.rawHeadGid);
        for (GalleryInfo gallery : result.galleryInfoList) {
            source.maxObservedGid = Math.max(source.maxObservedGid, gallery.gid);
            if (source.baselineInitialized
                    && gallery.gid > source.cursorGid) {
                source.discoveredGids.add(gallery.gid);
            }
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
                || (result.rawTailGid > 0L && result.rawTailGid <= source.cursorGid);
        boolean shouldContinue = source.baselineInitialized
                && hasMore && !reachedCursor;

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
            mProgress.complete(source.progressKey, Math.max(source.cursorGid, source.maxObservedGid));
            Settings.putString(KEY_SOURCE_PROGRESS, mProgress.serialize());
        }
        mCompletedSources++;
        if (source.group == Group.EH) {
            if (success) {
                mEhMaxObservedGid = Math.max(mEhMaxObservedGid,
                        source.maxObservedGid);
            } else {
                mEhFailureCount++;
            }
        } else {
            if (success) {
                mBookmarkMaxObservedGid = Math.max(mBookmarkMaxObservedGid,
                        source.maxObservedGid);
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
            commitGroup(Group.EH, mEhCursorGid, mEhMaxObservedGid);
        }
        if (mBookmarkEnabled && bookmarkSuccess) {
            commitGroup(Group.BOOKMARK, mBookmarkCursorGid,
                    mBookmarkMaxObservedGid);
        }

        if (!mManual && sameContext) {
            cacheAutomaticCheckResult();
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

    private void commitGroup(@NonNull Group group, long cursorGid,
                             long maxObservedGid) {
        Set<Long> discovered = new HashSet<>();
        for (Source source : mSources) {
            if (source.group == group) {
                discovered.addAll(source.discoveredGids);
            }
        }

        boolean baselineInitialized = group == Group.EH
                ? mEhBaselineInitialized : mBookmarkBaselineInitialized;
        if (!baselineInitialized) {
            if (maxObservedGid > 0L) {
                if (group == Group.EH) {
                    putMaxLong(KEY_LAST_CHECKED_EH_GID, maxObservedGid);
                } else {
                    putMaxLong(KEY_LAST_CHECKED_BOOKMARK_GID, maxObservedGid);
                }
            }
            Settings.putBoolean(group == Group.EH
                    ? KEY_EH_BASELINE_INITIALIZED
                    : KEY_BOOKMARK_BASELINE_INITIALIZED, true);
            return;
        }

        if (group == Group.EH) {
            if (mEhUnreadGids.addAll(discovered)) {
                persistGids(KEY_UNREAD_EH_GIDS, mEhUnreadGids);
            }
            putMaxLong(KEY_LAST_CHECKED_EH_GID, maxObservedGid);
        } else {
            if (mBookmarkUnreadGids.addAll(discovered)) {
                persistGids(KEY_UNREAD_BOOKMARK_GIDS, mBookmarkUnreadGids);
            }
            putMaxLong(KEY_LAST_CHECKED_BOOKMARK_GID, maxObservedGid);
        }
    }

    private void cacheAutomaticCheckResult() {
        ArrayList<AutomaticCheckSource> sources = new ArrayList<>();
        for (Source source : mSources) {
            if (source.successful) {
                sources.add(new AutomaticCheckSource(source));
            }
        }
        if (sources.isEmpty()) {
            mRecentAutomaticCheckResult = null;
            mRecentAutomaticCheckCompletedRealtime = 0L;
        } else {
            mRecentAutomaticCheckResult = new AutomaticCheckResult(sources, mSearchContext);
            mRecentAutomaticCheckCompletedRealtime = SystemClock.elapsedRealtime();
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

    private static void putMaxLong(@NonNull String key, long value) {
        if (value > 0L) {
            Settings.putLong(key, Math.max(Settings.getLong(key, 0L), value));
        }
    }

    private void notifyStateChanged() {
        Listener listener = mListener;
        if (listener != null) {
            listener.onSubscriptionUpdateStateChanged();
        }
    }

    private static long findMaxGid(@Nullable List<GalleryInfo> galleries) {
        long maxGid = 0L;
        if (galleries != null) {
            for (GalleryInfo gallery : galleries) {
                maxGid = Math.max(maxGid, gallery.gid);
            }
        }
        return maxGid;
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
                ? lastCheckTime + CHECK_INTERVAL_MS : 0L;
        return Math.max(intervalCheckTime, cancelRetryNotBeforeTime);
    }

    static boolean isAutomaticCheckResultFresh(long completedRealtime,
                                                long nowRealtime) {
        return completedRealtime >= 0L && nowRealtime >= completedRealtime
                && nowRealtime - completedRealtime <= AUTOMATIC_CHECK_REUSE_WINDOW_MS;
    }
}
