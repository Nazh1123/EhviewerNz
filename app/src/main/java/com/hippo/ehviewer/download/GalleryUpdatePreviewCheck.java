package com.hippo.ehviewer.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhRequestBuilder;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.ehviewer.client.parser.GalleryDetailParser;
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.util.ExceptionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.jsoup.Jsoup;
import org.json.JSONArray;

import okhttp3.Call;
import okhttp3.Response;

/** One cancellable metadata check. UI and service exchange ownership on the main thread. */
public final class GalleryUpdatePreviewCheck {
    public record Result(GalleryUpdateRecord record, String error, String scope, SpiderInfo sourceInfo) {
        Result(GalleryUpdateRecord record, String error) { this(record, error, null, null); }
    }

    private record Source(GalleryInfo gallery, GalleryPageRange range, boolean editing) {
        int totalPages() { return range != null ? range.totalPages : gallery.pages; }
    }
    private static final Map<Long, GalleryUpdatePreviewCheck> confirmed = new HashMap<>();
    private final Context context;
    private final GalleryInfo source;
    private final GalleryDetail target;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile Call call;
    private Consumer<Result> listener;
    private Result result;

    public GalleryUpdatePreviewCheck(Context context, GalleryInfo source, GalleryDetail target) {
        this.context = context.getApplicationContext();
        this.source = source;
        this.target = target;
    }

    public void start(Consumer<Result> listener) {
        this.listener = listener;
        EhApplication.getExecutorService(context).execute(() -> {
            Result loaded;
            try {
                loaded = compare();
            } catch (Exception e) {
                if (cancelled) return;
                loaded = new Result(null, context.getString(R.string.gallery_update_log_incomplete)
                        + "\n" + ExceptionUtils.getReadableString(e));
            }
            Result finished = loaded;
            main.post(() -> deliver(finished));
        });
    }

    void deliver(Result finished) {
        if (cancelled) return;
        result = finished;
        Consumer<Result> callback = listener;
        listener = null;
        if (callback != null) callback.accept(finished);
    }

    public void handOff() {
        listener = null;
        GalleryUpdatePreviewCheck previous = confirmed.put(target.gid, this);
        if (previous != null && previous != this) previous.cancel();
    }

    public static GalleryUpdatePreviewCheck take(long gid) { return confirmed.remove(gid); }

    public void observe(Consumer<Result> listener) {
        if (result != null) listener.accept(result);
        else this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
        listener = null;
        confirmed.remove(target.gid, this);
        Call active = call;
        if (active != null) active.cancel();
    }

    static boolean matchesCurrentSources(Context context, GalleryInfo source,
                                         GalleryDetail target, Result checked) {
        long firstGid = family(target);
        return checked.scope == null || (checked.record.targetGid == target.gid
                && checked.record.sourceGid == source.gid && checked.record.targetToken.equals(target.token)
                && (target.pages <= 0 || checked.record.newPages == target.pages)
                && checked.scope.equals(scope(firstGid, localSources(context, source, target))));
    }

    private static long family(GalleryDetail target) { return target.firstGid != null ? target.firstGid : 0; }

    private static List<Source> localSources(Context context, GalleryInfo source, GalleryDetail target) {
        List<Source> sources = new ArrayList<>();
        long firstGid = family(target);
        if (firstGid > 0) {
            DownloadManager manager = EhApplication.getDownloadManager(context);
            List<Long> older = manager.getOlderGalleryVersionGids(firstGid, target.gid);
            for (int i = older.size() - 1; i >= 0; i--) {
                GalleryInfo gallery = manager.getDownloadInfo(older.get(i));
                if (gallery != null) sources.add(readScope(gallery));
            }
        } else {
            sources.add(readScope(source));
        }
        return sources;
    }

    private static Source readScope(GalleryInfo gallery) {
        GalleryDownloadRangeStore.Entry entry = GalleryDownloadRangeStore.peek(gallery.gid);
        return new Source(gallery, entry != null ? entry.range() : null,
                GalleryDownloadRangeStore.isEditing(gallery.gid) || (entry != null && entry.pending()));
    }

