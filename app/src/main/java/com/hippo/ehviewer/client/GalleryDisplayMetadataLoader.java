package com.hippo.ehviewer.client;

import android.os.Handler;
import android.os.Looper;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Adapter-owned, cancellable batches. Only main-thread callbacks mutate displayed entries. */
public final class GalleryDisplayMetadataLoader {
    public static final class Request {
        private final GalleryInfo target;
        private final String token;
        private final Runnable changed;
        private boolean cancelled;
        private Request(GalleryInfo target, Runnable changed) {
            this.target = target; this.token = target.token; this.changed = changed;
        }
        public void cancel() { cancelled = true; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<EhRequest> execute;
    private final Supplier<String> scope;
    private final List<Request> waiting = new ArrayList<>();
    private final Runnable flush = this::flush;
    private EhRequest active;
    private int generation;

    public GalleryDisplayMetadataLoader(Consumer<EhRequest> execute) {
        this(execute, SubscriptionSearchContext::key);
    }
    GalleryDisplayMetadataLoader(Consumer<EhRequest> execute, Supplier<String> scope) {
        this.execute = execute; this.scope = scope;
    }
    public static boolean needsMetadata(GalleryInfo info) {
        boolean details = Settings.isThumbnailInfoBarEffective() && Settings.getShowThumbnailInfoDetails();
        return info.gid > 0 && info.token != null && !info.token.isEmpty()
                && ((details && info.simpleTags == null) || ((details || Settings.getShowGalleryPages()) && info.pages <= 0));
    }
    public Request request(GalleryInfo target, Runnable changed) {
        Request request = new Request(target, changed);
        waiting.removeIf(item -> item.cancelled);
        waiting.add(request);
        main.removeCallbacks(flush);
        main.postDelayed(flush, 32);
        return request;
    }
    private void flush() {
        if (active != null) return;
        waiting.removeIf(item -> item.cancelled);
        if (waiting.isEmpty()) return;
        List<Request> batch = new ArrayList<>(waiting.subList(0, Math.min(25, waiting.size())));
        waiting.subList(0, batch.size()).clear();
        List<GalleryInfo> copies = new ArrayList<>();
        for (Request item : batch) {
            GalleryInfo copy = new GalleryInfo();
            copy.gid = item.target.gid; copy.token = item.token;
            copies.add(copy);
        }
        int epoch = generation;
        String context;
        try { context = scope.get(); }
        catch (RuntimeException error) {
            android.util.Log.w("DisplayMetadata", "Metadata context unavailable", error);
            main.post(flush);
            return;
        }
        active = new EhRequest().setMethod(EhClient.METHOD_GET_DISPLAY_METADATA).setArgs(copies, context)
                .setCallback(new EhClient.Callback<List<GalleryInfo>>() {
                    @Override public void onSuccess(List<GalleryInfo> result) {
                        if (epoch != generation) return;
                        if (Objects.equals(context, scope.get())) {
                            for (int i = 0; i < batch.size(); i++) {
                                Request item = batch.get(i);
                                GalleryInfo value = result.get(i);
                                if (item.cancelled || !Objects.equals(item.token, item.target.token)
                                        || value.firstGid == null || value.firstGid < 0) continue;
                                // Fill holes only; never overwrite a newer detail response or local UI state.
                                if (item.target.pages <= 0) item.target.pages = value.pages;
                                if (item.target.simpleTags == null) item.target.simpleTags = value.simpleTags;
                                if (item.target.firstGid == null) item.target.firstGid = value.firstGid;
                                item.changed.run();
                            }
                        }
                        finish();
                    }
                    private void finish() {
                        if (epoch != generation) return;
                        active = null;
                        main.post(flush);
                    }
                    @Override public void onFailure(Exception error) { finish(); }
                    @Override public void onCancel() { finish(); }
                });
        try { execute.accept(active); }
        catch (RuntimeException error) {
            android.util.Log.w("DisplayMetadata", "Cannot schedule optional metadata", error);
            active = null;
            main.post(flush);
        }
    }
    public void clear() {
        generation++;
        main.removeCallbacks(flush);
        waiting.clear();
        EhRequest previous = active;
        active = null;
        if (previous != null) previous.cancel();
    }
}
