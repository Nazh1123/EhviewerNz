/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hippo.ehviewer.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryChainMetadata;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.NewVersion;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves a gallery's parent chain without retaining a scene or displaying a dialog.
 *
 * <p>The normal path is the same fast path used by the History dialog: obtain the first gallery,
 * then consume its {@code newVersions} list in one detail request. Reverse traversal is only a
 * fallback for incomplete or unusual chains.</p>
 */
public final class GalleryUpdateTask {

    private static final int MAX_FORWARD_SEGMENTS = 8;
    private static final int MAX_PARENT_DEPTH = 100;
    private static final int API_BURST_SIZE = 4;
    private static final long API_BURST_PAUSE = 5200L;

    public interface Listener {
        void onSuccess(@NonNull List<Long> parentGids);

        void onFailure(@NonNull Exception error);
    }

    private static final class ChainGallery {
        final long gid;
        @NonNull
        final String token;

        ChainGallery(long gid, @NonNull String token) {
            this.gid = gid;
            this.token = token;
        }
    }

    private final GalleryDetail target;
    private final Listener listener;
    private final EhClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TreeMap<Long, ChainGallery> forwardItems = new TreeMap<>();
    private final Set<Long> forwardAnchors = new HashSet<>();
    private final ArrayList<Long> reverseItems = new ArrayList<>();
    private final Set<Long> reverseGids = new HashSet<>();

    @Nullable
    private EhRequest request;
    @Nullable
    private Runnable scheduledRequest;
    private boolean stopped;
    private boolean reverseFallback;
    private long firstGid = -1L;
    private int forwardSegmentCount;
    private int metadataRequestCount;

    public GalleryUpdateTask(@NonNull Context context, @NonNull GalleryDetail target,
                             @NonNull Listener listener) {
        this.target = target;
        this.listener = listener;
        client = EhApplication.getEhClient(context);
    }

    public void start() {
        if (stopped) {
            return;
        }
        requestMetadata(target.gid, target.token, false, new MetadataHandler() {
            @Override
            public void onSuccess(@NonNull GalleryChainMetadata metadata) {
                firstGid = metadata.firstGid;
                if (metadata.firstGid > 0L && !TextUtils.isEmpty(metadata.firstToken)
                        && metadata.firstGid != target.gid) {
                    requestForwardSegment(metadata.firstGid, metadata.firstToken);
                } else {
                    startReverseFallback();
                }
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                startReverseFallback();
            }
        });
    }

    public void cancel() {
        stopped = true;
        cancelActiveRequest();
    }

    private void requestForwardSegment(long gid, @NonNull String token) {
        if (stopped || forwardSegmentCount >= MAX_FORWARD_SEGMENTS
                || !forwardAnchors.add(gid)) {
            startReverseFallback();
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
                        if (!stopped) {
                            handleForwardSegment(result);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        request = null;
                        if (!stopped) {
                            startReverseFallback();
                        }
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        request = nextRequest;
        client.execute(nextRequest);
    }

    private void handleForwardSegment(@NonNull GalleryDetail detail) {
        int previousSize = forwardItems.size();
        addForwardItem(detail.gid, detail.token);
        NewVersion[] newVersions = detail.newVersions;
        if (newVersions != null) {
            for (NewVersion version : newVersions) {
                GalleryDetailUrlParser.Result parsed = GalleryDetailUrlParser.parse(
                        version.versionUrl, false);
                if (parsed != null) {
                    addForwardItem(parsed.gid, parsed.token);
                }
            }
        }

        if (forwardItems.containsKey(target.gid)) {
            complete(buildForwardParents());
            return;
        }
        if (forwardItems.size() == previousSize) {
            startReverseFallback();
            return;
        }

        Map.Entry<Long, ChainGallery> nextAnchor = forwardItems.lowerEntry(target.gid);
        while (nextAnchor != null && forwardAnchors.contains(nextAnchor.getKey())) {
            nextAnchor = forwardItems.lowerEntry(nextAnchor.getKey());
        }
        if (nextAnchor == null) {
            startReverseFallback();
        } else {
            requestForwardSegment(nextAnchor.getValue().gid, nextAnchor.getValue().token);
        }
    }

    private void addForwardItem(long gid, @Nullable String token) {
        if (gid > 0L && gid <= target.gid && !TextUtils.isEmpty(token)) {
            forwardItems.put(gid, new ChainGallery(gid, token));
        }
    }

    @NonNull
    private ArrayList<Long> buildForwardParents() {
        ArrayList<Long> result = new ArrayList<>();
        for (Long gid : forwardItems.descendingKeySet()) {
            if (gid < target.gid) {
                result.add(gid);
            }
        }
        return result;
    }

    private void startReverseFallback() {
        if (stopped || reverseFallback) {
            return;
        }
        reverseFallback = true;
        cancelActiveRequest();
        GalleryDetailUrlParser.Result parent = GalleryDetailUrlParser.parse(target.parent, false);
        if (parent == null) {
            fail(new IllegalStateException("Invalid parent gallery URL"));
            return;
        }
        continueReverse(parent.gid, parent.token);
    }

    private void continueReverse(long gid, @NonNull String token) {
        if (stopped) {
            return;
        }
        if (forwardItems.containsKey(gid)) {
            appendForwardTail(gid);
            complete(reverseItems);
            return;
        }
        if (reverseItems.size() >= MAX_PARENT_DEPTH || !reverseGids.add(gid)) {
            fail(new IllegalStateException("Parent gallery chain did not converge"));
            return;
        }

        requestMetadata(gid, token, true, new MetadataHandler() {
            @Override
            public void onSuccess(@NonNull GalleryChainMetadata metadata) {
                if (metadata.gid != gid) {
                    fail(new IllegalStateException("Unexpected gallery metadata"));
                    return;
                }
                reverseItems.add(gid);
                if (gid == firstGid || metadata.parentGid <= 0L
                        || TextUtils.isEmpty(metadata.parentToken)) {
                    complete(reverseItems);
                } else if (forwardItems.containsKey(metadata.parentGid)) {
                    appendForwardTail(metadata.parentGid);
                    complete(reverseItems);
                } else {
                    continueReverse(metadata.parentGid, metadata.parentToken);
                }
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                fail(error);
            }
        });
    }

    private void appendForwardTail(long intersectionGid) {
        for (Long gid : forwardItems.descendingKeySet()) {
            if (gid <= intersectionGid && reverseGids.add(gid)) {
                reverseItems.add(gid);
            }
        }
    }

    private void requestMetadata(long gid, @Nullable String token, boolean respectBurstLimit,
                                 @NonNull MetadataHandler callback) {
        if (stopped || TextUtils.isEmpty(token)) {
            callback.onFailure(new IllegalStateException("Missing gallery token"));
            return;
        }
        Runnable execute = () -> {
            scheduledRequest = null;
            if (stopped) {
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
                            if (!stopped) {
                                callback.onSuccess(result);
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            request = null;
                            if (!stopped) {
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

    private void complete(@NonNull List<Long> parentGids) {
        if (stopped) {
            return;
        }
        stopped = true;
        cancelActiveRequest();
        listener.onSuccess(new ArrayList<>(parentGids));
    }

    private void fail(@NonNull Exception error) {
        if (stopped) {
            return;
        }
        stopped = true;
        cancelActiveRequest();
        listener.onFailure(error);
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

    private interface MetadataHandler {
        void onSuccess(@NonNull GalleryChainMetadata metadata);

        void onFailure(@NonNull Exception error);
    }
}
