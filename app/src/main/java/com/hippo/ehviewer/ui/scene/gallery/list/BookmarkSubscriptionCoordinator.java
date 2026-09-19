/*
 * Copyright 2026 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.gallery.list;

import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.BookmarkSubscriptionPlanner;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.SubscriptionUpdateManager;
import com.hippo.ehviewer.client.SubscriptionSearchContext;
import com.hippo.ehviewer.client.SubscriptionGallerySnapshot;
import com.hippo.ehviewer.client.SubscriptionRetry;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lazily merges several chronologically ordered bookmark searches. Each source keeps its own
 * pagination cursor. A source is advanced only when its unseen upper bound could affect the next
 * item in the merged stream.
 */
final class BookmarkSubscriptionCoordinator {

    private static final int DEFAULT_BATCH_SIZE = 25;
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    interface Listener {
        void onBookmarkSubscriptionBatch(int taskId, List<GalleryInfo> data,
                                         boolean hasMore, boolean refresh,
                                         boolean noSubscriptions, boolean partialFailure);

        void onBookmarkSubscriptionFailure(int taskId, Exception error);
    }

    private static final class Source {
        final int id;
        @Nullable final BookmarkSubscriptionPlanner.Source plan;
        final ListUrlBuilder builder = new ListUrlBuilder();
        final ArrayList<GalleryInfo> buffer = new ArrayList<>();

        int bufferIndex;
        int pageIndex;
        @Nullable String nextHref;
        @Nullable String boundaryPosted;
        long boundaryGid;
        boolean hasBoundary;
        boolean exhausted;
        boolean queued;
        boolean loading;
        @Nullable EhRequest request;
        int retries;
        @Nullable String retryUrl;

        Source(int id, BookmarkSubscriptionPlanner.Source plan) {
            this.id = id;
            this.plan = plan;
            builder.set(plan.createBuilder());
            builder.setPageIndex(0);
        }

        Source(int id) {
            this.id = id;
            plan = null;
            builder.setMode(ListUrlBuilder.MODE_SUBSCRIPTION);
            builder.setPageIndex(0);
        }

        boolean hasVisibleItem() {
            return bufferIndex < buffer.size();
        }

        @Nullable GalleryInfo peek() {
            return hasVisibleItem() ? buffer.get(bufferIndex) : null;
        }

        @Nullable GalleryInfo take() {
            return hasVisibleItem() ? buffer.get(bufferIndex++) : null;
        }
    }

    private record PendingRequest(Source source, boolean initial) {
    }

    private final EhClient mClient;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Listener mListener;
    private final ArrayList<Source> mSources = new ArrayList<>();
    private final ArrayDeque<PendingRequest> mRequestQueue = new ArrayDeque<>();
    private final Set<Long> mEmittedGids = new HashSet<>();
    private final Map<Long, LinkedHashSet<QuickSearch>> mMatchingQuickSearches =
            new HashMap<>();
    private final ArrayList<GalleryInfo> mPendingBatch = new ArrayList<>();
    private final Map<Long, LinkedHashSet<QuickSearch>> mUnresolvedQuickSearches = new HashMap<>();
    @Nullable private Resolution mResolution;

    private static final class Resolution {
        final long gid;
        final ArrayDeque<QuickSearch> candidates;
        final LinkedHashSet<QuickSearch> matches = new LinkedHashSet<>();
        final EhClient.Callback<ListUrlBuilder> callback;
        @Nullable EhRequest request;
        Resolution(long gid, Set<QuickSearch> candidates, EhClient.Callback<ListUrlBuilder> callback) {
            this.gid = gid;
            this.candidates = new ArrayDeque<>(candidates);
            this.callback = callback;
        }
    }

    private int mGeneration;
    private int mTaskId;
    private int mActiveRequests;
    private int mInitialRequestsRemaining;
    private int mBatchSize = DEFAULT_BATCH_SIZE;
    private long mLatestEhGid;
    private long mLatestBookmarkGid;
    private boolean mRefresh;
    private boolean mLoading;
    @Nullable private Exception mFirstFailure;
    private String mSubscriptionFingerprint = "";

    BookmarkSubscriptionCoordinator(EhClient client, Listener listener) {
        mClient = client;
        mListener = listener;
    }