    private static String scope(long firstGid, List<Source> sources) {
        JSONArray signature = new JSONArray().put(firstGid);
        for (Source item : sources) {
            signature.put(new JSONArray().put(item.gallery.gid).put(item.gallery.token)
                    .put(item.totalPages()).put(item.range != null ? item.range.toString() : "")
                    .put(item.editing));
        }
        return signature.toString();
    }

    private Result compare() throws Exception {
        GalleryUpdateRecordStore store = GalleryUpdateRecordStore.get(context);
        long firstGid = family(target);
        List<Source> sources = localSources(context, source, target);
        if (sources.stream().noneMatch(item -> item.gallery.gid == source.gid)) {
            throw new IOException(context.getString(R.string.gallery_update_preview_source_changed));
        }
        String signature = scope(firstGid, sources);
        Set<String> covered = GalleryUpdateManager.findLocallyCoveredTokens(
                sources.stream().map(item -> item.gallery.gid).collect(java.util.stream.Collectors.toList()),
                () -> cancelled);
        GalleryUpdateRecord cached = store.findPreview(target.gid, target.token, source.gid, source.token, signature);
        if (cached != null && (target.pages <= 0 || target.pages == cached.newPages)) {
            SpiderInfo old = new SpiderInfo();
            old.gid = source.gid;
            old.token = source.token;
            old.pages = cached.oldPages;
            old.pTokenMap = new SparseArray<>();
            store.fillPreviewSource(target.gid, target.token, old);
            return new Result(cached.withLocalCoverage(covered), null, signature, old);
        }
        SpiderInfo updated = load(target, 0, null, false);
        if (!GalleryUpdateRecord.hasAllTokens(updated))
            throw new IOException(context.getString(R.string.gallery_update_history_incomplete));
        List<GalleryUpdateRecord.Source> inputs = new ArrayList<>();
        SpiderInfo old = null;
        for (Source item : sources) {
            if (cancelled) throw new CancellationException();
            boolean primary = item.gallery.gid == source.gid;
            SpiderInfo info = null;
            if (!item.editing && item.gallery.token != null) {
                try {
                    int expectedRangeTotal = item.range != null ? item.range.totalPages : 0;
                    info = load(item.gallery, expectedRangeTotal, item.range, true);
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    if (cancelled) throw new CancellationException();
                    android.util.Log.w("GalleryUpdatePreview", "Unable to check source " + item.gallery.gid, e);
                }
            }
            if (primary) old = info;
            inputs.add(new GalleryUpdateRecord.Source(item.gallery.gid, item.totalPages(), item.range, info));
        }
        GalleryUpdateRecord record = GalleryUpdateRecord.compareSources(target.gid, source.gid, inputs, updated, covered)
                .withFirstGid(firstGid);
        if (!cancelled && record.hasKnownAdditions()
                && record.sourceDeletions.stream().allMatch(GalleryUpdateRecord.SourceDeletion::complete)) {
            store.savePreview(record, old, signature);
        }
        return new Result(record, null, signature, old);
    }

