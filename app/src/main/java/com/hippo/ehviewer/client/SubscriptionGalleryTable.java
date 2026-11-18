package com.hippo.ehviewer.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shares identical gallery records throughout one saved result without losing metadata variants. */
final class SubscriptionGalleryTable {
    final JSONArray rows;
    private final Map<GalleryInfo, Integer> identity = new IdentityHashMap<>();
    private final Map<JSONObject, Integer> content = new HashMap<>();
    private final Map<Integer, GalleryInfo> restored = new HashMap<>();

    SubscriptionGalleryTable(JSONArray rows) {
        if (rows == null) throw new IllegalArgumentException("Missing gallery table");
        this.rows = rows;
    }

    JSONArray references(List<GalleryInfo> galleries) {
        JSONArray refs = new JSONArray();
        for (GalleryInfo gallery : galleries) {
            refs.add(identity.computeIfAbsent(gallery, entry -> {
                JSONObject row = (JSONObject) JSON.toJSON(entry);
                if (Float.isFinite(entry.apiRating)) row.put("apiRating", entry.apiRating);
                return content.computeIfAbsent(row, value -> {
                    int index = rows.size();
                    rows.add(value);
                    return index;
                });
            }));
        }
        return refs;
    }

    GalleryInfo gallery(int index) {
        // Decode on demand so a bad record only affects the sources that use it.
        return restored.computeIfAbsent(index, key -> readGallery(rows.getJSONObject(key)));
    }

    static GalleryInfo readGallery(JSONObject row) {
        GalleryInfo gallery = row.toJavaObject(GalleryInfo.class);
        if (gallery.gid <= 0) throw new IllegalArgumentException("Invalid gallery");
        gallery.apiRating = row.containsKey("apiRating") ? row.getFloatValue("apiRating") : Float.NaN;
        return gallery;
    }
}
