package com.hippo.ehviewer.client;

import android.util.AtomicFile;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.SubscriptionUpdateManager.RecentCheckResult;
import com.hippo.ehviewer.client.SubscriptionUpdateManager.RecentCheckSource;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One write-through first-page store shared by EH, bookmark checks and interactive refreshes. */
final class SubscriptionResultStore {
    static final String FILE_NAME = "subscription-results-v1.json";
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicFile file;
    private RecentCheckResult current;
    private boolean loaded;

    SubscriptionResultStore(File directory) {
        file = new AtomicFile(new File(directory, FILE_NAME));
    }

    synchronized RecentCheckResult get() {
        if (!loaded) {
            try {
                // Queued writes finish before a newly created manager restores the same file.
                current = IO.submit(this::read).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w("SubscriptionResults", "Unable to restore subscription results", e);
            }
            loaded = true;
        }
        return current;
    }

    synchronized void record(String contextKey, String displayKey, List<RecentCheckSource> updates) {
        if (updates.isEmpty()) return;
        RecentCheckResult previous = get();
        List<RecentCheckSource> sources = new ArrayList<>();
        if (previous != null && previous.contextKey.equals(contextKey)
                && previous.displayKey.equals(displayKey)) sources.addAll(previous.sources);
        for (RecentCheckSource update : updates) {
            sources.removeIf(update::sameSource);
            sources.add(update);
        }
        current = new RecentCheckResult(sources, contextKey, displayKey);
        if (previous != null) current.displaySnapshots.putAll(previous.displaySnapshots);
        RecentCheckResult snapshot = current;
        IO.execute(() -> write(snapshot));
    }

    synchronized void recordSnapshots(String contextKey, String displayKey,
            List<RecentCheckSource> sources, List<SubscriptionDisplaySnapshot> snapshots) {
        RecentCheckResult previous = get();
        current = new RecentCheckResult(sources, contextKey, displayKey);
        if (previous != null) current.displaySnapshots.putAll(previous.displaySnapshots);
        for (SubscriptionDisplaySnapshot snapshot : snapshots) current.displaySnapshots.put(snapshot.mode, snapshot);
        RecentCheckResult saved = current;
        IO.execute(() -> write(saved));
    }

    synchronized void clear() {
        loaded = true;
        current = null;
        IO.execute(file::delete);
    }

    private RecentCheckResult read() {
        try {
            JSONObject root = JSON.parseObject(new String(file.readFully(), StandardCharsets.UTF_8));
            int version = root.getIntValue("version");
            if (version < 1 || version > 3) return null;
            SubscriptionGalleryTable galleryTable = version == 3
                    ? new SubscriptionGalleryTable(root.getJSONArray("galleryTable")) : null;
            String context = root.getString("context"), display = root.getString("display");
            if (context == null || display == null) return null;
            List<RecentCheckSource> sources = new ArrayList<>();
            JSONArray saved = root.getJSONArray("sources");
            if (saved == null) return null;
            for (int i = 0; i < saved.size(); i++) {
                try { sources.add(new RecentCheckSource(saved.getJSONObject(i), galleryTable)); }
                catch (RuntimeException ignored) { /* A damaged source cannot erase other sources. */ }
            }
            RecentCheckResult result = new RecentCheckResult(sources, context, display);
            JSONArray snapshots = root.getJSONArray("snapshots");
            if (snapshots != null) for (int i = 0; i < snapshots.size(); i++) {
                try {
                    SubscriptionDisplaySnapshot snapshot = SubscriptionDisplaySnapshot.fromJson(
                            snapshots.getJSONObject(i), galleryTable);
                    result.displaySnapshots.put(snapshot.mode, snapshot);
                } catch (RuntimeException ignored) { /* A broken snapshot cannot affect the other entry. */ }
            }
            return sources.isEmpty() && result.displaySnapshots.isEmpty() ? null : result;
        } catch (Exception e) {
            // Missing or damaged results always fall back to an ordinary fresh request.
            return null;
        }
    }

    private void write(RecentCheckResult snapshot) {
        FileOutputStream output = null;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("context", snapshot.contextKey);
            root.put("display", snapshot.displayKey);
            SubscriptionGalleryTable galleryTable = new SubscriptionGalleryTable(new JSONArray());
            root.put("galleryTable", galleryTable.rows);
            JSONArray sources = new JSONArray();
            for (RecentCheckSource source : snapshot.sources) sources.add(source.toJson(galleryTable));
            root.put("sources", sources);
            JSONArray snapshots = new JSONArray();
            for (SubscriptionDisplaySnapshot display : snapshot.displaySnapshots.values()) {
                snapshots.add(display.toJson(galleryTable));
            }
            root.put("snapshots", snapshots);
            byte[] bytes = JSON.toJSONString(root).getBytes(StandardCharsets.UTF_8);
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            Log.w("SubscriptionResults", "Unable to save subscription results", e);
        }
    }
}