    private SpiderInfo load(GalleryInfo gallery, int expectedRangeTotal, GalleryPageRange range,
                            boolean allowPartial) throws Exception {
        if (cancelled) throw new CancellationException();
        int total = expectedRangeTotal > 0 ? expectedRangeTotal : gallery.pages;
        SpiderInfo info = GalleryUpdateManager.readDownloadedSpiderInfo(gallery.gid);
        if (info == null || !gallery.token.equals(info.token) || info.pTokenMap == null
                || total > 0 && total != info.pages) {
            info = new SpiderInfo();
            info.gid = gallery.gid;
            info.token = gallery.token;
            info.pages = total;
            info.pTokenMap = new SparseArray<>();
        }
        if (GalleryUpdateRecord.hasTokens(info, range)) return info;
        SpiderInfo browsing = SpiderQueen.readSpiderInfoFromCache(context, gallery.gid);
        if (browsing != null && browsing.gid == gallery.gid && gallery.token.equals(browsing.token)
                && browsing.pTokenMap != null && (info.pages <= 0 || info.pages == browsing.pages)) {
            info.pages = browsing.pages;
            for (int i = 0; i < browsing.pTokenMap.size(); i++) {
                int page = browsing.pTokenMap.keyAt(i);
                String known = info.pTokenMap.get(page);
                if (known == null || known.isEmpty() || "failed".equals(known))
                    info.pTokenMap.put(page, browsing.pTokenMap.valueAt(i));
            }
            if (GalleryUpdateRecord.hasTokens(info, range)) return info;
        }

        int[] selected = range != null ? range.pages() : null;
        int next = 0;
        if (gallery instanceof GalleryDetail detail && detail.SpiderInfoPreviewSet != null
                && detail.SpiderInfoPages > 0 && detail.SpiderInfoPreviewPages > 0) {
            merge(info, detail.SpiderInfoPages, detail.SpiderInfoPreviewPages, 0, detail.SpiderInfoPreviewSet);
            if (GalleryUpdateRecord.hasTokens(info, range)) return info;
            if (covered(info, 0, selected)) next = 1;
        }
        // Older local metadata can be missing an entire preview page. The site can also
        // change its page size, so only skip a request when all its positions are known.
        while (next == 0 || next < info.previewPages) {
            if (cancelled) throw new CancellationException();
            if (next == 0 || !covered(info, next, selected)) {
                try {
                    fetch(info, next, expectedRangeTotal);
                } catch (Exception e) {
                    if (cancelled || e instanceof CancellationException) throw new CancellationException();
                    if (!allowPartial) throw e;
                    android.util.Log.w("GalleryUpdatePreview",
                            "Unable to check preview page " + next + " of source " + gallery.gid, e);
                    if (next == 0 && (info.previewPages <= 0 || info.previewPerPage <= 0)) break;
                }
                if (selected != null && GalleryUpdateRecord.hasTokens(info, range)) break;
            }
            next++;
        }
        return info;
    }

    private static boolean covered(SpiderInfo info, int index, int[] selected) {
        if (info.previewPerPage <= 0) return false;
        int start = index * info.previewPerPage;
        int end = Math.min(info.pages, start + info.previewPerPage);
        if (start >= end) return false;
        if (selected == null) {
            for (int i = start; i < end; i++) {
                if (!GalleryUpdateRecord.usableToken(info.pTokenMap.get(i))) return false;
            }
        } else {
            int offset = Arrays.binarySearch(selected, start);
            for (int i = offset >= 0 ? offset : -offset - 1; i < selected.length && selected[i] < end; i++) {
                if (!GalleryUpdateRecord.usableToken(info.pTokenMap.get(selected[i]))) return false;
            }
        }
        return true;
    }

    private void fetch(SpiderInfo info, int page, int expectedRangeTotal) throws Exception {
        String url = EhUrl.getGalleryDetailUrl(info.gid, info.token, page, false);
        Call request = EhApplication.getBrowsingOkHttpClient(context).newCall(
                new EhRequestBuilder(url, EhUrl.getReferer()).build());
        call = request;
        try {
            if (cancelled) throw new CancellationException();
            try (Response response = request.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                String html = response.body().string();
                int pages = GalleryDetailParser.parsePages(html);
                if ((page > 0 && pages != info.pages) || (expectedRangeTotal > 0 && pages != expectedRangeTotal)) {
                    throw new IOException(context.getString(R.string.gallery_update_log_incomplete));
                }
                var document = Jsoup.parse(html);
                merge(info, pages, info.previewPages, page, GalleryDetailParser.parsePreviewSet(document, html));
                if (page == 0 && !GalleryUpdateRecord.hasAllTokens(info)) {
                    info.previewPages = GalleryDetailParser.parsePreviewPages(document, html);
                }
            }
        } finally {
            call = null;
        }
    }

    static void merge(SpiderInfo info, int pages, int previewPages, int page, PreviewSet previews) {
        if (pages != info.pages) info.pTokenMap.clear();
        info.pages = pages;
        info.previewPages = previewPages;
        if (page == 0) info.previewPerPage = previews.size();
        else if (previews.size() > 0) info.previewPerPage = previews.getPosition(0) / page;
        for (int i = 0; i < previews.size(); i++) {
            GalleryPageUrlParser.Result item = GalleryPageUrlParser.parse(previews.getPageUrlAt(i));
            if (item != null && item.gid == info.gid && item.page < pages) info.pTokenMap.put(item.page, item.pToken);
        }
    }
}
