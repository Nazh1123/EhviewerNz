package com.hippo.ehviewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryPreview;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.ehviewer.gallery.EhGalleryProvider;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.gallery.ReaderPreviewLoader;
import com.hippo.lib.image.Image;
import com.hippo.widget.LoadImageView;
import java.util.HashSet;
import java.util.Set;

/** A fixed five-card window: work stays bounded regardless of the gallery's page count. */
final class ReaderPagePreview implements AutoCloseable {
    private static final ReaderPreviewRequestBudget BUDGET = new ReaderPreviewRequestBudget(8, 5000);
    private static final LruCache<String, PreviewSet> METADATA = new LruCache<>(12);
    private static final int[] LOAD_ORDER = {2, 1, 3, 0, 4};
    private final Context context;
    private final GalleryProvider2 provider;
    private final GalleryInfo gallery;
    private final LinearLayout row;
    private final Card[] cards = new Card[5];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ReaderPreviewLoader localLoader;
    private final LruCache<String, Long> failures = new LruCache<>(128);
    private final Set<String> downloading = new HashSet<>();
    private final int edge;
    private volatile boolean closed;
    private volatile boolean visible;
    private boolean dispatchPending;
    private long dispatchAt;
    private int selected;
    private int size;
    private int perPage;
    private boolean reverse;
    private EhRequest metadataRequest;
    private int[] preparedPages;
    private final Runnable prepare = this::prepareLocal;
    private final Runnable dispatch = () -> {
        dispatchPending = false;
        if (visible && !closed) dispatchOnline();
    };

