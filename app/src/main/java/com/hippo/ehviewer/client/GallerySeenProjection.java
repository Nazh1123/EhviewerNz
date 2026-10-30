package com.hippo.ehviewer.client;

import android.os.Bundle;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Display-only partition. Once a divider is shown, pagination never inserts above it. */
public final class GallerySeenProjection {
    private final Set<Long> prefix = new HashSet<>();
    private boolean frozen;

    public void reset() { prefix.clear(); frozen = false; }

    public List<GalleryInfo> project(List<GalleryInfo> source, Predicate<GalleryInfo> isNew) {
        if (!frozen) {
            prefix.clear();
            for (GalleryInfo gallery : source) if (isNew.test(gallery)) prefix.add(gallery.gid);
            frozen = source.stream().anyMatch(gallery -> !isNew.test(gallery));
        }
        List<GalleryInfo> result = new ArrayList<>(source.size());
        for (GalleryInfo gallery : source) if (prefix.contains(gallery.gid)) result.add(gallery);
        for (GalleryInfo gallery : source) if (!prefix.contains(gallery.gid)) result.add(gallery);
        return result;
    }

    public boolean inPrefix(long gid) { return prefix.contains(gid); }

    public void save(Bundle out) {
        out.putBoolean("seen_projection_frozen", frozen);
        long[] ids = new long[prefix.size()]; int i = 0;
        for (long gid : prefix) ids[i++] = gid;
        out.putLongArray("seen_projection_prefix", ids);
    }

    public void restore(Bundle in) {
        reset();
        frozen = in.getBoolean("seen_projection_frozen");
        long[] ids = in.getLongArray("seen_projection_prefix");
        if (ids != null) for (long gid : ids) prefix.add(gid);
    }
}