    static boolean isSupported(QuickSearch quickSearch) {
        return BookmarkSubscriptionPlanner.isSupported(quickSearch);
    }

    static String getFingerprint(List<QuickSearch> quickSearches,
                                 boolean includeEhSubscription) {
        StringBuilder builder = new StringBuilder();
        if (includeEhSubscription) {
            builder.append("eh-subscription;");
        }
        List<QuickSearch> sorted = new ArrayList<>(quickSearches);
        sorted.sort(java.util.Comparator.comparing(q -> String.valueOf(q.id)));
        builder.append(SubscriptionSearchContext.key()).append(';');
        for (QuickSearch quickSearch : sorted) {
            if (!quickSearch.subscribed || !isSupported(quickSearch)) {
                continue;
            }
            builder.append(quickSearch.id).append('|')
                    .append(quickSearch.mode).append('|')
                    .append(quickSearch.category).append('|')
                    .append(quickSearch.keyword).append('|')
                    .append(quickSearch.advanceSearch).append('|')
                    .append(quickSearch.minRating).append('|')
                    .append(quickSearch.pageFrom).append('|')
                    .append(quickSearch.pageTo).append(';');
        }
        return builder.toString();
    }

    boolean matchesSubscriptions(List<QuickSearch> quickSearches,
                                 boolean includeEhSubscription) {
        return mSubscriptionFingerprint.equals(
                getFingerprint(quickSearches, includeEhSubscription));
    }

    boolean isLoading() {
        return mLoading;
    }

    long getLatestEhGid() {
        return mLatestEhGid;
    }

    long getLatestBookmarkGid() {
        return mLatestBookmarkGid;
    }

    @Nullable
    ListUrlBuilder buildSearchForGallery(long gid) {
        LinkedHashSet<QuickSearch> matches = mMatchingQuickSearches.get(gid);
        if (matches == null || matches.isEmpty()) {
            return null;
        }

        List<BookmarkSubscriptionPlanner.Source> union = BookmarkSubscriptionPlanner.plan(new ArrayList<>(matches));
        if (union.size() == 1) return union.get(0).createBuilder();

        ListUrlBuilder builder = new ListUrlBuilder();
        if (matches.size() == 1) {
            builder.set(matches.iterator().next());
            return builder;
        }

        LinkedHashMap<String, String> uniqueTerms = new LinkedHashMap<>();
        int excludedCategories = 0;
        int advanceSearch = 0;
        int minRating = -1;
        int pageFrom = -1;
        int pageTo = -1;
        boolean hasAdvanceSearch = false;

        for (QuickSearch quickSearch : matches) {
            switch (quickSearch.mode) {
                case ListUrlBuilder.MODE_UPLOADER:
                    addUniqueTerm(uniqueTerms, toExactFieldSearch(
                            "uploader", quickSearch.keyword));
                    break;
                case ListUrlBuilder.MODE_TAG:
                    addUniqueTerm(uniqueTerms, toExactTagSearch(quickSearch.keyword));
                    break;
                default:
                    addKeywordTerms(uniqueTerms, quickSearch.keyword);
                    break;
            }

            if (quickSearch.category >= 0) {
                excludedCategories |= quickSearch.category;
            }
            if (quickSearch.advanceSearch != -1) {
                hasAdvanceSearch = true;
                advanceSearch |= quickSearch.advanceSearch;
            }
            if (quickSearch.minRating != -1) {
                minRating = Math.max(minRating, quickSearch.minRating);
            }
            if (quickSearch.pageFrom != -1) {
                pageFrom = Math.max(pageFrom, quickSearch.pageFrom);
            }
            if (quickSearch.pageTo != -1) {
                pageTo = pageTo == -1
                        ? quickSearch.pageTo : Math.min(pageTo, quickSearch.pageTo);
            }
        }

        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        builder.setCategory(excludedCategories);
        builder.setKeyword(TextUtils.join(" ", uniqueTerms.values()));
        if (hasAdvanceSearch) {
            builder.setAdvanceSearch(advanceSearch);
            builder.setMinRating(minRating);
            builder.setPageFrom(pageFrom);
            builder.setPageTo(pageTo);
        }
        return builder;
    }

    boolean hasSearchForGallery(long gid) {
        return mUnresolvedQuickSearches.containsKey(gid) || buildSearchForGallery(gid) != null;
    }

