package com.hippo.ehviewer.ui.scene.gallery.list;

import android.os.Handler;
import android.os.Looper;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.GalleryRequestBudget;
import com.hippo.ehviewer.client.SubscriptionSearchContext;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Ordered page commits and bounded, parallel gdata batches share one cancellable operation. */
final class GalleryRatingSortController {
    private static final int BATCH_SIZE = 25;
    private static final int MAX_BACKLOG = 200;

    interface Host {
        List<GalleryInfo> galleries();
        int loadedPages();
        String oldestPosted();
        boolean hasNextPage();
        boolean requestNextPage();
        void cancelPageRequest();
        void loadingChanged(boolean loading);
        void progressChanged();
        void sorted(List<GalleryInfo> order, boolean incomplete);
    }

    private final Host host;
    private final Consumer<EhRequest> execute;
    private final Executor sorter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Float> ratings = new HashMap<>();
    private final Set<EhRequest> metadata = new HashSet<>();
    private final Set<String> enqueued = new HashSet<>();
    private final ArrayDeque<GalleryInfo> pending = new ArrayDeque<>();
    private int generation;
    private boolean active;
    private boolean pagePending;
    private boolean pagesFinished;
    private boolean sorting;
    private boolean incomplete;
    private int targetPages;
    private String cutoff;
    private String previousCursor;

    GalleryRatingSortController(Host host, Consumer<EhRequest> execute, Executor sorter) {
        this.host = host;
        this.execute = execute;
        this.sorter = sorter;
    }

    boolean isActive() { return active; }
    boolean isWaitingForPage() { return pagePending; }
    boolean isReadingRatings() { return pagesFinished; }
    void clearRatings() { ratings.clear(); }

    void start(int pages, String date) {
        cancel();
        active = true;
        incomplete = false;
        pagesFinished = false;
        sorting = false;
        targetPages = pages;
        cutoff = date;
        previousCursor = null;
        host.loadingChanged(true);
        advance();
    }

    private boolean needsMore(int pages, String oldest) {
        return cutoff == null ? pages < targetPages : !GalleryRatingSort.reached(oldest, cutoff);
    }

    /** Do not prefetch beyond the user's page/date boundary, or past a full metadata queue. */
    boolean shouldPrefetch(GalleryListParser.Result raw) {
        return active && pagePending && !pagesFinished && backlog() < MAX_BACKLOG
                && host.loadedPages() + raw.sourcePages < GalleryRatingSort.MAX_PAGES
                && (cutoff == null || GalleryRatingSort.validTime(raw.rawTailPosted))
                && needsMore(host.loadedPages() + raw.sourcePages, raw.rawTailPosted);
    }

    private int backlog() { return pending.size() + metadata.size() * BATCH_SIZE; }

    private void advance() {
        if (!active || sorting) return;
        enqueueMissing();
        host.progressChanged();
        if (!pagePending && !pagesFinished) {
            boolean more = needsMore(host.loadedPages(), host.oldestPosted());
            if (cutoff != null && !GalleryRatingSort.validTime(host.oldestPosted())) {
                incomplete = true;
                more = false;
            }
            if (host.loadedPages() >= GalleryRatingSort.MAX_PAGES
                    || host.galleries().size() >= GalleryRatingSort.MAX_GALLERIES) {
                incomplete |= more && host.hasNextPage();
                more = false;
            }
            if (!more || !host.hasNextPage()) pagesFinished = true;
            else if (backlog() < MAX_BACKLOG) {
                pagePending = true;
                if (!host.requestNextPage()) {
                    pagePending = false;
                    pagesFinished = true;
                    incomplete = true;
                }
            }
        }
        pumpMetadata();
    }

    void pageLoaded(GalleryListParser.Result result) {
        if (!active || !pagePending) return;
        pagePending = false;
        boolean repeatedCursor = result.pages < 0 && result.nextHref != null
                && result.nextHref.startsWith("http") && result.nextHref.equals(previousCursor);
        previousCursor = result.nextHref;
        if (result.customErrorString != null || result.partialFailure || repeatedCursor) {
            incomplete = true;
            pagesFinished = true;
        }
        postAdvance();
    }

    void pageFailed() {
        if (!active) return;
        pagePending = false;
        pagesFinished = true;
        incomplete = true;
        postAdvance();
    }

    private void postAdvance() {
        int epoch = generation;
        main.post(() -> { if (epoch == generation) advance(); });
    }

    private static String key(GalleryInfo gallery) { return gallery.gid + ":" + gallery.token; }

    private boolean reuseRating(GalleryInfo gallery) {
        Float value = gallery.hasApiRating() ? Float.valueOf(gallery.apiRating) : ratings.get(key(gallery));
        if (value == null) return false;
        gallery.rating = value;
        ratings.put(key(gallery), value);
        return true;
    }

    private void enqueueMissing() {
        for (GalleryInfo gallery : host.galleries()) {
            if (reuseRating(gallery) || !enqueued.add(key(gallery))) continue;
            if (gallery.gid > 0 && gallery.token != null && !gallery.token.isEmpty()) pending.add(gallery);
        }
    }

