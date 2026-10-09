package com.hippo.ehviewer.ui.scene.gallery.list;

import android.util.AtomicFile;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The last two successful popular responses. Disk operations run on IO, in request order. */
final class PopularGalleryHistory {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final int CURRENT = 0;
    static final int UPDATES = 1;
    static final int PREVIOUS = 2;

    private final AtomicFile file;
    private List<GalleryInfo> current;
    private List<GalleryInfo> previous;

    PopularGalleryHistory(File directory, int site) {
        file = new AtomicFile(new File(directory, "popular-history-" + site + ".json"));
    }

    void load() {
        try {
            JSONObject saved = JSON.parseObject(new String(file.readFully(), StandardCharsets.UTF_8));
            current = readList(saved, "current");
            previous = readList(saved, "previous");
        } catch (IOException | RuntimeException e) {
            // A missing or damaged cache must never prevent a fresh popular request.
            current = null;
            previous = null;
        }
    }

    private static List<GalleryInfo> readList(JSONObject saved, String key) {
        if (saved.getJSONArray(key) == null) return null;
        List<GalleryInfo> result = saved.getJSONArray(key).toJavaList(GalleryInfo.class);
        for (GalleryInfo gallery : result) {
            if (gallery == null || gallery.gid <= 0 || gallery.token == null) {
                throw new IllegalArgumentException("Invalid popular snapshot");
            }
        }
        return result;
    }

    void record(List<GalleryInfo> galleries) {
        previous = current;
        current = new ArrayList<>(galleries);
    }

    boolean hasCurrent() { return current != null; }
    boolean hasPrevious() { return previous != null; }

    List<GalleryInfo> galleries(int mode) {
        List<GalleryInfo> result = new ArrayList<>();
        if (mode == PREVIOUS) {
            if (previous != null) result.addAll(previous);
        } else if (current != null) {
            Set<Long> oldGids = new HashSet<>();
            if (mode == UPDATES && previous != null) {
                for (GalleryInfo gallery : previous) oldGids.add(gallery.gid);
            }
            for (GalleryInfo gallery : current) {
                if (!oldGids.contains(gallery.gid)) result.add(gallery);
            }
        }
        return result;
    }

    void updateFavorite(long gid, int slot) {
        for (GalleryInfo gallery : galleries(CURRENT)) {
            if (gallery.gid == gid) gallery.favoriteSlot = slot;
        }
        for (GalleryInfo gallery : galleries(PREVIOUS)) {
            if (gallery.gid == gid) gallery.favoriteSlot = slot;
        }
    }

    void persist() {
        // Capture the pair now so another response cannot change a queued write's baseline.
        JSONObject saved = new JSONObject();
        saved.put("current", new ArrayList<>(current));
        saved.put("previous", previous == null ? null : new ArrayList<>(previous));
        IO.execute(() -> write(saved));
    }

    private void write(JSONObject saved) {
        FileOutputStream output = null;
        try {
            byte[] bytes = JSON.toJSONString(saved).getBytes(StandardCharsets.UTF_8);
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            Log.w("PopularGalleryHistory", "Unable to save popular history", e);
        }
    }
}
