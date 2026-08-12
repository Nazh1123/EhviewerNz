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
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.unifile.UniFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists gallery-update plans and reuses parent pages by pToken.
 *
 * <p>The old page number is deliberately not used as identity. A new gallery page is reused only
 * when its pToken exists in the selected parent gallery and the corresponding local image is still
 * present and readable.</p>
 */
public final class GalleryUpdateManager {

    private static final String TAG = GalleryUpdateManager.class.getSimpleName();
    private static final String PREFS_NAME = "gallery_update_plans";
    private static final String KEY_PREFIX = "target_";
    private static final String JSON_SOURCE_GID = "source_gid";
    private static final String JSON_PARENT_GIDS = "parent_gids";
    private static final String FAILED_PTOKEN = "failed";

    private static final Map<Long, UpdatePlan> PLAN_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, SourcePages> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> CLEANUP_IN_PROGRESS =
            Collections.synchronizedSet(new HashSet<>());

    private GalleryUpdateManager() {
    }

    public static final class UpdatePlan {
        public final long targetGid;
        public final long sourceGid;
        @NonNull
        public final List<Long> parentGids;

        UpdatePlan(long targetGid, long sourceGid, @NonNull List<Long> parentGids) {
            this.targetGid = targetGid;
            this.sourceGid = sourceGid;
            this.parentGids = Collections.unmodifiableList(parentGids);
        }
    }

    private static final class SourcePages {
        @NonNull
        final Map<String, UniFile> filesByPToken;

        SourcePages(@NonNull Map<String, UniFile> filesByPToken) {
            this.filesByPToken = filesByPToken;
        }
    }

    @Nullable
    private static SharedPreferences preferences() {
        EhApplication application = EhApplication.getInstance();
        return application != null
                ? application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;
    }

    public static synchronized void register(long targetGid, long sourceGid,
                                             @NonNull List<Long> parentGids) {
        SharedPreferences preferences = preferences();
        if (preferences == null || targetGid <= 0L || sourceGid <= 0L) {
            return;
        }

        JSONArray parents = new JSONArray();
        ArrayList<Long> normalizedParents = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (Long gid : parentGids) {
            if (gid != null && gid > 0L && gid != targetGid && seen.add(gid)) {
                parents.put(gid);
                normalizedParents.add(gid);
            }
        }
        if (!seen.contains(sourceGid)) {
            parents.put(sourceGid);
            normalizedParents.add(sourceGid);
        }

        try {
            JSONObject json = new JSONObject();
            json.put(JSON_SOURCE_GID, sourceGid);
            json.put(JSON_PARENT_GIDS, parents);
            preferences.edit().putString(KEY_PREFIX + targetGid, json.toString()).apply();
            PLAN_CACHE.put(targetGid, new UpdatePlan(targetGid, sourceGid, normalizedParents));
            SOURCE_CACHE.remove(targetGid);
        } catch (JSONException e) {
            Log.w(TAG, "Unable to persist gallery update plan", e);
        }
    }

    @Nullable
    public static UpdatePlan getPlan(long targetGid) {
        UpdatePlan cached = PLAN_CACHE.get(targetGid);
        if (cached != null) {
            return cached;
        }
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return null;
        }
        String raw = preferences.getString(KEY_PREFIX + targetGid, null);
        if (TextUtils.isEmpty(raw)) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(raw);
            long sourceGid = json.getLong(JSON_SOURCE_GID);
            JSONArray parents = json.getJSONArray(JSON_PARENT_GIDS);
            ArrayList<Long> parentGids = new ArrayList<>(parents.length());
            HashSet<Long> seen = new HashSet<>();
            for (int i = 0; i < parents.length(); i++) {
                long gid = parents.getLong(i);
                if (gid > 0L && gid != targetGid && seen.add(gid)) {
                    parentGids.add(gid);
                }
            }
            if (sourceGid <= 0L || parentGids.isEmpty()) {
                cancel(targetGid);
                return null;
            }
            UpdatePlan plan = new UpdatePlan(targetGid, sourceGid, parentGids);
            PLAN_CACHE.put(targetGid, plan);
            return plan;
        } catch (JSONException e) {
            Log.w(TAG, "Invalid gallery update plan for " + targetGid, e);
            cancel(targetGid);
            return null;
        }
    }

    @NonNull
    public static List<Long> getPlannedTargetGids() {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return Collections.emptyList();
        }
        ArrayList<Long> result = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            try {
                result.add(Long.parseLong(key.substring(KEY_PREFIX.length())));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    public static boolean tryReuse(long targetGid, int targetIndex, @Nullable String pToken,
                                   @NonNull SpiderDen targetDen) {
        if (TextUtils.isEmpty(pToken) || FAILED_PTOKEN.equals(pToken)) {
            return false;
        }
        UpdatePlan plan = getPlan(targetGid);
        if (plan == null) {
            return false;
        }

        SourcePages source = SOURCE_CACHE.get(targetGid);
        if (source == null) {
            synchronized (SOURCE_CACHE) {
                source = SOURCE_CACHE.get(targetGid);
                if (source == null) {
                    source = loadSourcePages(plan.sourceGid);
                    if (source == null) {
                        return false;
                    }
                    SOURCE_CACHE.put(targetGid, source);
                }
            }
        }

        UniFile oldFile = source.filesByPToken.get(pToken);
        return oldFile != null && oldFile.exists() && oldFile.length() > 0L
                && SpiderDen.isReadableImage(oldFile)
                && targetDen.copyImageFrom(oldFile, targetIndex);
    }

    @Nullable
    private static SourcePages loadSourcePages(long sourceGid) {
        DownloadInfo sourceInfo = EhApplication.getDownloadManager().getDownloadInfo(sourceGid);
        if (sourceInfo == null) {
            return null;
        }
        UniFile sourceDir = SpiderDen.getExistingGalleryDownloadDir(sourceInfo);
        if (sourceDir == null || !sourceDir.isDirectory()) {
            return null;
        }
        SpiderInfo spiderInfo = SpiderInfo.read(sourceDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME));
        if (spiderInfo == null || spiderInfo.gid != sourceGid || spiderInfo.pTokenMap == null) {
            return null;
        }

        HashMap<String, UniFile> filesByPToken = new HashMap<>();
        for (int i = 0; i < spiderInfo.pTokenMap.size(); i++) {
            int oldIndex = spiderInfo.pTokenMap.keyAt(i);
            String pToken = spiderInfo.pTokenMap.valueAt(i);
            if (TextUtils.isEmpty(pToken) || FAILED_PTOKEN.equals(pToken)
                    || filesByPToken.containsKey(pToken)) {
                continue;
            }
            UniFile image = SpiderDen.findImageFile(sourceDir, oldIndex);
            if (image != null && image.exists() && image.length() > 0L) {
                filesByPToken.put(pToken, image);
            }
        }
        return new SourcePages(filesByPToken);
    }

    public static boolean beginCleanup(long targetGid) {
        return getPlan(targetGid) != null && CLEANUP_IN_PROGRESS.add(targetGid);
    }

    public static void finishCleanup(long targetGid, boolean success) {
        CLEANUP_IN_PROGRESS.remove(targetGid);
        if (success) {
            cancel(targetGid);
        }
    }

    public static synchronized void cancel(long targetGid) {
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            preferences.edit().remove(KEY_PREFIX + targetGid).apply();
        }
        PLAN_CACHE.remove(targetGid);
        SOURCE_CACHE.remove(targetGid);
        CLEANUP_IN_PROGRESS.remove(targetGid);
    }
}
