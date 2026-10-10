package com.hippo.ehviewer.ui.scene.history;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.GalleryUpdateRecord;
import com.hippo.ehviewer.download.GalleryUpdateManager;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore;
import com.hippo.ehviewer.download.GalleryUpdateRecordStore.Summary;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.ProgressScene;
import com.hippo.ehviewer.ui.scene.download.part.ThumbDataContainer;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.scene.Announcer;
import com.hippo.view.ViewTransition;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Local, global update history. Download metadata is resolved only for visible cards. */
public class GalleryUpdateHistoryScene extends ToolbarScene implements GalleryUpdateManager.UpdateStateListener {
    private final List<Summary> entries = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private RecyclerView recycler;
    private HistoryAdapter adapter;
    private ViewTransition transition;
    private TextView tip;
    private int generation;
    private int openGeneration;

    @Override public int getNavCheckedItem() { return R.id.nav_downloads; }

    @Nullable
    @Override public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.scene_history, container, false);
        recycler = root.findViewById(R.id.recycler_view);
        tip = root.findViewById(R.id.tip);
        tip.setText(R.string.gallery_update_log_loading);
        transition = new ViewTransition(root.findViewById(R.id.content), tip);
        transition.showView(1, false);
        android.content.res.Resources resources = inflater.getContext().getResources();
        AutoStaggeredGridLayoutManager manager = new AutoStaggeredGridLayoutManager(
                0, StaggeredGridLayoutManager.VERTICAL);
        manager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        manager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);
        recycler.setLayoutManager(manager);
        recycler.setClipToPadding(false);
        MarginItemDecoration decoration = new MarginItemDecoration(
                resources.getDimensionPixelOffset(R.dimen.gallery_list_interval),
                resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h),
                resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v),
                resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h),
                resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v));
        recycler.addItemDecoration(decoration);
        decoration.applyPaddings(recycler);
        adapter = new HistoryAdapter(inflater);
        adapter.setHasStableIds(true);
        recycler.setAdapter(adapter);
        FastScroller scroller = root.findViewById(R.id.fast_scroller);
        scroller.attachToRecyclerView(recycler);
        HandlerDrawable handle = new HandlerDrawable();
        handle.setColor(AttrResources.getAttrColor(inflater.getContext(), R.attr.widgetColorThemeAccent));
        scroller.setHandlerDrawable(handle);
        reload();
        return root;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.gallery_update_history_title);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override public void onResume() {
        super.onResume();
        GalleryUpdateManager.addUpdateStateListener(this);
        reload();
    }

    @Override public void onGalleryUpdateStateChanged(long targetGid, int state) {
        main.post(() -> {
            if (isResumed()) reload();
        });
    }

    protected Executor executor(Context context) { return EhApplication.getExecutorService(context); }

    protected List<Summary> loadSummaries(Context context) {
        return GalleryUpdateRecordStore.get(context).listSummaries();
    }

    @Nullable protected DownloadInfo findDownload(long gid) {
        return EhApplication.getDownloadManager(getEHContext()).getDownloadInfo(gid);
    }

    private void reload() {
        Context context = getEHContext();
        if (adapter == null || context == null) return;
        int request = ++generation;
        Context application = context.getApplicationContext();
        executor(application).execute(() -> {
            List<Summary> loaded = List.of();
            boolean failed = false;
            try {
                loaded = loadSummaries(application);
            } catch (RuntimeException e) {
                Log.w("GalleryUpdateHistory", "Unable to load update history", e);
                failed = true;
            }
            List<Summary> result = loaded;
            boolean error = failed;
            main.post(() -> {
                if (request != generation || adapter == null) return;
                entries.clear();
                entries.addAll(result);
                adapter.notifyDataSetChanged();
                tip.setText(error ? R.string.gallery_update_history_load_failed
                        : R.string.gallery_update_history_all_empty);
                transition.showView(entries.isEmpty() ? 1 : 0, false);
            });
        });
    }

    private void open(Summary entry) {
        // Supersede a previous legacy-token lookup even when this card can open immediately.
        int request = ++openGeneration;
        Context context = getEHContext();
        if (context == null || adapter == null) return;
        DownloadInfo local = findDownload(entry.targetGid());
        if (local != null && !TextUtils.isEmpty(local.token)) {
            startScene(new Announcer(GalleryDetailScene.class).setArgs(detailArgs(entry, local, local.token)));
            return;
        }
        if (!entry.targetToken().isEmpty()) {
            startScene(new Announcer(GalleryDetailScene.class).setArgs(detailArgs(entry, null, entry.targetToken())));
            return;
        }
        // Old records may lack a gallery token. Resolve on click, never while listing cards.
        Context application = context.getApplicationContext();
        executor(application).execute(() -> {
            String token = "";
            String pageToken = "";
            try {
                GalleryInfo cached = EhApplication.getGalleryDetailCache(application).get(entry.targetGid());
                if (cached == null || TextUtils.isEmpty(cached.token)) cached = EhDB.getHistoryInfo(entry.targetGid());
                if (cached == null || TextUtils.isEmpty(cached.token)) cached = EhDB.searchLocalFavorites(entry.targetGid());
                if (cached != null && !TextUtils.isEmpty(cached.token)) token = cached.token;
                if (token.isEmpty()) {
                    GalleryUpdateRecord record = GalleryUpdateRecordStore.get(application).find(entry.targetGid());
                    if (record != null && !record.getFirstPageToken().isEmpty()) {
                        pageToken = record.getFirstPageToken();
                    }
                }
            } catch (RuntimeException e) {
                Log.w("GalleryUpdateHistory", "Unable to resolve gallery token for " + entry.targetGid(), e);
            }
            String resolved = token;
            String firstPageToken = pageToken;
            main.post(() -> {
                if (request != openGeneration || adapter == null || !isResumed()) return;
                if (TextUtils.isEmpty(resolved) && !firstPageToken.isEmpty()) {
                    Bundle args = new Bundle();
                    args.putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN);
                    args.putLong(ProgressScene.KEY_GID, entry.targetGid());
                    args.putString(ProgressScene.KEY_PTOKEN, firstPageToken);
                    args.putInt(ProgressScene.KEY_PAGE, 0);
                    startScene(new Announcer(ProgressScene.class).setArgs(args));
                    return;
                }
                startScene(new Announcer(GalleryDetailScene.class).setArgs(detailArgs(entry, null, resolved)));
            });
        });
    }

    static Bundle detailArgs(Summary entry, @Nullable DownloadInfo local, String token) {
        Bundle args = new Bundle();
        if (local != null) {
            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_DOWNLOAD_GALLERY_INFO);
            args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, local);
        } else {
            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN);
            args.putLong(GalleryDetailScene.KEY_GID, entry.targetGid());
            args.putString(GalleryDetailScene.KEY_TOKEN, token);
        }
        return args;
    }

    @Override public void onNavigationClick(View view) { onBackPressed(); }

    @Override public void onPause() {
        GalleryUpdateManager.removeUpdateStateListener(this);
        openGeneration++;
        super.onPause();
    }

    @Override public void onDestroyView() {
        GalleryUpdateManager.removeUpdateStateListener(this);
        generation++;
        openGeneration++;
        if (recycler != null) {
            recycler.stopScroll();
            recycler.setAdapter(null);
        }
        recycler = null;
        adapter = null;
        transition = null;
        tip = null;
        entries.clear();
        super.onDestroyView();
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryHolder> {
        private final int coverHeight;
        private final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        HistoryAdapter(LayoutInflater inflater) {
            View calculator = inflater.inflate(R.layout.item_gallery_list_thumb_height, null);
            ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT);
            coverHeight = calculator.getMeasuredHeight();
        }

        @Override public long getItemId(int position) { return entries.get(position).targetGid(); }

        @NonNull
        @Override public HistoryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            HistoryHolder holder = new HistoryHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_gallery_update_history, parent, false));
            ViewGroup.LayoutParams params = holder.cover.getLayoutParams();
            params.height = coverHeight;
            params.width = coverHeight * 2 / 3;
            holder.cover.setLayoutParams(params);
            return holder;
        }

        @Override public void onBindViewHolder(@NonNull HistoryHolder holder, int position) {
            Summary entry = entries.get(position);
            DownloadInfo local = findDownload(entry.targetGid());
            Context context = holder.itemView.getContext();
            holder.cover.unload();
            holder.cover.setImageDrawable(null);
            String title = local != null ? EhUtils.getSuitableTitle(local) : null;
            holder.title.setText(TextUtils.isEmpty(title) ? Long.toString(entry.targetGid()) : title);
            if (local != null && !TextUtils.isEmpty(local.thumb)) {
                holder.cover.load(EhCacheKeyFactory.getThumbKey(local.gid), local.thumb,
                        new ThumbDataContainer(local), true, false);
            }
            holder.category.setVisibility(local != null ? View.VISIBLE : View.GONE);
            holder.category.setText(local != null ? EhUtils.getCategory(local.category) : "");
            holder.category.setBackground(local != null ? new ColorDrawable(EhUtils.getCategoryColor(local.category)) : null);
            holder.time.setText(time.format(new Date(entry.completedAt())));
            boolean known = entry.complete() && !entry.failure();
            SpannableStringBuilder counts = new SpannableStringBuilder();
            appendCount(counts, "+" + (known ? entry.addedPages() : "—"), context.getColor(R.color.deep_green_600));
            counts.append("  ");
            appendCount(counts, "-" + (known ? entry.deletedPages() : "—"), context.getColor(R.color.red_500));
            holder.counts.setText(counts);
            holder.state.setText(entry.failure() ? R.string.gallery_update_history_failed
                    : entry.complete() ? R.string.gallery_update_history_updated
                    : R.string.gallery_update_history_updated_incomplete);
            holder.state.setTextColor(entry.failure() ? context.getColor(R.color.red_500)
                    : AttrResources.getAttrColor(context, R.attr.textColorThemeAccent));
            holder.progress.setText(known ? context.getString(R.string.gallery_update_history_read_progress,
                    entry.addedPages() > 0 ? entry.readingPage() + 1 : 0, entry.addedPages()) : "—");
            holder.itemView.setOnClickListener(view -> open(entry));
        }

        @Override public int getItemCount() { return entries.size(); }

        @Override public void onViewRecycled(@NonNull HistoryHolder holder) {
            holder.cover.unload();
            holder.cover.setImageDrawable(null);
            holder.itemView.setOnClickListener(null);
            super.onViewRecycled(holder);
        }
    }

    private static void appendCount(SpannableStringBuilder text, String count, int color) {
        int start = text.length();
        text.append(count);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static class HistoryHolder extends RecyclerView.ViewHolder {
        final LoadImageView cover;
        final TextView title, time, counts, category, state, progress;
        HistoryHolder(View view) {
            super(view);
            cover = view.findViewById(R.id.thumb);
            title = view.findViewById(R.id.title);
            time = view.findViewById(R.id.update_time);
            counts = view.findViewById(R.id.update_counts);
            category = view.findViewById(R.id.category);
            state = view.findViewById(R.id.state);
            progress = view.findViewById(R.id.read_progress);
        }
    }
}