    void resolveSearchForGallery(long gid, EhClient.Callback<ListUrlBuilder> callback) {
        if (mResolution != null) return;
        Set<QuickSearch> candidates = mUnresolvedQuickSearches.get(gid);
        if (candidates == null || candidates.isEmpty()) {
            callback.onSuccess(buildSearchForGallery(gid));
            return;
        }
        mResolution = new Resolution(gid, candidates, callback);
        pumpResolution();
    }

    private void pumpResolution() {
        Resolution resolution = mResolution;
        if (resolution == null || resolution.request != null || mActiveRequests >= MAX_CONCURRENT_REQUESTS) return;
        QuickSearch candidate = resolution.candidates.pollFirst();
        if (candidate == null) {
            mMatchingQuickSearches.computeIfAbsent(resolution.gid, ignored -> new LinkedHashSet<>())
                    .addAll(resolution.matches);
            mUnresolvedQuickSearches.remove(resolution.gid);
            mResolution = null;
            resolution.callback.onSuccess(buildSearchForGallery(resolution.gid));
            return;
        }
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.set(candidate);
        int generation = mGeneration;
        resolution.request = new EhRequest().setMethod(EhClient.METHOD_VERIFY_BOOKMARK)
                .setArgs(builder.build(), builder.getMode(), resolution.gid)
                .setCallback(new EhClient.Callback<Boolean>() {
                    @Override public void onSuccess(Boolean matches) {
                        if (generation != mGeneration || mResolution != resolution) return;
                        resolution.request = null;
                        mActiveRequests--;
                        if (matches) resolution.matches.add(candidate);
                        pumpResolution();
                        pumpRequests();
                    }
                    @Override public void onFailure(Exception error) {
                        if (generation != mGeneration || mResolution != resolution) return;
                        mResolution = null;
                        mActiveRequests--;
                        resolution.callback.onFailure(error);
                        pumpRequests();
                    }
                    @Override public void onCancel() { }
                });
        mActiveRequests++;
        mClient.execute(resolution.request);
    }

    void refresh(int taskId, List<QuickSearch> quickSearches,
                 boolean includeEhSubscription,
                 @Nullable SubscriptionUpdateManager.AutomaticCheckResult automaticResult) {
        cancel();
        mTaskId = taskId;
        mRefresh = true;
        mLoading = true;
        mFirstFailure = null;
        mBatchSize = 0;
        mSubscriptionFingerprint = getFingerprint(
                quickSearches, includeEhSubscription);

        if (includeEhSubscription) {
            mSources.add(new Source(mSources.size()));
        }

        for (BookmarkSubscriptionPlanner.Source plan
                : BookmarkSubscriptionPlanner.plan(quickSearches)) {
            mSources.add(new Source(mSources.size(), plan));
        }

        if (mSources.isEmpty()) {
            mLoading = false;
            mListener.onBookmarkSubscriptionBatch(taskId, Collections.emptyList(),
                    false, true, true, false);
            return;
        }

        mInitialRequestsRemaining = 0;
        for (Source source : mSources) {
            SubscriptionUpdateManager.AutomaticCheckSource cachedSource =
                    findCachedSource(automaticResult, source);
            if (cachedSource != null) {
                applyCachedSource(source, cachedSource);
            } else {
                mInitialRequestsRemaining++;
                enqueue(source, true);
            }
        }
        pumpRequests();
        if (mInitialRequestsRemaining == 0) {
            if (mBatchSize == 0) {
                mBatchSize = DEFAULT_BATCH_SIZE;
            }
            continueProducing();
        }
    }

    void loadMore(int taskId) {
        if (mLoading) {
            return;
        }
        mTaskId = taskId;
        mRefresh = false;
        mLoading = true;
        mFirstFailure = null;
        continueProducing();
    }

    void cancel() {
        mGeneration++;
        if (mResolution != null && mResolution.request != null) mResolution.request.cancel();
        mResolution = null;
        mUnresolvedQuickSearches.clear();
        mLoading = false;
        mRefresh = false;
        mRequestQueue.clear();
        mActiveRequests = 0;
        mInitialRequestsRemaining = 0;
        for (Source source : mSources) {
            if (source.request != null) {
                EhRequest request = source.request;
                source.request = null;
                request.cancel();
            }
        }
        mSources.clear();
        mPendingBatch.clear();
        mEmittedGids.clear();
        mMatchingQuickSearches.clear();
        mLatestEhGid = 0L;
        mLatestBookmarkGid = 0L;
    }

