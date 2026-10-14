package com.hippo.ehviewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
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
import com.hippo.ehviewer.widget.ReaderPreviewRecyclerView;
import com.hippo.lib.image.Image;
import com.hippo.widget.LoadImageView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/** A recycled, horizontally scrollable gallery. Browsing never commits a reader page. */
final class ReaderPagePreview implements AutoCloseable {
    private static final ReaderPreviewRequestBudget BUDGET = new ReaderPreviewRequestBudget(8, 5000);
    private static final LruCache<String, PreviewSet> METADATA = new LruCache<>(12);
    private final Context context;
    private final GalleryProvider2 provider;
    private final GalleryInfo gallery;
    private final RecyclerView row;
    private final LinearLayoutManager layout;
    private final RecyclerView.OnScrollListener scrollListener;
    private final View.OnLayoutChangeListener layoutListener;
    private final PreviewAdapter adapter = new PreviewAdapter();
    private final Set<Card> attached = ConcurrentHashMap.newKeySet();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ReaderPreviewLoader localLoader;
    private final IntConsumer onSelected;
    private final IntConsumer onBrowsed;
    private final LruCache<String, Long> failures = new LruCache<>(128);
    private final Set<String> downloading = new HashSet<>();
    private int edge;
    private volatile boolean closed;
    private volatile boolean visible;
    private boolean manualScrolling;
    private boolean dispatchPending;
    private long dispatchAt;
    private int selected;
    private int size;
    private int perPage;
    private int itemWidth;
    private int imageHeight;
    private int viewportWidth;
    private int browseDirection;
    private boolean sliderPositioned;
    private boolean reverse;
    private EhRequest metadataRequest;
    private final Runnable refresh = this::refreshVisible;
    private final Runnable dispatch = () -> {
        dispatchPending = false;
        if (visible && !closed) dispatchOnline();
    };

    ReaderPagePreview(Context context, RecyclerView row, GalleryProvider2 provider) {
        this(context, row, provider, page -> { }, page -> { });
    }

