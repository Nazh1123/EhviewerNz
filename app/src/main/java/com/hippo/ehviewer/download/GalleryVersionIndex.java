package com.hippo.ehviewer.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.LongPredicate;

/** Local version membership. All access is guarded by the owning DownloadManager's lock. */
final class GalleryVersionIndex {
    private final Map<Long, NavigableSet<Long>> families = new HashMap<>();
    // Remember the indexed value independently of mutable DownloadInfo objects.
    private final Map<Long, Long> familyByGid = new HashMap<>();

    void update(long gid, Long firstGid, boolean imported) {
        Long family = !imported && firstGid != null && firstGid > 0L ? firstGid : null;
        if (Objects.equals(familyByGid.get(gid), family)) return;
        remove(gid);
        if (family != null) {
            families.computeIfAbsent(family, ignored -> new TreeSet<>()).add(gid);
            familyByGid.put(gid, family);
        }
    }

    void remove(long gid) {
        Long family = familyByGid.remove(gid);
        if (family == null) return;
        NavigableSet<Long> gids = families.get(family);
        gids.remove(gid);
        if (gids.isEmpty()) families.remove(family);
    }

    void clear() {
        families.clear();
        familyByGid.clear();
    }

    boolean hasOlder(long firstGid, long targetGid) {
        NavigableSet<Long> gids = families.get(firstGid);
        return gids != null && gids.lower(targetGid) != null;
    }

    List<Long> getOlder(long firstGid, long targetGid) {
        NavigableSet<Long> gids = families.get(firstGid);
        return gids == null ? Collections.emptyList()
                : new ArrayList<>(gids.headSet(targetGid, false));
    }

    Long findClosestOlder(long firstGid, long targetGid, LongPredicate eligible) {
        NavigableSet<Long> gids = families.get(firstGid);
        if (gids != null) {
            for (Long gid : gids.headSet(targetGid, false).descendingSet()) {
                if (eligible.test(gid)) return gid;
            }
        }
        return null;
    }
}
