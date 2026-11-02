package com.hippo.ehviewer.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One homepage pass; commits only after its oldest retained minute has been crossed completely. */
public final class SubscriptionHomepageScan {
    private static final int OVERLAP = 15;
    private final Map<Long, Long> previous = new LinkedHashMap<>();
    private final Map<Long, Long> current = new LinkedHashMap<>();
    private long previousHead;
    private long previousMinute;
    private long previousFloor;
    private boolean previousFloorComplete;
    private long floor;
    private long lastTailMinute;
    private long head;
    private long minute;
    private boolean initialized;
    private boolean boundary;
    private boolean extraPage;
    private int olderPages;
    private int olderThanFloor;
    private boolean closingMinute;
    private boolean finished;

    public SubscriptionHomepageScan(String checkpoint) {
        try {
            JSONObject state = JSON.parseObject(checkpoint);
            if (state == null) return;
            int version = state.getIntValue("version");
            if (version != 1 && version != 2) return;
            long anchor = state.getLongValue("head");
            long posted = state.getLongValue("minute");
            long oldest = state.getLongValue("floor");
            if (anchor <= 0 || posted <= 0 || oldest <= 0 || oldest > posted) return;
            if (version == 1) {
                JSONArray ids = state.getJSONArray("ids");
                if (ids == null) return;
                for (Long gid : ids.toJavaList(Long.class)) {
                    if (gid == null || gid <= 0) throw new IllegalArgumentException();
                    previous.put(gid, 0L);
                }
            } else {
                JSONArray entries = state.getJSONArray("entries");
                if (entries == null) return;
                for (int i = 0; i < entries.size(); i++) {
                    JSONArray entry = entries.getJSONArray(i);
                    if (entry == null || entry.size() != 2) throw new IllegalArgumentException();
                    long gid = entry.getLongValue(0), time = entry.getLongValue(1);
                    if (gid <= 0 || time < 0 || (time > 0 && time < oldest))
                        throw new IllegalArgumentException();
                    previous.put(gid, time);
                }
                if (!Long.valueOf(posted).equals(previous.get(anchor))) throw new IllegalArgumentException();
            }
            if (!previous.containsKey(anchor)) throw new IllegalArgumentException();
            previousHead = anchor;
            previousMinute = posted;
            previousFloor = oldest;
            previousFloorComplete = version == 2;
            initialized = true;
        } catch (RuntimeException ignored) { previous.clear(); }
    }

    /** Resume native searches without treating the first homepage response as a new baseline. */
    public SubscriptionHomepageScan(String checkpoint, List<GallerySeenState> cursors) {
        this(checkpoint);
        if (initialized) return;
        long oldest = Long.MAX_VALUE;
        for (GallerySeenState cursor : cursors) {
            if (cursor.initialized() && cursor.oldestMinute() > 0) {
                oldest = Math.min(oldest, cursor.oldestMinute());
            }
        }
        if (oldest == Long.MAX_VALUE) return;
        initialized = true;
        previousMinute = previousFloor = oldest;
        previousFloorComplete = true;
        // No homepage anchor exists yet. Require two old pages, as for a deleted anchor.
    }

    /** Also excludes duplicate candidates on later pages of the current pass. */
    public boolean isNew(GalleryInfo gallery) {
        long posted = GallerySeenState.postedMinute(gallery.posted);
        return initialized && gallery.gid > 0 && !previous.containsKey(gallery.gid)
                && !current.containsKey(gallery.gid)
                && (posted == 0 || posted > previousFloor
                || (previousFloorComplete && posted == previousFloor));
    }

    public boolean shouldContinue(List<GalleryInfo> rawPage, boolean hasMore) {
        if (finished) throw new IllegalStateException("Homepage pass already completed");
        int added = 0, afterAnchor = 0;
        boolean foundAnchor = false;
        boolean entirelyOlder = !rawPage.isEmpty();
        long tailMinute = 0;
        for (GalleryInfo gallery : rawPage) {
            if (gallery.gid <= 0) { entirelyOlder = false; continue; }
            long posted = GallerySeenState.postedMinute(gallery.posted);
            boolean unique = !current.containsKey(gallery.gid);
            current.merge(gallery.gid, posted, Math::max);
            if (unique) added++;
            if (head == 0 || posted > minute) { head = gallery.gid; minute = posted; }
            tailMinute = posted;
            entirelyOlder &= posted > 0 && posted < previousMinute;
            if (gallery.gid == previousHead) foundAnchor = true;
            else if (foundAnchor && unique) afterAnchor++;
            if (closingMinute) {
                if (posted <= 0 || posted >= floor) olderThanFloor = 0;
                else if (unique) olderThanFloor++;
            }
        }
        if (!rawPage.isEmpty() && added == 0)
            throw new IllegalStateException("Homepage pagination made no progress");
        if (tailMinute > 0) lastTailMinute = tailMinute;
        if (!hasMore || rawPage.isEmpty()) {
            if (!closingMinute) floor = lastTailMinute;
            finished = true;
            return false;
        }
        if (closingMinute) {
            if (olderThanFloor >= OVERLAP) { finished = true; return false; }
            return true;
        }
        boolean stop = !initialized || extraPage;
        if (!stop && foundAnchor) {
            boundary = true;
            extraPage = afterAnchor < OVERLAP;
            stop = !extraPage;
        } else if (!stop && !boundary) {
            olderPages = entirelyOlder ? olderPages + 1 : 0;
            // One reordered old page is not proof that the anchor disappeared.
            if (olderPages >= 2) {
                boundary = true;
                extraPage = added < OVERLAP;
                stop = !extraPage;
            }
        }
        if (!stop) return true;
        if (tailMinute <= 0) throw new IllegalStateException("Missing homepage boundary timestamp");
        floor = tailMinute;
        if (previousFloorComplete && floor >= previousFloor) {
            // Preserve the complete old minute, including known IDs beyond the current page.
            previous.forEach((gid, time) -> { if (time >= floor) current.putIfAbsent(gid, time); });
            finished = true;
            return false;
        }
        // Freeze this floor; lookahead must not move it to another partial minute.
        closingMinute = true;
        return true;
    }

    public String checkpoint() {
        if (!finished || head <= 0 || minute <= 0 || floor <= 0) return "";
        JSONObject state = new JSONObject(true);
        state.put("version", 2);
        state.put("head", head);
        state.put("minute", minute);
        state.put("floor", floor);
        JSONArray entries = new JSONArray();
        current.forEach((gid, time) -> {
            if (time == 0 || time >= floor) {
                JSONArray entry = new JSONArray();
                entry.add(gid); entry.add(time); entries.add(entry);
            }
        });
        state.put("entries", entries);
        return state.toJSONString();
    }
}
