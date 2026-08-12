/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.ui.scene;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryUpdateTask;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.util.ClipboardUtil;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Displays the same shared parent-chain result used by gallery-update detection. */
public final class GalleryParentChainDialog {

    private final SceneFragment host;
    private final Context context;
    private final GalleryDetail currentGallery;
    private final DownloadManager downloadManager;

    @Nullable
    private AlertDialog dialog;
    @Nullable
    private View progressView;
    @Nullable
    private TextView statusView;
    @Nullable
    private ListView listView;
    @Nullable
    private ParentAdapter adapter;
    @Nullable
    private GalleryUpdateTask task;
    @Nullable
    private ArrayList<GalleryUpdateTask.ParentGallery> cachedItems;
    private boolean destroyed;

    public GalleryParentChainDialog(@NonNull SceneFragment host, @NonNull Context context,
                                    @NonNull GalleryDetail currentGallery) {
        this.host = host;
        this.context = context;
        this.currentGallery = currentGallery;
        downloadManager = EhApplication.getDownloadManager(context);
        List<GalleryUpdateTask.ParentGallery> cached =
                GalleryUpdateTask.getCachedParents(currentGallery);
        if (cached != null) {
            cachedItems = new ArrayList<>(cached);
        }
    }

    public void show() {
        if (destroyed || TextUtils.isEmpty(currentGallery.parent)) {
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            return;
        }

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_archive_list, null);
        progressView = content.findViewById(R.id.progress);
        statusView = content.findViewById(R.id.text);
        listView = content.findViewById(R.id.list_view);
        addHalfLineDividerSpacing(listView);
        adapter = new ParentAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openGallery(position));

        AlertDialog newDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.gallery_history_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.open_direct_parent, null)
                .create();
        dialog = newDialog;
        newDialog.setOnShowListener(ignored -> {
            View directButton = newDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            directButton.setVisibility(View.GONE);
            directButton.setOnClickListener(view -> {
                UrlOpener.openUrl(context, currentGallery.parent, true);
                newDialog.dismiss();
            });
        });
        newDialog.setOnDismissListener(ignored -> {
            if (dialog == newDialog) {
                cancelTask();
                dialog = null;
                progressView = null;
                statusView = null;
                listView = null;
                adapter = null;
            }
        });
        newDialog.show();

        if (cachedItems != null) {
            showItems(cachedItems);
        } else {
            startLoading();
        }
    }

    public void destroy() {
        destroyed = true;
        cancelTask();
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void startLoading() {
        showLoading();
        GalleryUpdateTask nextTask = new GalleryUpdateTask(context, currentGallery,
                new GalleryUpdateTask.Listener() {
                    @Override
                    public void onSuccess(
                            @NonNull List<GalleryUpdateTask.ParentGallery> parents) {
                        if (task == null || destroyed) {
                            return;
                        }
                        task = null;
                        cachedItems = new ArrayList<>(parents);
                        showItems(cachedItems);
                    }

                    @Override
                    public void onFailure(@NonNull Exception error) {
                        if (task == null || destroyed) {
                            return;
                        }
                        task = null;
                        showError(error);
                    }
                });
        task = nextTask;
        nextTask.start();
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void showLoading() {
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }
        if (statusView != null) {
            statusView.setVisibility(View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(View.GONE);
        }
    }

    private void showItems(@NonNull List<GalleryUpdateTask.ParentGallery> items) {
        if (adapter == null || progressView == null || statusView == null || listView == null) {
            return;
        }
        adapter.replace(items);
        progressView.setVisibility(View.GONE);
        statusView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty()) {
            statusView.setText(R.string.gallery_history_empty);
        }
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        loadReadingProgress(items, adapter);
    }

    private void addHalfLineDividerSpacing(@NonNull ListView target) {
        Drawable divider = target.getDivider();
        if (divider == null) {
            return;
        }
        int height = Math.round(10 * context.getResources().getDisplayMetrics().density);
        int lineHeight = Math.min(height, Math.max(1, divider.getIntrinsicHeight()));
        int inset = height - lineHeight;
        target.setDivider(new InsetDrawable(divider, 0, inset / 2, 0,
                inset - inset / 2));
        target.setDividerHeight(height);
    }

    private void loadReadingProgress(
            @NonNull List<GalleryUpdateTask.ParentGallery> items,
            @NonNull ParentAdapter targetAdapter) {
        ArrayList<DownloadInfo> downloads = new ArrayList<>();
        for (GalleryUpdateTask.ParentGallery item : items) {
            DownloadInfo info = downloadManager.getDownloadInfo(item.gid);
            if (info != null) {
                downloads.add(info);
            }
        }
        if (downloads.isEmpty()) {
            return;
        }
        new DownloadSpiderInfoExecutor(downloads, result -> {
            if (!destroyed && adapter == targetAdapter) {
                targetAdapter.setReadingProgress(result);
            }
        }).execute();
    }

    private void showError(@NonNull Exception error) {
        if (progressView == null || statusView == null || listView == null) {
            return;
        }
        progressView.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);
        statusView.setText(context.getString(R.string.parent_gallery_list_failed,
                ExceptionUtils.getReadableString(error)));
        statusView.setVisibility(View.VISIBLE);
        if (dialog != null) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
        }
    }

    private void openGallery(int position) {
        if (adapter == null || position < 0 || position >= adapter.getCount()) {
            return;
        }
        GalleryUpdateTask.ParentGallery item = adapter.getItem(position);
        Announcer announcer = ClipboardUtil.createAnnouncerFromClipboardUrl(item.getUrl());
        if (announcer != null) {
            host.startScene(announcer);
            if (dialog != null) {
                dialog.dismiss();
            }
        }
    }

    private final class ParentAdapter extends BaseAdapter {
        private final ArrayList<GalleryUpdateTask.ParentGallery> items = new ArrayList<>();
        private final Map<Long, SpiderInfo> readingProgress = new HashMap<>();
        private final Set<Long> loadedProgress = new HashSet<>();
        private final LayoutInflater inflater = LayoutInflater.from(context);
        @Nullable
        private final Drawable downloadedIcon = AppCompatResources.getDrawable(
                context, R.drawable.v_download_badge_x24);

        void replace(@NonNull List<GalleryUpdateTask.ParentGallery> replacement) {
            items.clear();
            items.addAll(replacement);
            readingProgress.clear();
            loadedProgress.clear();
            notifyDataSetChanged();
        }

        void setReadingProgress(@NonNull Map<Long, SpiderInfo> replacement) {
            readingProgress.putAll(replacement);
            loadedProgress.addAll(replacement.keySet());
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public GalleryUpdateTask.ParentGallery getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).gid;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ParentHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_parent_gallery_history,
                        parent, false);
                holder = new ParentHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ParentHolder) convertView.getTag();
            }

            GalleryUpdateTask.ParentGallery item = getItem(position);
            DownloadInfo downloadInfo = downloadManager.getDownloadInfo(item.gid);
            boolean downloaded = downloadInfo != null;

            String title = item.title;
            String posted = item.posted;
            if (downloadInfo != null) {
                String localTitle = EhUtils.getSuitableTitle(downloadInfo);
                if (!TextUtils.isEmpty(localTitle)) {
                    title = localTitle;
                }
                if (!TextUtils.isEmpty(downloadInfo.posted)) {
                    posted = downloadInfo.posted;
                }
            }

            holder.sequence.setText(String.format(Locale.getDefault(), "%d", position + 1));
            holder.posted.setText(posted);
            holder.title.setText(title);
            holder.title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null, null, downloaded ? downloadedIcon : null, null);
            holder.pageProgress.setText(getPageText(item, downloadInfo));
            return convertView;
        }

        @Nullable
        private String getPageText(@NonNull GalleryUpdateTask.ParentGallery item,
                                   @Nullable DownloadInfo downloadInfo) {
            if (downloadInfo == null) {
                return item.pages > 0
                        ? String.format(Locale.getDefault(), "%d", item.pages) : null;
            }

            SpiderInfo spiderInfo = readingProgress.get(item.gid);
            if (spiderInfo != null && spiderInfo.pages > 0) {
                int page = Math.min(Math.max(0, spiderInfo.startPage),
                        spiderInfo.pages - 1) + 1;
                return String.format(Locale.getDefault(), "%d/%d", page, spiderInfo.pages);
            }
            if (!loadedProgress.contains(item.gid)) {
                return null;
            }

            int total = Math.max(item.pages, Math.max(downloadInfo.pages, downloadInfo.total));
            return total > 0 ? String.format(Locale.getDefault(), "%d", total) : null;
        }
    }

    private static final class ParentHolder {
        final TextView sequence;
        final TextView posted;
        final TextView pageProgress;
        final TextView title;

        ParentHolder(@NonNull View itemView) {
            sequence = itemView.findViewById(R.id.sequence);
            posted = itemView.findViewById(R.id.posted);
            pageProgress = itemView.findViewById(R.id.page_progress);
            title = itemView.findViewById(R.id.title);
        }
    }
}