    ReaderPagePreview(Context context, LinearLayout row, GalleryProvider2 provider) {
        this.context = context.getApplicationContext();
        this.row = row;
        this.provider = provider;
        gallery = provider instanceof EhGalleryProvider
                ? ((EhGalleryProvider) provider).getPreviewGalleryInfo() : null;
        edge = Math.min(256, Math.max(128, dp(72)));
        localLoader = gallery == null ? new ReaderPreviewLoader(this.context, provider, (page, bitmap) -> {
            if (!visible || closed) return;
            for (Card card : cards) if (card.page == page)
                card.image.load(new android.graphics.drawable.BitmapDrawable(row.getResources(), bitmap));
        }) : null;
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 0; i < cards.length; i++) {
            cards[i] = new Card(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(124), 1);
            params.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(cards[i].container, params);
            cards[i].container.setBackground(background(i == 2));
            cards[i].container.setSelected(i == 2);
        }
        if (gallery != null) {
            PreviewSet cached = METADATA.get(metadataKey(0));
            GalleryDetail detail = EhApplication.getGalleryDetailCache(context).get(gallery.gid);
            if (cached == null && detail != null && gallery.token.equals(detail.token)) cached = detail.previewSet;
            if (cached != null) remember(cached);
        }
    }

    void show(int page, int count, boolean rightToLeft) {
        if (closed || count <= 0) return;
        handler.removeCallbacks(prepare);
        visible = true;
        row.setVisibility(View.VISIBLE);
        update(page, count, rightToLeft);
    }

    void update(int page, int count, boolean rightToLeft) {
        if (!visible || closed || count <= 0) return;
        int target = Math.max(0, Math.min(page, count - 1));
        selected = target; size = count; reverse = rightToLeft;
        reuseCards(target, count, rightToLeft);
        for (int i = 0; i < cards.length; i++) {
            Card card = cards[i];
            int index = windowPage(target, count, i, rightToLeft);
            if (card.page != index) {
                card.image.unload();
                card.image.preview = null;
                card.image.missed = false;
                card.image.network = false;
                card.page = index;
                card.label.setText(index < 0 ? "" : Integer.toString(index + 1));
            }
            card.container.setVisibility(index < 0 ? View.INVISIBLE : View.VISIBLE);
            if (gallery == null && index >= 0 && card.image.getDrawable() == null) {
                Bitmap bitmap = localLoader.get(index);
                if (bitmap != null) card.image.load(new android.graphics.drawable.BitmapDrawable(row.getResources(), bitmap));
            }
        }
        // Bind the online center first when a cache miss needs a disk request.
        if (gallery != null) for (int slot : LOAD_ORDER) if (cards[slot].page >= 0) bindOnline(cards[slot]);
        Set<String> currentKeys = new HashSet<>();
        for (Card card : cards) if (card.image.preview != null && card.image.network)
            currentKeys.add(card.image.preview.getImageKey());
        downloading.retainAll(currentKeys);
        if (gallery != null) schedule(75);
        else loadLocal();
    }

    static int windowPage(int target, int size, int slot, boolean reverse) {
        int index = target + (reverse ? 2 - slot : slot - 2);
        return index >= 0 && index < size ? index : -1;
    }

    void prepare(int page, int count, boolean rightToLeft) {
        if (closed || visible || count <= 0 || localLoader == null || provider.getPreviewConcurrency() <= 1) return;
        int target = Math.max(0, Math.min(page, count - 1));
        preparedPages = new int[cards.length];
        for (int i = 0; i < preparedPages.length; i++)
            preparedPages[i] = windowPage(target, count, LOAD_ORDER[i], rightToLeft);
        handler.removeCallbacks(prepare);
        // Warm only a settled five-page neighborhood, so fast page turns do not start more reads.
        handler.postDelayed(prepare, 150);
    }

    private void prepareLocal() {
        if (!visible && !closed && localLoader != null && preparedPages != null)
            localLoader.request(preparedPages, edge, false);
    }

    private void reuseCards(int target, int count, boolean rightToLeft) {
        Card[] ordered = new Card[cards.length];
        Set<Card> used = new HashSet<>();
        for (int slot = 0; slot < ordered.length; slot++) {
            int page = windowPage(target, count, slot, rightToLeft);
            if (page < 0) continue;
            for (Card card : cards) if (card.page == page) {
                ordered[slot] = card;
                used.add(card);
                break;
            }
        }
        for (int slot = 0; slot < ordered.length; slot++) if (ordered[slot] == null) {
            for (Card card : cards) if (used.add(card)) { ordered[slot] = card; break; }
        }
        boolean moved = false;
        for (int slot = 0; slot < ordered.length; slot++) if (ordered[slot] != cards[slot]) moved = true;
        if (!moved) return;
        for (int slot = 0; slot < ordered.length; slot++) {
            cards[slot] = ordered[slot];
            // Reorder without detaching views and cancelling their in-flight requests.
            row.bringChildToFront(cards[slot].container);
            cards[slot].container.setBackground(background(slot == 2));
            cards[slot].container.setSelected(slot == 2);
        }
    }

    void hide() {
        visible = false;
        handler.removeCallbacksAndMessages(null);
        dispatchPending = false;
        row.setVisibility(View.GONE);
        if (metadataRequest != null) { metadataRequest.cancel(); metadataRequest = null; }
        if (localLoader != null) localLoader.stop();
        for (Card card : cards) {
            card.image.unload();
            card.image.preview = null;
            card.image.missed = false;
            card.page = -1;
        }
        downloading.clear();
    }

    @Override public void close() {
        if (closed) return;
        hide();
        closed = true;
        if (localLoader != null) localLoader.close();
    }

    private void loadLocal() {
        int[] pages = new int[5];
        for (int i = 0; i < pages.length; i++) pages[i] = cards[LOAD_ORDER[i]].page;
        localLoader.request(pages, edge, true);
    }

    private String metadataKey(int page) { return gallery.gid + ":" + gallery.token + ":" + page; }

    private void remember(PreviewSet set) {
        if (set == null || set.size() == 0) return;
        if (set.getPosition(0) == 0) perPage = set.size();
        if (perPage > 0) METADATA.put(metadataKey(set.getPosition(0) / perPage), set);
    }

    private GalleryPreview preview(int index) {
        if (perPage <= 0) return null;
        int sourcePage = provider.getSourcePage(index);
        PreviewSet set = METADATA.get(metadataKey(sourcePage / perPage));
        if (set == null) return null;
        for (int i = 0; i < set.size(); i++) {
            if (set.getPosition(i) == sourcePage) return set.getGalleryPreview(gallery.gid, i);
        }
        return null;
    }

    private void bindOnline(Card card) {
        if (card.image.preview != null) return;
        GalleryPreview preview = preview(card.page);
        if (preview == null) return;
        card.image.preview = preview;
        card.image.network = false;
        card.image.missed = false;
        preview.load(card.image, false); // Always try the existing memory/disk caches first.
    }

    private boolean coolingDown(String key) {
        Long failed = failures.get(key);
        return failed != null && SystemClock.uptimeMillis() - failed < 5000;
    }

    private boolean admitNetwork() {
        return visible && !closed && BUDGET.acquire(SystemClock.uptimeMillis());
    }

    private void schedule(long delay) {
        if (closed || !visible) return;
        long at = SystemClock.uptimeMillis() + Math.max(25, delay);
        if (dispatchPending && dispatchAt <= at) return;
        handler.removeCallbacks(dispatch);
        dispatchPending = true;
        dispatchAt = at;
        handler.postAtTime(dispatch, at);
    }

    private void dispatchOnline() {
        for (int slot : LOAD_ORDER) {
            Card card = cards[slot];
            if (card.page < 0) continue;
            bindOnline(card);
            PreviewImage image = card.image;
            if (image.preview == null) {
                int page = perPage <= 0 ? 0 : provider.getSourcePage(card.page) / perPage;
                String key = metadataKey(page);
                if (metadataRequest != null) continue;
                if (coolingDown(key)) { schedule(5000); continue; }
                if (!BUDGET.acquire(SystemClock.uptimeMillis())) { schedule(BUDGET.retryAfter(SystemClock.uptimeMillis())); return; }
                fetchMetadata(page);
                continue;
            }
            if (!image.missed || image.network) continue;
            String key = image.preview.getImageKey();
            if (key == null) continue;
            if (coolingDown(key)) { schedule(5000); continue; }
            if (!downloading.contains(key)) {
                if (downloading.size() >= 2) continue;
                long wait = BUDGET.retryAfter(SystemClock.uptimeMillis());
                if (wait > 0) { schedule(wait); return; }
                downloading.add(key);
            }
            image.network = true;
            image.missed = false;
            image.preview.load(image, true);
        }
    }

    private void fetchMetadata(int page) {
        EhRequest request = new EhRequest().setMethod(EhClient.METHOD_GET_PREVIEW_SET)
                .setArgs(EhUrl.getGalleryDetailUrl(gallery.gid, gallery.token, page, false));
        metadataRequest = request;
        request.setCallback(new EhClient.Callback<Pair<PreviewSet, Integer>>() {
            @Override public void onSuccess(Pair<PreviewSet, Integer> result) {
                if (metadataRequest != request || closed || !visible) return;
                metadataRequest = null;
                remember(result.first);
                if (result.first.size() == 0) failures.put(metadataKey(page), SystemClock.uptimeMillis());
                update(selected, size, reverse);
            }
            @Override public void onFailure(Exception error) {
                if (metadataRequest != request) return;
                metadataRequest = null;
                failures.put(metadataKey(page), SystemClock.uptimeMillis());
                schedule(5000);
            }
            @Override public void onCancel() { }
        });
        EhApplication.getEhClient(context).execute(request);
    }

    private int dp(int value) { return Math.round(value * row.getResources().getDisplayMetrics().density); }

    private GradientDrawable background(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? 0xE0445555 : 0xC0202020);
        drawable.setCornerRadius(dp(6));
        if (selected) drawable.setStroke(dp(2), 0xFF80CBC4);
        return drawable;
    }

    private final class Card {
        final LinearLayout container;
        final PreviewImage image;
        final TextView label;
        int page = -1;
        Card(Context context) {
            container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp(3), dp(3), dp(3), dp(3));
            image = new PreviewImage(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            container.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            container.addView(label, new LinearLayout.LayoutParams(-1, dp(22)));
        }
    }

    private final class PreviewImage extends LoadImageView {
        GalleryPreview preview;
        boolean network;
        boolean missed;
        PreviewImage(Context context) { super(context); }
        @Override protected boolean animateImageChanges() { return false; }
        @Override protected java.util.function.BooleanSupplier networkAdmission() {
            return ReaderPagePreview.this::admitNetwork;
        }
        @Override public void onFailure() {
            if (preview == null || !visible || closed) return;
            missed = true;
            if (network) {
                downloading.remove(preview.getImageKey());
                failures.put(preview.getImageKey(), SystemClock.uptimeMillis());
                network = false;
                schedule(5000);
            }
            schedule(75);
        }
        @Override public boolean onGetValue(@NonNull Image value, int origin) {
            boolean accepted = super.onGetValue(value, origin);
            if (accepted) {
                missed = false;
                if (preview != null && network) downloading.remove(preview.getImageKey());
                network = false;
                schedule(75);
            }
            return accepted;
        }
    }
}