    private void pumpMetadata() {
        // Leave a task slot for the advancing HTML chain until the range is complete.
        int limit = GalleryRequestBudget.MAX_CONCURRENT - (pagePending ? 1 : 0);
        while (active && metadata.size() < limit && !pending.isEmpty()) {
            List<GalleryInfo> targets = new ArrayList<>(BATCH_SIZE);
            while (!pending.isEmpty() && targets.size() < BATCH_SIZE) {
                GalleryInfo gallery = pending.removeFirst();
                if (!reuseRating(gallery)) targets.add(gallery);
            }
            if (targets.isEmpty()) continue;
            if (targets.size() < BATCH_SIZE && !pagesFinished) {
                for (int i = targets.size() - 1; i >= 0; i--) pending.addFirst(targets.get(i));
                break;
            }
            fetchRatings(targets);
        }
        if (active && pagesFinished && !pagePending && pending.isEmpty() && metadata.isEmpty()) sort();
    }

    private void fetchRatings(List<GalleryInfo> targets) {
        List<GalleryInfo> copies = new ArrayList<>(targets.size());
        for (GalleryInfo target : targets) {
            GalleryInfo copy = new GalleryInfo();
            copy.gid = target.gid;
            copy.token = target.token;
            copies.add(copy);
        }
        int epoch = generation;
        EhRequest request = new EhRequest().setMethod(EhClient.METHOD_GET_DISPLAY_METADATA).setBulk(true)
                .setArgs(copies, SubscriptionSearchContext.key());
        metadata.add(request);
        request.setCallback(new EhClient.Callback<List<GalleryInfo>>() {
            @Override public void onSuccess(List<GalleryInfo> result) {
                metadata.remove(request);
                if (epoch != generation || !active) return;
                if (result == null || result.size() != targets.size()) { finishLoaded(true); return; }
                for (int i = 0; i < targets.size(); i++) {
                    GalleryInfo value = result.get(i);
                    GalleryInfo target = targets.get(i);
                    if (value.gid == target.gid && Objects.equals(value.token, target.token)
                            && value.firstGid != null && value.firstGid > 0 && Float.isFinite(value.rating)
                            && value.rating >= 0 && value.rating <= 5) {
                        target.apiRating = target.rating = value.rating;
                        if (target.pages <= 0) target.pages = value.pages;
                        if (target.simpleTags == null) target.simpleTags = value.simpleTags;
                        if (target.firstGid == null) target.firstGid = value.firstGid;
                        ratings.put(key(target), value.rating);
                    } else incomplete = true;
                }
                postAdvance();
            }
            @Override public void onFailure(Exception error) {
                metadata.remove(request);
                if (epoch == generation && active) finishLoaded(true);
            }
            @Override public void onCancel() { metadata.remove(request); }
        });
        execute.accept(request);
    }

    private void sort() {
        if (sorting) return;
        sorting = true;
        int epoch = generation;
        List<GalleryRatingSort.Entry> snapshot = GalleryRatingSort.snapshot(host.galleries());
        sorter.execute(() -> {
            List<GalleryInfo> order = GalleryRatingSort.sort(snapshot);
            main.post(() -> {
                if (epoch != generation || !active) return;
                active = false;
                host.loadingChanged(false);
                // Pausing subscription lookahead can commit a completed partial batch. A
                // score may also change while the worker sorts; never publish a stale order.
                if (!matchesSnapshot(snapshot, host.galleries())) {
                    active = true;
                    sorting = false;
                    host.loadingChanged(true);
                    advance();
                    return;
                }
                host.sorted(order, incomplete);
            });
        });
    }

    private static boolean matchesSnapshot(List<GalleryRatingSort.Entry> snapshot, List<GalleryInfo> current) {
        if (snapshot.size() != current.size()) return false;
        Map<String, Integer> values = new HashMap<>();
        for (GalleryRatingSort.Entry entry : snapshot) {
            String key = key(entry.gallery()) + ":" + Float.floatToIntBits(entry.rating());
            values.merge(key, 1, Integer::sum);
        }
        for (GalleryInfo gallery : current) {
            String key = key(gallery) + ":" + Float.floatToIntBits(gallery.rating);
            Integer count = values.get(key);
            if (count == null) return false;
            if (count == 1) values.remove(key);
            else values.put(key, count - 1);
        }
        return values.isEmpty();
    }

    private void finishLoaded(boolean partial) {
        boolean wasIncomplete = incomplete || partial;
        cancel();
        active = true;
        incomplete = wasIncomplete;
        pagesFinished = true;
        sorting = false;
        host.loadingChanged(true);
        sort();
    }

    void stopAndSort() { finishLoaded(false); }

    void cancel() {
        generation++;
        boolean cancelPage = pagePending;
        pagePending = false;
        active = false;
        main.removeCallbacksAndMessages(null);
        for (EhRequest request : new ArrayList<>(metadata)) request.cancel();
        metadata.clear();
        pending.clear();
        enqueued.clear();
        if (cancelPage) host.cancelPageRequest();
        host.loadingChanged(false);
    }
}
