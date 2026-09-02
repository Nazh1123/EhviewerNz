/*
 * Copyright 2026 EhViewer contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.data.GalleryChainMetadata;
import com.hippo.ehviewer.client.data.GalleryInfo;

/** Fetches only the gdata needed to identify a gallery's version family. */
public final class GalleryVersionMetadataTask {

    public interface Listener {
        void onSuccess(long firstGid);

        void onFailure(@NonNull Exception error);
    }

    private final GalleryInfo gallery;
    private final Listener listener;
    private final EhClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable
    private EhRequest request;
    private boolean stopped;

    public GalleryVersionMetadataTask(@NonNull Context context, @NonNull GalleryInfo gallery,
                                      @NonNull Listener listener) {
        this.gallery = gallery;
        this.listener = listener;
        client = EhApplication.getEhClient(context);
    }

    public void start() {
        if (stopped) {
            return;
        }
        if (gallery.firstGid != null) {
            if (gallery.firstGid > 0L) {
                handler.post(() -> complete(gallery.firstGid));
            } else {
                handler.post(() -> fail(new IllegalStateException(
                        "Gallery version metadata is unavailable")));
            }
            return;
        }
        if (gallery.gid <= 0L || gallery.token == null || gallery.token.isEmpty()) {
            handler.post(() -> fail(new IllegalStateException("Missing gallery identity")));
            return;
        }
        EhRequest next = new EhRequest()
                .setMethod(EhClient.METHOD_GET_GALLERY_CHAIN_METADATA)
                .setArgs(gallery.gid, gallery.token)
                .setCallback(new EhClient.Callback<GalleryChainMetadata>() {
                    @Override
                    public void onSuccess(GalleryChainMetadata result) {
                        request = null;
                        long firstGid = result.firstGid > 0L ? result.firstGid : gallery.gid;
                        gallery.firstGid = firstGid;
                        complete(firstGid);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        request = null;
                        fail(e);
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        request = next;
        client.execute(next);
    }

    public void cancel() {
        stopped = true;
        if (request != null) {
            request.cancel();
            request = null;
        }
    }

    private void complete(long firstGid) {
        if (!stopped) {
            stopped = true;
            listener.onSuccess(firstGid);
        }
    }

    private void fail(@NonNull Exception error) {
        if (!stopped) {
            stopped = true;
            listener.onFailure(error);
        }
    }
}
