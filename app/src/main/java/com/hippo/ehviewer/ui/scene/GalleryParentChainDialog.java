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
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.GalleryUpdateTask;
import com.hippo.ehviewer.util.ClipboardUtil;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;

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
        private final LayoutInflater inflater = LayoutInflater.from(context);

        void replace(@NonNull List<GalleryUpdateTask.ParentGallery> replacement) {
            items.clear();
            items.addAll(replacement);
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
            TextView textView;
            if (convertView instanceof TextView) {
                textView = (TextView) convertView;
            } else {
                textView = (TextView) inflater.inflate(
                        R.layout.dialog_item_select_with_icon, parent, false);
            }

            GalleryUpdateTask.ParentGallery item = getItem(position);
            textView.setText(item.label);
            Drawable icon = null;
            if (downloadManager.containDownloadInfo(item.gid)) {
                icon = AppCompatResources.getDrawable(context, R.drawable.v_download_x24);
                if (icon != null) {
                    icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
                }
            }
            textView.setCompoundDrawablesRelative(icon, null, null, null);
            return textView;
        }
    }
}