    @Nullable
    private static SubscriptionUpdateManager.AutomaticCheckSource findCachedSource(
            @Nullable SubscriptionUpdateManager.AutomaticCheckResult automaticResult,
            Source source) {
        if (automaticResult == null || !automaticResult.matchesContext()) {
            return null;
        }
        for (SubscriptionUpdateManager.AutomaticCheckSource cachedSource
                : automaticResult.sources) {
            if (!cachedSource.isFresh()) continue;
            if (source.plan == null
                    ? cachedSource.isEhSubscription()
                    : cachedSource.matchesBookmarkSource(source.plan)) {
                return cachedSource;
            }
        }
        return null;
    }

    private void applyCachedSource(
            Source source,
            SubscriptionUpdateManager.AutomaticCheckSource cachedSource) {
        mBatchSize = Math.max(mBatchSize, cachedSource.initialResultCount);
        source.pageIndex = cachedSource.pageIndex;
        source.nextHref = cachedSource.nextHref;
        source.boundaryPosted = cachedSource.boundaryPosted;
        source.boundaryGid = cachedSource.boundaryGid;
        source.hasBoundary = cachedSource.hasBoundary;
        source.exhausted = cachedSource.exhausted;
        setSourceGalleryInfos(source, SubscriptionGallerySnapshot.copy(cachedSource.galleryInfoList));
    }

    private void enqueue(Source source, boolean initial) {
        if (source.exhausted || source.queued || source.loading) {
            return;
        }
        source.queued = true;
        mRequestQueue.addLast(new PendingRequest(source, initial));
    }

    private void pumpRequests() {
        pumpResolution();
        while (mActiveRequests < MAX_CONCURRENT_REQUESTS && !mRequestQueue.isEmpty()) {
            PendingRequest pending = mRequestQueue.removeFirst();
            Source source = pending.source();
            source.queued = false;
            source.loading = true;

            String url;
            if (source.retries > 0 && source.retryUrl != null) {
                url = source.retryUrl;
            } else if (pending.initial()) {
                source.pageIndex = 0;
                source.builder.setPageIndex(0);
                url = source.builder.build();
            } else if (!TextUtils.isEmpty(source.nextHref)) {
                url = source.nextHref;
            } else {
                source.pageIndex++;
                source.builder.setPageIndex(source.pageIndex);
                url = source.builder.build();
            }

            if (TextUtils.isEmpty(url)) {
                onPageFailure(mGeneration, source.id, pending.initial(),
                        new IllegalStateException("Bookmark subscription URL is empty"));
                continue;
            }

            source.retryUrl = url;
            int generation = mGeneration;
            EhRequest request = new EhRequest()
                    .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
                    .setArgs(url, source.builder.getMode(), true,
                            source.plan != null && source.plan.isMergedUploaderSearch())
                    .setCallback(new EhClient.Callback<GalleryListParser.Result>() {
                        @Override
                        public void onSuccess(GalleryListParser.Result result) {
                            onPageSuccess(generation, source.id, pending.initial(), result);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            onPageFailure(generation, source.id, pending.initial(), e);
                        }

                        @Override
                        public void onCancel() {
                            // Explicit cancellation increments the generation first.
                        }
                    });
            source.request = request;
            mActiveRequests++;
            mClient.execute(request);
        }
    }

    private void onPageSuccess(int generation, int sourceId, boolean initial,
                               GalleryListParser.Result result) {
        if (generation != mGeneration || sourceId < 0 || sourceId >= mSources.size()) {
            return;
        }
        Source source = mSources.get(sourceId);
        finishRequest(source, initial);
        source.retries = 0;
        source.retryUrl = null;

        if (initial) {
            mBatchSize = Math.max(mBatchSize, result.rawResultCount);
        }
        setSourceGalleryInfos(source, result.galleryInfoList);

        if (result.rawResultCount > 0) {
            source.boundaryPosted = result.rawTailPosted;
            source.boundaryGid = result.rawTailGid;
            source.hasBoundary = true;
        }

        source.nextHref = result.nextHref;
        boolean hasHref = !TextUtils.isEmpty(result.nextHref);
        boolean hasIndexedPage = result.pages > 0 && source.pageIndex + 1 < result.pages;
        source.exhausted = !hasHref && !hasIndexedPage;

        afterRequestFinished();
    }

