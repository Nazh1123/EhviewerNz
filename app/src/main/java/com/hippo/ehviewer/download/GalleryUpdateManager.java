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

import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.unifile.UniFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
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

    /**
     * Transfers reading progress from the selected parent using pToken identity.
     *
     * <p>This method performs storage I/O and must run on a worker thread. Existing target
     * progress always wins. If the exact source page was removed, the nearest source page still
     * present in the target is used; the next page is preferred when both sides are equally
     * close.</p>
     */
    public static boolean migrateReadingProgress(@NonNull Context context,
                                                 @NonNull GalleryInfo targetInfo) {
        UpdatePlan plan = getPlan(targetInfo.gid);
        if (plan == null) {
            return false;
        }

        SpiderInfo source = readDownloadedSpiderInfo(plan.sourceGid);
        SpiderInfo target = readDownloadedSpiderInfo(targetInfo.gid);
        if (source == null || target == null || source.pTokenMap == null
                || target.pTokenMap == null) {
            return false;
        }

        // Reading progress is also kept in the spider-info cache. Merge only startPage here:
        // the downloaded file owns the current gallery metadata and pToken table.
        source.startPage = Math.max(source.startPage,
                readCachedStartPage(context, plan.sourceGid));
        target.startPage = Math.max(target.startPage,
                readCachedStartPage(context, targetInfo.gid));

        // A non-zero target page means the user has already read the new gallery. Never replace
        // that newer, gallery-specific choice with progress inherited from its parent.
        if (target.startPage > 0) {
            return true;
        }

        int mappedPage = findMappedStartPage(source, target);
        if (mappedPage < 0) {
            // The target's pToken table can still be incomplete after a failed download. Leave
            // the plan intact so a later continuation can retry the migration.
            return false;
        }

        // Re-read before writing to narrow the race with a reader opened while the background
        // update was finishing. Any progress made on the target since the first read wins.
        SpiderInfo latestTarget = readDownloadedSpiderInfo(targetInfo.gid);
        if (latestTarget == null || latestTarget.pTokenMap == null) {
            return false;
        }
        latestTarget.startPage = Math.max(latestTarget.startPage,
                readCachedStartPage(context, targetInfo.gid));
        if (latestTarget.startPage > 0) {
            return true;
        }

        latestTarget.startPage = mappedPage;
        latestTarget.writeNewSpiderInfoToLocal(new SpiderDen(targetInfo), context);
        Log.i(TAG, "Migrated reading progress from " + plan.sourceGid + " page "
                + source.startPage + " to " + targetInfo.gid + " page " + mappedPage);
        return true;
    }

    /** Returns -1 until a source progress anchor can be matched to the target pToken table. */
    static int findMappedStartPage(@NonNull SpiderInfo source, @NonNull SpiderInfo target) {
        if (source.pages <= 0 || target.pages <= 0 || source.pTokenMap == null
                || target.pTokenMap == null || target.pTokenMap.size() == 0) {
            return -1;
        }

        HashMap<String, ArrayList<Integer>> targetIndexes = new HashMap<>();
        for (int i = 0; i < target.pTokenMap.size(); i++) {
            int index = target.pTokenMap.keyAt(i);
            String pToken = target.pTokenMap.valueAt(i);
            if (index < 0 || index >= target.pages || !isUsablePToken(pToken)) {
                continue;
            }
            ArrayList<Integer> indexes = targetIndexes.get(pToken);
            if (indexes == null) {
                indexes = new ArrayList<>(1);
                targetIndexes.put(pToken, indexes);
            }
            indexes.add(index);
        }
        if (targetIndexes.isEmpty()) {
            return -1;
        }

        int sourcePage = Math.max(0, Math.min(source.startPage, source.pages - 1));
        for (int distance = 0; distance < source.pages; distance++) {
            int forward = sourcePage + distance;
            int mapped = findTargetIndex(source, target, targetIndexes, forward);
            if (mapped >= 0) {
                return mapped;
            }
            // Prefer the following surviving page for an exact tie. It is the least surprising
            // continuation when the page being read was deleted by the update.
            if (distance > 0) {
                int backward = sourcePage - distance;
                mapped = findTargetIndex(source, target, targetIndexes, backward);
                if (mapped >= 0) {
                    return mapped;
                }
            }
            if (forward >= source.pages && sourcePage - distance < 0) {
                break;
            }
        }
        return -1;
    }

    private static int findTargetIndex(@NonNull SpiderInfo source, @NonNull SpiderInfo target,
                                       @NonNull Map<String, ArrayList<Integer>> targetIndexes,
                                       int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= source.pages) {
            return -1;
        }
        String pToken = source.pTokenMap.get(sourceIndex);
        if (!isUsablePToken(pToken)) {
            return -1;
        }
        ArrayList<Integer> matches = targetIndexes.get(pToken);
        if (matches == null || matches.isEmpty()) {
            return -1;
        }

        int expected = source.pages > 1
                ? (int) Math.round((double) sourceIndex * (target.pages - 1)
                / (source.pages - 1)) : 0;
        int best = matches.get(0);
        int bestDistance = Math.abs(best - expected);
        for (int i = 1; i < matches.size(); i++) {
            int candidate = matches.get(i);
            int candidateDistance = Math.abs(candidate - expected);
            if (candidateDistance < bestDistance) {
                best = candidate;
                bestDistance = candidateDistance;
            }
        }
        return best;
    }

    private static boolean isUsablePToken(@Nullable String pToken) {
        return !TextUtils.isEmpty(pToken) && !FAILED_PTOKEN.equals(pToken);
    }

    @Nullable
    private static SpiderInfo readDownloadedSpiderInfo(long gid) {
        GalleryInfo placeholder = new GalleryInfo();
        placeholder.gid = gid;
        UniFile dir = SpiderDen.getExistingGalleryDownloadDir(placeholder);
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        SpiderInfo result = SpiderInfo.read(dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME));
        return result != null && result.gid == gid ? result : null;
    }

    private static int readCachedStartPage(@NonNull Context context, long gid) {
        SimpleDiskCache cache = EhApplication.getSpiderInfoCache(context);
        InputStreamPipe pipe = cache.getInputStreamPipe(Long.toString(gid));
        if (pipe == null) {
            return 0;
        }
        try {
            pipe.obtain();
            SpiderInfo cached = SpiderInfo.read(pipe.open());
            return cached != null && cached.gid == gid ? cached.startPage : 0;
        } catch (IOException e) {
            return 0;
        } finally {
            pipe.close();
            pipe.release();
        }
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
