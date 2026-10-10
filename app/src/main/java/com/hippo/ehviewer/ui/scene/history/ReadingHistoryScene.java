package com.hippo.ehviewer.ui.scene.history;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.android.resource.AttrResources;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.gallery.ReadingHistory;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.view.ViewTransition;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.widget.LoadImageView;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Unlike HistoryScene, selecting a visit opens the reader directly. */
public class ReadingHistoryScene extends ToolbarScene {
    private final List<ReadingHistory.Entry> entries = new ArrayList<>();
    private RecyclerView recycler;
    private ReadingAdapter adapter;
    private ViewTransition transition;

    @Override public int getNavCheckedItem() { return R.id.nav_reading_history; }

    @Nullable
    @Override public View onCreateView3(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.scene_history, container, false);
        recycler = root.findViewById(R.id.recycler_view);
        TextView tip = root.findViewById(R.id.tip);
        tip.setText(R.string.reading_history_empty);
        transition = new ViewTransition(root.findViewById(R.id.content), tip);
        android.content.res.Resources resources = inflater.getContext().getResources();
        AutoStaggeredGridLayoutManager manager = new AutoStaggeredGridLayoutManager(
                0, StaggeredGridLayoutManager.VERTICAL);
        manager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        manager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);
        recycler.setLayoutManager(manager);
        recycler.setClipToPadding(false);
        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        recycler.addItemDecoration(decoration);
        decoration.applyPaddings(recycler);
        adapter = new ReadingAdapter(inflater);
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
        setTitle(R.string.reading_history);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        Context context = getEHContext();
        if (adapter == null || context == null || transition == null) return;
        entries.clear();
        entries.addAll(ReadingHistory.list(context));
        adapter.notifyDataSetChanged();
        transition.showView(entries.isEmpty() ? 1 : 0, false);
    }

    @Override public void onDestroyView() {
        if (recycler != null) recycler.setAdapter(null);
        recycler = null;
        adapter = null;
        transition = null;
        entries.clear();
        super.onDestroyView();
    }

    @Override public void onNavigationClick(View view) { onBackPressed(); }
    @Override public int getMenuResId() { return R.menu.scene_history; }

    @Override public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() != R.id.action_clear_all) return false;
        Context context = getEHContext();
        if (context == null) return false;
        new AlertDialog.Builder(context)
                .setMessage(R.string.reading_history_clear_all)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_all, (dialog, which) -> {
                    ReadingHistory.clear(context);
                    reload();
                }).show();
        return true;
    }

    private void open(ReadingHistory.Entry entry) {
        if (getActivity2() == null) return;
        try {
            startActivity(entry.createIntent(getActivity2()));
        } catch (RuntimeException e) {
            Toast.makeText(getEHContext(), R.string.reading_history_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private class ReadingAdapter extends RecyclerView.Adapter<ReadingHolder> {
        private final int coverHeight;

        ReadingAdapter(LayoutInflater inflater) {
            View calculator = inflater.inflate(R.layout.item_gallery_list_thumb_height, null);
            ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT);
            coverHeight = calculator.getMeasuredHeight();
        }

        @NonNull
        @Override public ReadingHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ReadingHolder holder = new ReadingHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reading_history, parent, false));
            ViewGroup.LayoutParams params = holder.cover.getLayoutParams();
            params.height = coverHeight;
            params.width = coverHeight * 2 / 3;
            holder.cover.setLayoutParams(params);
            return holder;
        }

        @Override public void onBindViewHolder(@NonNull ReadingHolder holder, int position) {
            ReadingHistory.Entry entry = entries.get(position);
            Context context = holder.itemView.getContext();
            ReadingHistoryCoverLoader.load(holder.cover, entry);
            holder.title.setText(entry.title);
            holder.metadata.setText(context.getString(R.string.reading_history_metadata,
                    context.getString(ReadingHistory.sourceLabel(entry.source)),
                    Integer.toString(Math.max(0,
                            (entry.readingPage >= 0 ? entry.readingPage : entry.page) + 1)),
                    entry.pages > 0 ? Integer.toString(entry.pages)
                            : entry.gallery != null && entry.gallery.pages > 0
                                    ? Integer.toString(entry.gallery.pages) : "—"));
            holder.time.setText(new SimpleDateFormat("yyyy-MM-dd hh:mm", Locale.ROOT)
                    .format(new Date(entry.readAt)));
            holder.itemView.setOnClickListener(view -> open(entry));
            holder.itemView.setOnLongClickListener(view -> {
                new AlertDialog.Builder(context).setTitle(entry.title)
                        .setItems(new String[]{context.getString(R.string.reading_history_remove)},
                                (dialog, which) -> {
                                    ReadingHistory.remove(context, entry);
                                    reload();
                                }).show();
                return true;
            });
        }

        @Override public int getItemCount() { return entries.size(); }

        @Override public void onViewRecycled(@NonNull ReadingHolder holder) {
            ReadingHistoryCoverLoader.clear(holder.cover);
            super.onViewRecycled(holder);
        }
    }

    private static class ReadingHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView metadata;
        final TextView time;
        final LoadImageView cover;
        ReadingHolder(View view) {
            super(view);
            title = view.findViewById(R.id.title);
            metadata = view.findViewById(R.id.reading_metadata);
            time = view.findViewById(R.id.reading_time);
            cover = view.findViewById(R.id.reading_cover);
        }
    }
}
