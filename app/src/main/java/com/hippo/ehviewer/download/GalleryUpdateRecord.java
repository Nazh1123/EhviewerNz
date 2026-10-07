package com.hippo.ehviewer.download;

import com.hippo.ehviewer.spider.SpiderInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;

/** A single update of a target gid. Page indexes are zero based, including deleted old pages. */
public final class GalleryUpdateRecord {
    public final long targetGid;
    public final long sourceGid;
    public final long firstGid;
    // The target snapshot survives deletion of old download directories.
    private final String[] targetTokens;
    public final long completedAt;
    public final int oldPages;
    public final int newPages;
    public final boolean complete;
    public final int[] addedPages;
    public final int[] deletedPages;
    public final int readingPage;
    public final String sourceTitle;
    public final String errorReason;
    public final long[] retainedParentGids;

    GalleryUpdateRecord(long targetGid, long sourceGid, long completedAt,
                        int oldPages, int newPages, boolean complete,
                        int[] addedPages, int[] deletedPages, int readingPage) {
        this(targetGid, sourceGid, completedAt, oldPages, newPages, complete,
                addedPages, deletedPages, readingPage, "", "", new long[0], 0, new String[0]);
    }

    private GalleryUpdateRecord(long targetGid, long sourceGid, long completedAt,
                                int oldPages, int newPages, boolean complete,
                                int[] addedPages, int[] deletedPages, int readingPage,
                                String sourceTitle, String errorReason, long[] retainedParentGids,
                                long firstGid, String[] targetTokens) {
        this.targetGid = targetGid;
        this.sourceGid = sourceGid;
        this.firstGid = Math.max(0, firstGid);
        this.targetTokens = targetTokens.clone();
        this.completedAt = completedAt;
        this.oldPages = oldPages;
        this.newPages = newPages;
        this.complete = complete;
        this.addedPages = addedPages.clone();
        this.deletedPages = deletedPages.clone();
        this.readingPage = Math.max(0, Math.min(readingPage, addedPages.length - 1));
        this.sourceTitle = sourceTitle != null ? sourceTitle : "";
        this.errorReason = errorReason;
        this.retainedParentGids = retainedParentGids.clone();
    }

    public boolean isFailure() { return !errorReason.isEmpty(); }

    public boolean hasTokenSnapshot() {
        return targetTokens.length > 0 && targetTokens.length == newPages;
    }