    private void setSourceGalleryInfos(Source source, List<GalleryInfo> galleryInfoList) {
        source.buffer.clear();
        source.bufferIndex = 0;
        source.buffer.addAll(galleryInfoList);
        for (GalleryInfo galleryInfo : galleryInfoList) {
            if (source.plan == null) {
                mLatestEhGid = Math.max(mLatestEhGid, galleryInfo.gid);
            } else {
                mLatestBookmarkGid = Math.max(
                        mLatestBookmarkGid, galleryInfo.gid);
            }
            if (source.plan != null) {
                LinkedHashSet<QuickSearch> matches =
                        mMatchingQuickSearches.get(galleryInfo.gid);
                if (matches == null) {
                    matches = new LinkedHashSet<>();
                    mMatchingQuickSearches.put(galleryInfo.gid, matches);
                }
                matches.addAll(source.plan.matchingGalleryBookmarks(galleryInfo));
                if (source.plan.requiresVerification()) {
                    mUnresolvedQuickSearches.computeIfAbsent(galleryInfo.gid, ignored -> new LinkedHashSet<>())
                            .addAll(source.plan.getBookmarks());
                }
            }
        }
        Collections.sort(source.buffer,
                (first, second) -> -compareGalleryOrder(first, second));
    }

    private void onPageFailure(int generation, int sourceId, boolean initial, Exception error) {
        if (generation != mGeneration || sourceId < 0 || sourceId >= mSources.size()) {
            return;
        }
        Source source = mSources.get(sourceId);
        long delay = SubscriptionRetry.delayMillis(error, source.retries);
        if (delay >= 0) {
            finishRequest(source, false);
            source.retries++;
            source.queued = true;
            mHandler.postDelayed(() -> {
                if (generation != mGeneration) return;
                source.queued = false;
                enqueue(source, initial);
                pumpRequests();
            }, delay);
            pumpRequests();
            return;
        }
        finishRequest(source, initial);
        source.exhausted = true;
        if (mFirstFailure == null) {
            mFirstFailure = error;
        }
        afterRequestFinished();
    }

    private void finishRequest(Source source, boolean initial) {
        source.loading = false;
        source.request = null;
        mActiveRequests = Math.max(0, mActiveRequests - 1);
        if (initial) {
            mInitialRequestsRemaining = Math.max(0, mInitialRequestsRemaining - 1);
        }
    }

    private void afterRequestFinished() {
        pumpRequests();
        if (mInitialRequestsRemaining == 0) {
            if (mRefresh && mBatchSize == 0) {
                mBatchSize = DEFAULT_BATCH_SIZE;
            }
            continueProducing();
        }
    }

    private void continueProducing() {
        if (!mLoading || mInitialRequestsRemaining != 0) {
            return;
        }

        while (mPendingBatch.size() < mBatchSize) {
            Source visibleSource = findNewestVisibleSource();
            Source blockingSource = findBlockingUnknownSource(visibleSource);
            if (blockingSource != null) {
                if (!blockingSource.loading && !blockingSource.queued) {
                    enqueue(blockingSource, false);
                    pumpRequests();
                }
                return;
            }

            if (visibleSource == null) {
                finishBatch();
                return;
            }

            GalleryInfo galleryInfo = visibleSource.take();
            if (galleryInfo != null && mEmittedGids.add(galleryInfo.gid)) {
                mPendingBatch.add(galleryInfo);
            }
        }

        finishBatch();
    }

    @Nullable
    private Source findNewestVisibleSource() {
        Source best = null;
        for (Source source : mSources) {
            GalleryInfo candidate = source.peek();
            if (candidate != null && (best == null
                    || compareGalleryOrder(candidate, best.peek()) > 0)) {
                best = source;
            }
        }
        return best;
    }

