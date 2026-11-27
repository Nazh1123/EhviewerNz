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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.jsoup.Jsoup;

import okhttp3.Call;
import okhttp3.Response;

/** One cancellable metadata check. UI and service exchange ownership on the main thread. */
public final class GalleryUpdatePreviewCheck {
    public record Result(GalleryUpdateRecord record, String error) {}
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
                loaded = new Result(compare(), null);
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

    private GalleryUpdateRecord compare() throws Exception {
        GalleryUpdateRecordStore store = GalleryUpdateRecordStore.get(context);
        GalleryUpdateRecord cached = store.findPreview(target.gid, target.token, source.gid, source.token);
        if (cached != null && (source.pages <= 0 || source.pages == cached.oldPages)
                && (target.pages <= 0 || target.pages == cached.newPages)) return cached;
        SpiderInfo old = load(source);
        SpiderInfo updated = load(target);
        GalleryUpdateRecord record = GalleryUpdateRecord.compare(target.gid, source.gid, old, updated);
        if (!record.complete) throw new IOException(context.getString(R.string.gallery_update_history_incomplete));
        if (!cancelled) store.savePreview(record, old);
        return record;
    }

    private SpiderInfo load(GalleryInfo gallery) throws Exception {
        if (cancelled) throw new CancellationException();
        SpiderInfo info = GalleryUpdateManager.readDownloadedSpiderInfo(gallery.gid);
        if (info == null || !gallery.token.equals(info.token) || info.pTokenMap == null
                || gallery.pages > 0 && gallery.pages != info.pages) {
            info = new SpiderInfo();
            info.gid = gallery.gid;
            info.token = gallery.token;
            info.pages = gallery.pages;
            info.pTokenMap = new SparseArray<>();
        }
        if (GalleryUpdateRecord.hasAllTokens(info)) return info;
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
            if (GalleryUpdateRecord.hasAllTokens(info)) return info;
        }

        int next = 0;
        if (gallery instanceof GalleryDetail detail && detail.SpiderInfoPreviewSet != null
                && detail.SpiderInfoPages > 0 && detail.SpiderInfoPreviewPages > 0) {
            merge(info, detail.SpiderInfoPages, detail.SpiderInfoPreviewPages, 0, detail.SpiderInfoPreviewSet);
            if (GalleryUpdateRecord.hasAllTokens(info)) return info;
            if (covered(info, 0)) next = 1;
        }
        // Older local metadata can be missing an entire preview page. The site can also
        // change its page size, so only skip a request when all its positions are known.
        while (next == 0 || next < info.previewPages) {
            if (cancelled) throw new CancellationException();
            if (next == 0 || !covered(info, next)) fetch(info, next);
            next++;
        }
        return info;
    }

    private static boolean covered(SpiderInfo info, int index) {
        if (info.previewPerPage <= 0) return false;
        int start = index * info.previewPerPage;
        int end = Math.min(info.pages, start + info.previewPerPage);
        if (start >= end) return false;
        for (int i = start; i < end; i++) {
            String token = info.pTokenMap.get(i);
            if (token == null || token.isEmpty() || "failed".equals(token)) return false;
        }
        return true;
    }

    private void fetch(SpiderInfo info, int page) throws Exception {
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
                if (page > 0 && pages != info.pages) {
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