    public static GalleryUpdateRecord failure(long targetGid, long sourceGid, long failedAt,
                                               String sourceTitle, String reason, long[] retained) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Missing failure reason");
        return new GalleryUpdateRecord(targetGid, sourceGid, failedAt, 0, 0, false,
                new int[0], new int[0], 0, sourceTitle, reason, retained, 0, new String[0]);
    }

    public GalleryUpdateRecord withFirstGid(long firstGid) {
        return new GalleryUpdateRecord(targetGid, sourceGid, completedAt, oldPages, newPages,
                complete, addedPages, deletedPages, readingPage, sourceTitle, errorReason,
                retainedParentGids, firstGid, targetTokens);
    }

    static GalleryUpdateRecord compare(long targetGid, long sourceGid,
                                       SpiderInfo source, SpiderInfo target) {
        int oldPages = source != null ? Math.max(0, source.pages) : 0;
        int newPages = target != null ? Math.max(0, target.pages) : 0;
        boolean complete = hasAllTokens(source) && hasAllTokens(target);
        if (!complete) {
            return new GalleryUpdateRecord(targetGid, sourceGid, 0, oldPages, newPages,
                    false, new int[0], new int[0], 0);
        }
        HashMap<String, ArrayDeque<Integer>> oldIndexes = new HashMap<>();
        for (int page = 0; page < oldPages; page++) {
            oldIndexes.computeIfAbsent(source.pTokenMap.get(page), key -> new ArrayDeque<>())
                    .add(page);
        }
        boolean[] matched = new boolean[oldPages];
        ArrayList<Integer> added = new ArrayList<>();
        for (int page = 0; page < newPages; page++) {
            ArrayDeque<Integer> matches = oldIndexes.get(target.pTokenMap.get(page));
            if (matches == null || matches.isEmpty()) added.add(page);
            else matched[matches.removeFirst()] = true;
        }
        ArrayList<Integer> deleted = new ArrayList<>();
        for (int page = 0; page < oldPages; page++) {
            if (!matched[page]) deleted.add(page);
        }
        String[] tokens = new String[newPages];
        for (int page = 0; page < newPages; page++) tokens[page] = target.pTokenMap.get(page);
        return new GalleryUpdateRecord(targetGid, sourceGid, 0, oldPages, newPages, true,
                added.stream().mapToInt(Integer::intValue).toArray(),
                deleted.stream().mapToInt(Integer::intValue).toArray(), 0,
                "", "", new long[0], 0, tokens);
    }

    /** Map historical additions to the current version, preserving duplicate-token counts. */
    public int[] resolveAddedPages(SpiderInfo current) {
        return Arrays.stream(resolveAddedPageMap(current)).filter(page -> page >= 0).sorted().toArray();
    }

    /** One current page per original addition, or -1 if it no longer exists. */
    public int[] resolveAddedPageMap(SpiderInfo current) {
        int[] result = new int[addedPages.length];
        Arrays.fill(result, -1);
        if (!complete || isFailure() || !hasAllTokens(current)) return result;
        if (targetTokens.length == 0) {
            // Legacy records have no identity snapshot: only their original version is safe.
            return current.gid == targetGid && current.pages == newPages
                    ? addedPages.clone() : result;
        }
        HashMap<String, ArrayDeque<Integer>> indexes = new HashMap<>();
        for (int page = 0; page < current.pages; page++) {
            indexes.computeIfAbsent(current.pTokenMap.get(page), key -> new ArrayDeque<>()).add(page);
        }
        int addedIndex = 0;
        for (int page = 0; page < targetTokens.length; page++) {
            ArrayDeque<Integer> matches = indexes.get(targetTokens[page]);
            Integer match = matches != null ? matches.pollFirst() : null;
            if (addedIndex < addedPages.length && addedPages[addedIndex] == page) {
                if (match != null) result[addedIndex] = match;
                addedIndex++;
            }
        }
        return result;
    }

    private static boolean hasAllTokens(SpiderInfo info) {
        if (info == null || info.pages <= 0 || info.pTokenMap == null) return false;
        for (int page = 0; page < info.pages; page++) {
            String token = info.pTokenMap.get(page);
            if (token == null || token.isEmpty() || "failed".equals(token)) {
                return false;
            }
        }
        return true;
    }

    String toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("first_gid", firstGid);
        JSONArray tokens = new JSONArray();
        for (String token : targetTokens) tokens.put(token);
        json.put("target_tokens", tokens);
        json.put("old_pages", oldPages);
        json.put("new_pages", newPages);
        json.put("complete", complete);
        json.put("added", array(addedPages));
        json.put("deleted", array(deletedPages));
        json.put("source_title", sourceTitle);
        json.put("error_reason", errorReason);
        JSONArray retained = new JSONArray();
        for (long gid : retainedParentGids) retained.put(gid);
        json.put("retained_parents", retained);
        return json.toString();
    }

    static GalleryUpdateRecord fromJson(long targetGid, long sourceGid, long completedAt,
                                        int readingPage, String raw) throws JSONException {
        JSONObject json = new JSONObject(raw);
        int oldPages = json.getInt("old_pages"), newPages = json.getInt("new_pages");
        if (oldPages < 0 || newPages < 0) throw new JSONException("Invalid page count");
        JSONArray retained = json.optJSONArray("retained_parents");
        long[] retainedGids = new long[retained != null ? retained.length() : 0];
        for (int i = 0; i < retainedGids.length; i++) retainedGids[i] = retained.getLong(i);
        JSONArray tokens = json.optJSONArray("target_tokens");
        String[] targetTokens = new String[tokens != null ? tokens.length() : 0];
        if (targetTokens.length != 0 && targetTokens.length != newPages)
            throw new JSONException("Invalid token snapshot length");
        for (int i = 0; i < targetTokens.length; i++) {
            targetTokens[i] = tokens.getString(i);
            if (targetTokens[i].isEmpty() || "failed".equals(targetTokens[i]))
                throw new JSONException("Invalid token snapshot");
        }
        return new GalleryUpdateRecord(targetGid, sourceGid, completedAt, oldPages, newPages,
                json.getBoolean("complete"), indexes(json.getJSONArray("added"), newPages),
                indexes(json.getJSONArray("deleted"), oldPages), readingPage,
                json.optString("source_title", ""), json.optString("error_reason", ""), retainedGids,
                json.optLong("first_gid", 0), targetTokens);
    }

    private static JSONArray array(int[] pages) {
        JSONArray array = new JSONArray();
        for (int page : pages) array.put(page);
        return array;
    }

    private static int[] indexes(JSONArray array, int total) throws JSONException {
        int[] pages = new int[array.length()];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = array.getInt(i);
            if (pages[i] < 0 || pages[i] >= total || (i > 0 && pages[i] <= pages[i - 1])) {
                throw new JSONException("Invalid page index");
            }
        }
        return pages;
    }

    public static String formatPages(int[] pages) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pages.length; i++) {
            int start = pages[i], end = start;
            while (i + 1 < pages.length && pages[i + 1] == end + 1) end = pages[++i];
            if (text.length() > 0) text.append(", ");
            text.append('p').append(start + 1);
            if (end != start) text.append('–').append(end + 1);
        }
        return text.toString();
    }
}