    @Nullable
    private Source findBlockingUnknownSource(@Nullable Source visibleSource) {
        Source bestUnknown = null;
        for (Source source : mSources) {
            if (source.hasVisibleItem() || source.exhausted) {
                continue;
            }
            if (bestUnknown == null || compareUpperBounds(source, bestUnknown) > 0) {
                bestUnknown = source;
            }
        }
        if (bestUnknown == null || visibleSource == null || !bestUnknown.hasBoundary) {
            return bestUnknown;
        }

        GalleryInfo visible = visibleSource.peek();
        return visible != null && compareOrder(bestUnknown.boundaryPosted,
                bestUnknown.boundaryGid, visible.posted, visible.gid) > 0
                ? bestUnknown : null;
    }

    private static int compareUpperBounds(Source first, Source second) {
        if (!first.hasBoundary) {
            return second.hasBoundary ? 1 : 0;
        }
        if (!second.hasBoundary) {
            return -1;
        }
        return compareOrder(first.boundaryPosted, first.boundaryGid,
                second.boundaryPosted, second.boundaryGid);
    }

    private static int compareGalleryOrder(@Nullable GalleryInfo first,
                                           @Nullable GalleryInfo second) {
        if (first == null) {
            return second == null ? 0 : -1;
        }
        if (second == null) {
            return 1;
        }
        return compareOrder(first.posted, first.gid, second.posted, second.gid);
    }

    private static int compareOrder(@Nullable String firstPosted, long firstGid,
                                    @Nullable String secondPosted, long secondGid) {
        String first = firstPosted != null ? firstPosted : "";
        String second = secondPosted != null ? secondPosted : "";
        int dateComparison = first.compareTo(second);
        return dateComparison != 0 ? dateComparison : Long.compare(firstGid, secondGid);
    }

    private static void addKeywordTerms(Map<String, String> terms, @Nullable String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            return;
        }
        StringBuilder term = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < keyword.length(); i++) {
            char current = keyword.charAt(i);
            if (escaped) {
                term.append(current);
                escaped = false;
            } else if (current == '\\') {
                term.append(current);
                escaped = true;
            } else if ((current == '\"' || current == '\'')
                    && (quote == 0 || quote == current)) {
                quote = quote == 0 ? current : 0;
                term.append(current);
            } else if (Character.isWhitespace(current) && quote == 0) {
                addUniqueTerm(terms, term.toString());
                term.setLength(0);
            } else {
                term.append(current);
            }
        }
        addUniqueTerm(terms, term.toString());
    }

    private static void addUniqueTerm(Map<String, String> terms, @Nullable String term) {
        if (term == null) {
            return;
        }
        String normalized = term.trim();
        if (!normalized.isEmpty()) {
            String key = normalized.toLowerCase(Locale.ROOT);
            if (!terms.containsKey(key)) {
                terms.put(key, normalized);
            }
        }
    }

    private static String toExactTagSearch(@Nullable String tag) {
        if (TextUtils.isEmpty(tag)) {
            return "";
        }
        int separator = tag.indexOf(':');
        if (separator <= 0 || separator == tag.length() - 1) {
            return quoteExactValue(tag);
        }
        return tag.substring(0, separator + 1)
                + quoteExactValue(tag.substring(separator + 1));
    }

    private static String toExactFieldSearch(String field, @Nullable String value) {
        return TextUtils.isEmpty(value) ? "" : field + ':' + quoteExactValue(value);
    }

    private static String quoteExactValue(String value) {
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.charAt(0) == '\"'
                && normalized.charAt(normalized.length() - 1) == '\"') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!normalized.endsWith("$")) {
            normalized += '$';
        }
        return "\"" + normalized.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private boolean hasMore() {
        for (Source source : mSources) {
            if (source.hasVisibleItem() || !source.exhausted) {
                return true;
            }
        }
        return false;
    }

    private void finishBatch() {
        boolean hasMore = hasMore();
        if (mPendingBatch.isEmpty() && mFirstFailure != null && !hasMore) {
            Exception error = mFirstFailure;
            mLoading = false;
            mListener.onBookmarkSubscriptionFailure(mTaskId, error);
            return;
        }

        ArrayList<GalleryInfo> data = new ArrayList<>(mPendingBatch);
        boolean refresh = mRefresh;
        boolean partialFailure = mFirstFailure != null;
        mPendingBatch.clear();
        mLoading = false;
        mRefresh = false;
        mListener.onBookmarkSubscriptionBatch(mTaskId, data, hasMore,
                refresh, false, partialFailure);
    }
}
