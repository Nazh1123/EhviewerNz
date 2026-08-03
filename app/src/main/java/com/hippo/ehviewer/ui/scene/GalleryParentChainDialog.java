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
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryChainMetadata;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.NewVersion;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.util.ClipboardUtil;
import com.hippo.scene.Announcer;
import com.hippo.util.ExceptionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class GalleryParentChainDialog {

    private static final int MAX_FORWARD_SEGMENTS = 8;
    private static final int MAX_PARENT_DEPTH = 100;
    private static final int API_BURST_SIZE = 4;
    private static final long API_BURST_PAUSE = 5200L;

    private final GalleryInfoScene host;
    private final Context context;
    private final GalleryDetail currentGallery;
    private final EhClient client;
    private final DownloadManager downloadManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final TreeMap<Long, ParentGallery> forwardItems = new TreeMap<>();
    private final Set<Long> forwardAnchors = new HashSet<>();
    private final ArrayList<ParentGallery> reverseItems = new ArrayList<>();
    private final Set<Long> reverseGids = new HashSet<>();

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
    private EhRequest request;
    @Nullable
    private Runnable scheduledRequest;
    @Nullable
    private ArrayList<ParentGallery> cachedItems;

    private boolean destroyed;
    private boolean reverseFallback;
    private long runId;
    private long firstGid = -1L;
    private int forwardSegmentCount;
    private int metadataRequestCount;

    GalleryParentChainDialog(@NonNull GalleryInfoScene host, @NonNull Context context,
                             @NonNull GalleryDetail currentGallery) {
        this.host = host;
        this.context = context;
        this.currentGallery = currentGallery;
        client = EhApplication.getEhClient(context);
        downloadManager = EhApplication.getDownloadManager(context);
    }

    void show() {
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

        statusView.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);
        progressView.setVisibility(View.VISIBLE);

        AlertDialog newDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.parent_gallery_list_title)
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
                cancelActiveRun();
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

    void destroy() {
        destroyed = true;
        cancelActiveRun();
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void startLoading() {
        cancelActiveRequest();
        runId++;
        forwardItems.clear();
        forwardAnchors.clear();
        reverseItems.clear();
        reverseGids.clear();
        reverseFallback = false;
        firstGid = -1L;
        forwardSegmentCount = 0;
        metadataRequestCount = 0;
        showLoading();

        long activeRun = runId;
        requestMetadata(activeRun, currentGallery.gid, currentGallery.token, false,
                new MetadataHandler() {
                    @Override
                    public void onSuccess(GalleryChainMetadata metadata) {
                        firstGid = metadata.firstGid;
                        if (metadata.firstGid > 0L && !TextUtils.isEmpty(metadata.firstToken)
                                && metadata.firstGid != currentGallery.gid) {
                            requestForwardSegment(activeRun, metadata.firstGid,
                                    metadata.firstToken);
                        } else {
                            startReverseFallback(activeRun);
                        }
                    }

                    @Override
                    public void onFailure(Exception error) {
                        startReverseFallback(activeRun);
                    }
                });
    }

    private void requestForwardSegment(long activeRun, long gid, @NonNull String token) {
        if (!isActive(activeRun) || forwardSegmentCount >= MAX_FORWARD_SEGMENTS
                || !forwardAnchors.add(gid)) {
            startReverseFallback(activeRun);
            return;
        }

        forwardSegmentCount++;
        EhRequest nextRequest = new EhRequest()
                .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
                .setArgs(EhUrl.getGalleryDetailUrl(gid, token))
                .setCallback(new EhClient.Callback<GalleryDetail>() {
                    @Override
                    public void onSuccess(GalleryDetail result) {
                        request = null;
                        if (isActive(activeRun)) {
                            handleForwardSegment(activeRun, result);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        request = null;
                        if (isActive(activeRun)) {
                            startReverseFallback(activeRun);
                        }
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        request = nextRequest;
        client.execute(nextRequest);
    }

    private void handleForwardSegment(long activeRun, @NonNull GalleryDetail detail) {
        int previousSize = forwardItems.size();
        addForwardItem(new ParentGallery(detail.gid,
                EhUrl.getGalleryDetailUrl(detail.gid, detail.token), detailLabel(detail)));

        NewVersion[] newVersions = detail.newVersions;
        if (newVersions != null) {
            for (NewVersion version : newVersions) {
                GalleryDetailUrlParser.Result parsed = GalleryDetailUrlParser.parse(
                        version.versionUrl, false);
                if (parsed != null) {
                    String label = TextUtils.isEmpty(version.versionName)
                            ? fallbackLabel(parsed.gid) : version.versionName;
                    addForwardItem(new ParentGallery(parsed.gid, version.versionUrl, label));
                }
            }
        }

        if (forwardItems.containsKey(currentGallery.gid)) {
            complete(buildForwardParents());
            return;
        }

        showItems(buildForwardParents());
        if (forwardItems.size() == previousSize) {
            startReverseFallback(activeRun);
            return;
        }

        Map.Entry<Long, ParentGallery> nextAnchor = forwardItems.lowerEntry(currentGallery.gid);
        while (nextAnchor != null && forwardAnchors.contains(nextAnchor.getKey())) {
            nextAnchor = forwardItems.lowerEntry(nextAnchor.getKey());
        }
        if (nextAnchor == null) {
            startReverseFallback(activeRun);
            return;
        }

        GalleryDetailUrlParser.Result parsed = GalleryDetailUrlParser.parse(
                nextAnchor.getValue().url, false);
        if (parsed == null) {
            startReverseFallback(activeRun);
        } else {
            requestForwardSegment(activeRun, parsed.gid, parsed.token);
        }
    }

    private void addForwardItem(@NonNull ParentGallery item) {
        if (item.gid > 0L && item.gid <= currentGallery.gid) {
            forwardItems.put(item.gid, item);
        }
    }

    @NonNull
    private ArrayList<ParentGallery> buildForwardParents() {
        ArrayList<ParentGallery> result = new ArrayList<>();
        for (ParentGallery item : forwardItems.descendingMap().values()) {
            if (item.gid < currentGallery.gid) {
                result.add(item);
            }
        }
        return result;
    }

    private void startReverseFallback(long activeRun) {
        if (!isActive(activeRun) || reverseFallback) {
            return;
        }
        reverseFallback = true;
        cancelActiveRequest();
        reverseItems.clear();
        reverseGids.clear();
        showItems(reverseItems);

        GalleryDetailUrlParser.Result parent = GalleryDetailUrlParser.parse(
                currentGallery.parent, false);
        if (parent == null) {
            showError(new IllegalStateException("Invalid parent gallery URL"));
            return;
        }
        continueReverse(activeRun, parent.gid, parent.token);
    }

    private void continueReverse(long activeRun, long gid, @NonNull String token) {
        if (!isActive(activeRun)) {
            return;
        }
        if (forwardItems.containsKey(gid)) {
            appendForwardTail(gid);
            complete(reverseItems);
            return;
        }
        if (reverseItems.size() >= MAX_PARENT_DEPTH || !reverseGids.add(gid)) {
            showError(new IllegalStateException("Parent gallery chain did not converge"));
            return;
        }

        requestMetadata(activeRun, gid, token, true, new MetadataHandler() {
            @Override
            public void onSuccess(GalleryChainMetadata metadata) {
                if (metadata.gid != gid) {
                    showError(new IllegalStateException("Unexpected gallery metadata"));
                    return;
                }

                String resultToken = TextUtils.isEmpty(metadata.token) ? token : metadata.token;
                reverseItems.add(new ParentGallery(gid,
                        EhUrl.getGalleryDetailUrl(gid, resultToken), metadataLabel(metadata)));
                showItems(reverseItems);

                if (gid == firstGid || metadata.parentGid <= 0L
                        || TextUtils.isEmpty(metadata.parentToken)) {
                    complete(reverseItems);
                } else if (forwardItems.containsKey(metadata.parentGid)) {
                    appendForwardTail(metadata.parentGid);
                    complete(reverseItems);
                } else {
                    continueReverse(activeRun, metadata.parentGid, metadata.parentToken);
                }
            }

            @Override
            public void onFailure(Exception error) {
                showError(error);
            }
        });
    }

    private void appendForwardTail(long intersectionGid) {
        for (ParentGallery item : forwardItems.descendingMap().values()) {
            if (item.gid <= intersectionGid && reverseGids.add(item.gid)) {
                reverseItems.add(item);
            }
        }
        showItems(reverseItems);
    }

    private void requestMetadata(long activeRun, long gid, @Nullable String token,
                                 boolean respectBurstLimit, @NonNull MetadataHandler callback) {
        if (!isActive(activeRun) || TextUtils.isEmpty(token)) {
            callback.onFailure(new IllegalStateException("Missing gallery token"));
            return;
        }

        Runnable execute = () -> {
            scheduledRequest = null;
            if (!isActive(activeRun)) {
                return;
            }
            metadataRequestCount++;
            EhRequest nextRequest = new EhRequest()
                    .setMethod(EhClient.METHOD_GET_GALLERY_CHAIN_METADATA)
                    .setArgs(gid, token)
                    .setCallback(new EhClient.Callback<GalleryChainMetadata>() {
                        @Override
                        public void onSuccess(GalleryChainMetadata result) {
                            request = null;
                            if (isActive(activeRun)) {
                                callback.onSuccess(result);
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            request = null;
                            if (isActive(activeRun)) {
                                callback.onFailure(e);
                            }
                        }

                        @Override
                        public void onCancel() {
                        }
                    });
            request = nextRequest;
            client.execute(nextRequest);
        };

        if (respectBurstLimit && metadataRequestCount > 0
                && metadataRequestCount % API_BURST_SIZE == 0) {
            scheduledRequest = execute;
            handler.postDelayed(execute, API_BURST_PAUSE);
        } else {
            execute.run();
        }
    }

    private void complete(@NonNull List<ParentGallery> items) {
        cachedItems = new ArrayList<>(items);
        showItems(cachedItems);
    }

    private void showLoading() {
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }
        if (statusView != null) {
            statusView.setText(R.string.parent_gallery_list_loading);
            statusView.setVisibility(View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(View.GONE);
        }
    }

    private void showItems(@NonNull List<ParentGallery> items) {
        if (adapter == null || progressView == null || statusView == null || listView == null) {
            return;
        }
        adapter.replace(items);
        progressView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        statusView.setVisibility(View.GONE);
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showError(@NonNull Exception error) {
        if (progressView == null || statusView == null || listView == null || adapter == null) {
            return;
        }
        String readableError = ExceptionUtils.getReadableString(error);
        if (adapter.getCount() == 0) {
            progressView.setVisibility(View.GONE);
            listView.setVisibility(View.GONE);
            statusView.setText(context.getString(R.string.parent_gallery_list_failed, readableError));
            statusView.setVisibility(View.VISIBLE);
        } else {
            Toast.makeText(context,
                    context.getString(R.string.parent_gallery_list_failed, readableError),
                    Toast.LENGTH_LONG).show();
        }
        if (dialog != null) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
        }
    }

    private void openGallery(int position) {
        if (adapter == null || position < 0 || position >= adapter.getCount()) {
            return;
        }
        ParentGallery item = adapter.getItem(position);
        Announcer announcer = ClipboardUtil.createAnnouncerFromClipboardUrl(item.url);
        if (announcer != null) {
            host.startScene(announcer);
            if (dialog != null) {
                dialog.dismiss();
            }
        }
    }

    private boolean isActive(long activeRun) {
        return !destroyed && activeRun == runId && dialog != null && dialog.isShowing();
    }

    private void cancelActiveRun() {
        runId++;
        cancelActiveRequest();
    }

    private void cancelActiveRequest() {
        if (scheduledRequest != null) {
            handler.removeCallbacks(scheduledRequest);
            scheduledRequest = null;
        }
        if (request != null) {
            request.cancel();
            request = null;
        }
    }

    private static String fallbackLabel(long gid) {
        return "GID " + gid;
    }

    private static String appendPosted(@Nullable String title, @Nullable String posted, long gid) {
        String safeTitle = TextUtils.isEmpty(title) ? fallbackLabel(gid) : title;
        return TextUtils.isEmpty(posted) ? safeTitle : safeTitle + "\n" + posted;
    }

    private static String detailLabel(@NonNull GalleryDetail detail) {
        return appendPosted(EhUtils.getSuitableTitle(detail), detail.posted, detail.gid);
    }

    private static String metadataLabel(@NonNull GalleryChainMetadata metadata) {
        String title = Settings.getShowJpnTitle() && !TextUtils.isEmpty(metadata.titleJpn)
                ? metadata.titleJpn : metadata.title;
        return appendPosted(title, metadata.posted, metadata.gid);
    }

    private interface MetadataHandler {
        void onSuccess(GalleryChainMetadata metadata);

        void onFailure(Exception error);
    }

    private static final class ParentGallery {
        final long gid;
        final String url;
        final String label;

        ParentGallery(long gid, @NonNull String url, @NonNull String label) {
            this.gid = gid;
            this.url = url;
            this.label = label;
        }
    }

    private final class ParentAdapter extends BaseAdapter {
        private final ArrayList<ParentGallery> items = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(context);

        void replace(@NonNull List<ParentGallery> replacement) {
            items.clear();
            items.addAll(replacement);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public ParentGallery getItem(int position) {
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

            ParentGallery item = getItem(position);
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