    ReaderPagePreview(Context context, RecyclerView row, GalleryProvider2 provider,
                      IntConsumer onSelected, IntConsumer onBrowsed) {
        this.context = context.getApplicationContext();
        this.row = row;
        this.provider = provider;
        this.onSelected = onSelected;
        this.onBrowsed = onBrowsed;
        gallery = provider instanceof EhGalleryProvider
                ? ((EhGalleryProvider) provider).getPreviewGalleryInfo() : null;
        layout = new LinearLayoutManager(row.getContext(), RecyclerView.HORIZONTAL, false);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setLayoutManager(layout);
        row.setItemAnimator(null);
        row.setHasFixedSize(true);
        row.setItemViewCacheSize(2);
        adapter.setHasStableIds(true);
        row.setAdapter(adapter);
        row.setContentDescription(context.getString(R.string.settings_reader_page_preview));
        updateDimensions();
        localLoader = new ReaderPreviewLoader(this.context, provider, (page, bitmap) -> {
            if (!visible || closed) return;
            for (Card card : attached) if (card.page == page) bindLocal(card, bitmap);
            if (page == selected) loadLocalNeighbors();
        });
        scrollListener = new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (!visible || closed) return;
                if (manualScrolling) browseCenter();
                refreshVisible();
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (!visible || closed) return;
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    manualScrolling = true;
                    sliderPositioned = false;
                }
                if (state == RecyclerView.SCROLL_STATE_IDLE && manualScrolling) {
                    browseCenter();
                    manualScrolling = false;
                    loadLocalNeighbors();
                }
            }
        };
        row.addOnScrollListener(scrollListener);
        layoutListener = (view, l, t, r, b, ol, ot, or, ob) -> {
            if (closed) return;
            if (updateDimensions()) {
                for (Card card : attached) card.resize();
                if (visible && !manualScrolling) positionSelected();
            }
            if (visible) scheduleRefresh();
        };
        row.addOnLayoutChangeListener(layoutListener);
        if (row instanceof ReaderPreviewRecyclerView) ((ReaderPreviewRecyclerView) row).setViewportWidthListener(width -> {
            if (closed || !updateDimensions(width)) return;
            for (Card card : attached) card.resize();
            if (visible && !manualScrolling) positionSelected(width);
        });
        if (gallery != null) {
            PreviewSet cached = METADATA.get(metadataKey(0));
            GalleryDetail detail = EhApplication.getGalleryDetailCache(context).get(gallery.gid);
            if (cached == null && detail != null && gallery.token.equals(detail.token)) cached = detail.previewSet;
            if (cached != null) remember(cached);
        }
    }

    boolean isVisible() { return visible && !closed; }

    void show(int page, int count, boolean rightToLeft) {
        if (closed || count <= 0) return;
        visible = true;
        manualScrolling = false;
        row.stopScroll();
        row.setVisibility(View.VISIBLE);
        update(page, count, rightToLeft);
    }

    void update(int page, int count, boolean rightToLeft) {
        if (!visible || closed || count <= 0) return;
        int target = Math.max(0, Math.min(page, count - 1));
        boolean changed = selected != target || size != count || reverse != rightToLeft;
        boolean newItems = size != count;
        if (!changed && layout.findViewByPosition(target) != null) {
            scheduleRefresh();
            return;
        }
        manualScrolling = false;
        row.stopScroll();
        browseDirection = Integer.compare(target, selected);
        selected = target;
        size = count;
        reverse = rightToLeft;
        layout.setReverseLayout(reverse);
        if (newItems) adapter.notifyDataSetChanged();
        if (changed || layout.findViewByPosition(target) == null) positionSelected();
        loadLocal();
        scheduleRefresh();
    }

    private void positionSelected() {
        int width = row.getWidth() > 0 ? row.getWidth() : row.getResources().getDisplayMetrics().widthPixels;
        positionSelected(width);
    }

    private void positionSelected(int width) {
        sliderPositioned = true;
        layout.scrollToPositionWithOffset(selected,
                Math.max(0, (width - row.getPaddingLeft() - row.getPaddingRight()) / 2));
    }

    private boolean updateDimensions() {
        int width = row.getWidth() > 0 ? row.getWidth() : row.getResources().getDisplayMetrics().widthPixels;
        return updateDimensions(width);
    }

    private boolean updateDimensions(int width) {
        int nextViewportWidth = Math.max(1, width - row.getPaddingLeft() - row.getPaddingRight());
        boolean viewportChanged = viewportWidth != nextViewportWidth;
        viewportWidth = nextViewportWidth;
        int nextWidth = Math.max(1, viewportWidth / 4);
        int nextHeight = Math.min(dp(180), Math.max(dp(96),
                Math.round(Math.max(1, nextWidth - dp(4)) * 130f / 91f)));
        if (itemWidth == nextWidth && imageHeight == nextHeight && !viewportChanged) return false;
        itemWidth = nextWidth; imageHeight = nextHeight;
        // FIT_CENTER can use either dimension; cover the actual image area in pixels.
        edge = Math.max(Math.max(1, itemWidth - dp(4)), imageHeight);
        if (localLoader != null) {
            if (visible) loadLocal();
        }
        if (row instanceof ReaderPreviewRecyclerView)
            ((ReaderPreviewRecyclerView) row).setPreviewHeight(imageHeight + dp(30));
        return true;
    }

    private void browseCenter() {
        int center = (row.getPaddingLeft() + row.getWidth() - row.getPaddingRight()) / 2;
        int distance = Integer.MAX_VALUE;
        int page = selected;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            int position = row.getChildAdapterPosition(child);
            int delta = Math.abs((layout.getDecoratedLeft(child) + layout.getDecoratedRight(child)) / 2 - center);
            if (position != RecyclerView.NO_POSITION && delta < distance) { page = position; distance = delta; }
        }
        if (selected != page) {
            browseDirection = Integer.compare(page, selected);
            selected = page;
            onBrowsed.accept(page);
        }
    }

    void hide() {
        visible = false;
        manualScrolling = false;
        row.stopScroll();
        handler.removeCallbacksAndMessages(null);
        dispatchPending = false;
        row.setVisibility(View.GONE);
        if (metadataRequest != null) { metadataRequest.cancel(); metadataRequest = null; }
        localLoader.stop();
        for (Card card : attached) card.clearImage();
        downloading.clear();
    }

    @Override public void close() {
        if (closed) return;
        hide();
        closed = true;
        localLoader.close();
        row.removeOnScrollListener(scrollListener);
        row.removeOnLayoutChangeListener(layoutListener);
        if (row instanceof ReaderPreviewRecyclerView) ((ReaderPreviewRecyclerView) row).setViewportWidthListener(null);
        row.setAdapter(null);
        attached.clear();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    private List<Card> visibleCards() {
        List<Card> result = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getRight() <= row.getPaddingLeft() || child.getLeft() >= row.getWidth() - row.getPaddingRight()) continue;
            Card card = (Card) row.getChildViewHolder(child);
            if (card.page >= 0) result.add(card);
        }
        result.sort(Comparator.comparingInt(card -> Math.abs(card.page - selected)));
        return result;
    }

    private void refreshVisible() {
        if (!visible || closed) return;
        for (Card card : visibleCards()) bindCached(card);
        loadLocalNeighbors();
        if (gallery != null) schedule(75);
    }

    private void bindCached(Card card) {
        Bitmap bitmap = localLoader.get(card.page);
        if (bitmap != null && card.image.preview == null && card.image.getDrawable() == null) bindLocal(card, bitmap);
        else if (bitmap != null && card.image.preview != null) bindLocal(card, bitmap);
        if (gallery != null) bindOnline(card);
    }

    private void bindLocal(Card card, Bitmap bitmap) {
        if (card.image.preview != null) downloading.remove(card.image.preview.getImageKey());
        card.image.load(new BitmapDrawable(row.getResources(), bitmap));
        card.image.preview = null; card.image.network = false; card.image.missed = false;
    }

    private void loadLocal() {
        if (!sliderPositioned) {
            loadLocalNeighbors();
            return;
        }
        // scrollToPositionWithOffset only takes effect on the next layout. Submit the
        // entire destination viewport now, so another slider step cannot drop its edge.
        // These are the cards that will be visible, not pages outside that viewport.
        int physicalTarget = reverse ? size - 1 - selected : selected;
        int offset = viewportWidth / 2;
        int targetLeft = reverse ? viewportWidth - offset - itemWidth : offset;
        long maxScroll = Math.max(0L, (long) size * itemWidth - viewportWidth);
        long scroll = Math.max(0L, Math.min(maxScroll, (long) physicalTarget * itemWidth - targetLeft));
        int first = (int) (scroll / itemWidth);
        int last = Math.min(size - 1, (int) ((scroll + viewportWidth - 1) / itemWidth));
        List<Integer> pages = new ArrayList<>();
        for (int position = first; position <= last; position++)
            pages.add(reverse ? size - 1 - position : position);
        requestLocal(pages);
    }

    private void loadLocalNeighbors() {
        if (!visible || closed) return;
        if (sliderPositioned) {
            loadLocal();
        } else {
            List<Integer> pages = new ArrayList<>();
            for (Card card : visibleCards()) pages.add(card.page);
            requestLocal(pages);
        }
        if (gallery != null) schedule(75);
    }

    private void requestLocal(List<Integer> pages) {
        pages.remove(Integer.valueOf(selected));
        int leading = selected;
        for (int page : pages) {
            if (browseDirection > 0) leading = Math.max(leading, page);
            else if (browseDirection < 0) leading = Math.min(leading, page);
        }
        final int incoming = leading;
        pages.sort(Comparator.comparingInt(page -> page == incoming ? -1 : Math.abs(page - selected)));
        int[] requested = new int[pages.size() + 1];
        requested[0] = selected;
        for (int i = 0; i < pages.size(); i++) requested[i + 1] = pages.get(i);
        localLoader.request(requested, edge);
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
        for (int i = 0; i < set.size(); i++) if (set.getPosition(i) == sourcePage)
            return set.getGalleryPreview(gallery.gid, i);
        return null;
    }

    private void bindOnline(Card card) {
        if (card.image.preview != null || card.image.getDrawable() != null) return;
        GalleryPreview preview = preview(card.page);
        if (preview == null) return;
        card.image.preview = preview;
        card.image.network = false; card.image.missed = false;
        preview.load(card.image, false);
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
        dispatchPending = true; dispatchAt = at;
        handler.postAtTime(dispatch, at);
    }

    private void dispatchOnline() {
        for (Card card : visibleCards()) {
            bindOnline(card);
            PreviewImage image = card.image;
            if (image.getDrawable() != null && !image.missed) continue;
            if (!localLoader.isUnavailable(card.page)) { schedule(75); continue; }
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
            image.network = true; image.missed = false;
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
                refreshVisible();
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

    private final class PreviewAdapter extends RecyclerView.Adapter<Card> {
        @Override public int getItemCount() { return size; }
        @Override public long getItemId(int position) { return position; }
        @NonNull @Override public Card onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Card(parent.getContext());
        }
        @Override public void onBindViewHolder(@NonNull Card card, int page) {
            card.clearImage();
            card.page = page;
            card.label.setText(Integer.toString(page + 1));
            card.itemView.setContentDescription(context.getString(R.string.reader_page_preview_page, page + 1));
            card.resize();
            if (visible) bindCached(card);
        }
        @Override public void onViewAttachedToWindow(@NonNull Card card) {
            attached.add(card);
            card.resize();
            if (visible) { bindCached(card); scheduleRefresh(); }
        }
        @Override public void onViewDetachedFromWindow(@NonNull Card card) {
            attached.remove(card);
            card.clearImage();
        }
        @Override public void onViewRecycled(@NonNull Card card) {
            attached.remove(card);
            card.clearImage(); card.page = -1;
        }
    }

    private final class Card extends RecyclerView.ViewHolder {
        final LinearLayout container;
        final PreviewImage image;
        final TextView label;
        int page = -1;
        Card(Context ui) {
            super(new LinearLayout(ui));
            container = (LinearLayout) itemView;
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp(2), dp(4), dp(2), dp(2));
            container.setClickable(true); container.setFocusable(true);
            image = new PreviewImage(ui, this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            container.addView(image, new LinearLayout.LayoutParams(-1, imageHeight));
            label = new TextView(ui);
            label.setGravity(Gravity.CENTER); label.setTextColor(0xFFFFFFFF); label.setTextSize(13);
            container.addView(label, new LinearLayout.LayoutParams(-1, dp(24)));
            container.setLayoutParams(new RecyclerView.LayoutParams(itemWidth, imageHeight + dp(30)));
            container.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (visible && !closed && position != RecyclerView.NO_POSITION && position < size)
                    onSelected.accept(position);
            });
        }
        void resize() {
            ViewGroup.LayoutParams params = container.getLayoutParams();
            if (params.width != itemWidth || params.height != imageHeight + dp(30)) {
                params.width = itemWidth; params.height = imageHeight + dp(30); container.setLayoutParams(params);
                ViewGroup.LayoutParams imageParams = image.getLayoutParams();
                imageParams.height = imageHeight; image.setLayoutParams(imageParams);
            }
        }
        void clearImage() {
            if (image.preview != null) downloading.remove(image.preview.getImageKey());
            image.unload(); image.preview = null; image.missed = false; image.network = false;
        }
    }

    private final class PreviewImage extends LoadImageView {
        final Card card;
        GalleryPreview preview;
        boolean network;
        boolean missed;
        PreviewImage(Context ui, Card card) { super(ui, false); this.card = card; }
        @Override protected boolean animateImageChanges() { return false; }
        @Override protected java.util.function.BooleanSupplier networkAdmission() {
            return () -> attached.contains(card) && admitNetwork();
        }
        @Override public void onFailure() {
            if (preview == null || !visible || closed || !attached.contains(card)) return;
            missed = true;
            if (network) {
                downloading.remove(preview.getImageKey());
                failures.put(preview.getImageKey(), SystemClock.uptimeMillis());
                network = false; schedule(5000);
            }
            schedule(75);
        }
        @Override public boolean onGetValue(@NonNull Image value, int origin) {
            boolean accepted = super.onGetValue(value, origin);
            if (accepted) {
                missed = false;
                if (preview != null && network) downloading.remove(preview.getImageKey());
                network = false; schedule(75);
            }
            return accepted;
        }
    }
}
