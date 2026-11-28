package com.hippo.ehviewer.download;

import com.hippo.ehviewer.spider.SpiderInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A single update of a target gid. Page indexes are zero based, including deleted old pages. */
public final class GalleryUpdateRecord {
    public final long targetGid;
    public final long sourceGid;
    public final long firstGid;
    public final String targetToken;
    // The target snapshot survives deletion of old download directories.
    private final String[] targetTokens;
    public final long completedAt;
    public final int oldPages;
    public final int newPages;
    public final boolean complete;
    private final boolean additionsKnown;
    private final boolean localCoverage;
    public final int[] addedPages;
    public final int[] deletedPages;
    public final List<SourceDeletion> sourceDeletions;
    public final int readingPage;
    public final String sourceTitle;
    public final String errorReason;
    public final long[] retainedParentGids;

    public record SourceDeletion(long gid, int totalPages, int comparisonPages, boolean partialRange,
                                 boolean complete, int[] pages) {
        public SourceDeletion { pages = pages.clone(); }
        @Override public int[] pages() { return pages.clone(); }
    }

    record Source(long gid, int totalPages, GalleryPageRange range, SpiderInfo info) {}

    GalleryUpdateRecord(long targetGid, long sourceGid, long completedAt,
                        int oldPages, int newPages, boolean complete,
                        int[] addedPages, int[] deletedPages, int readingPage) {
        this(targetGid, sourceGid, completedAt, oldPages, newPages, complete,
                addedPages, deletedPages, readingPage, "", "", new long[0], 0, new String[0], "", List.of(), complete, false);
    }

    private GalleryUpdateRecord(long targetGid, long sourceGid, long completedAt,
                                int oldPages, int newPages, boolean complete,
                                int[] addedPages, int[] deletedPages, int readingPage,
                                String sourceTitle, String errorReason, long[] retainedParentGids,
                                long firstGid, String[] targetTokens, String targetToken,
                                List<SourceDeletion> sourceDeletions, boolean additionsKnown, boolean localCoverage) {
        this.targetGid = targetGid;
        this.sourceGid = sourceGid;
        this.firstGid = Math.max(0, firstGid);
        this.targetToken = targetToken != null ? targetToken : "";
        this.targetTokens = targetTokens.clone();
        this.completedAt = completedAt;
        this.oldPages = oldPages;
        this.newPages = newPages;
        this.complete = complete;
        this.additionsKnown = complete && additionsKnown;
        this.localCoverage = localCoverage;
        this.addedPages = addedPages.clone();
        this.deletedPages = deletedPages.clone();
        this.sourceDeletions = List.copyOf(sourceDeletions);
        this.readingPage = Math.max(0, Math.min(readingPage, addedPages.length - 1));
        this.sourceTitle = sourceTitle != null ? sourceTitle : "";
        this.errorReason = errorReason;
        this.retainedParentGids = retainedParentGids.clone();
    }

    public boolean isFailure() { return !errorReason.isEmpty(); }

    public boolean hasTokenSnapshot() {
        return targetTokens.length > 0 && targetTokens.length == newPages;
    }

    public boolean hasKnownAdditions() {
        return additionsKnown;
    }

    public boolean showsLocalCoverage() { return localCoverage; }

    public boolean hasKnownDeletions() {
        if (!localCoverage) return complete;
        return sourceDeletions.stream().anyMatch(source -> source.gid == sourceGid && source.complete);
    }

    GalleryUpdateRecord withLocalCoverage(Set<String> coveredTokens) {
        return new GalleryUpdateRecord(targetGid, sourceGid, completedAt, oldPages, newPages,
                hasTokenSnapshot(), uncoveredPages(targetTokens, coveredTokens), deletedPages,
                readingPage, sourceTitle, errorReason,
                retainedParentGids, firstGid, targetTokens, targetToken, sourceDeletions, hasTokenSnapshot(), true);
    }

    private static int[] uncoveredPages(String[] tokens, Set<String> coveredTokens) {
        return java.util.stream.IntStream.range(0, tokens.length)
                .filter(page -> !coveredTokens.contains(tokens[page])).toArray();
    }

    public static GalleryUpdateRecord failure(long targetGid, long sourceGid, long failedAt,
                                               String sourceTitle, String reason, long[] retained) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Missing failure reason");
        return new GalleryUpdateRecord(targetGid, sourceGid, failedAt, 0, 0, false,
                new int[0], new int[0], 0, sourceTitle, reason, retained, 0, new String[0], "", List.of(), false, false);
    }

    public GalleryUpdateRecord withFirstGid(long firstGid) {
        return new GalleryUpdateRecord(targetGid, sourceGid, completedAt, oldPages, newPages,
                complete, addedPages, deletedPages, readingPage, sourceTitle, errorReason,
                retainedParentGids, firstGid, targetTokens, targetToken, sourceDeletions, additionsKnown, localCoverage);
    }

    public GalleryUpdateRecord withTargetToken(String token) {
        return new GalleryUpdateRecord(targetGid, sourceGid, completedAt, oldPages, newPages,
                complete, addedPages, deletedPages, readingPage, sourceTitle, errorReason,
                retainedParentGids, firstGid, targetTokens, token, sourceDeletions, additionsKnown, localCoverage);
    }

    /** A legacy snapshot can resolve the gallery token through its first page. */
    public String getFirstPageToken() {
        return targetTokens.length > 0 ? targetTokens[0] : "";
    }

    static GalleryUpdateRecord compare(long targetGid, long sourceGid,
                                       SpiderInfo source, SpiderInfo target) {
        GalleryUpdateRecord record = compareSources(targetGid, sourceGid,
                List.of(new Source(sourceGid, 0, null, source)), target);
        return new GalleryUpdateRecord(targetGid, sourceGid, 0, record.oldPages, record.newPages,
                record.complete, record.addedPages, record.deletedPages, 0,
                "", "", new long[0], 0, record.complete ? record.targetTokens : new String[0],
                record.targetToken, List.of(), record.additionsKnown, false);
    }

    static GalleryUpdateRecord compareSources(long targetGid, long sourceGid,
                                               List<Source> sources, SpiderInfo target) {
        return compareSources(targetGid, sourceGid, sources, target, null);
    }

    static GalleryUpdateRecord compareSources(long targetGid, long sourceGid, List<Source> sources,
                                               SpiderInfo target, Set<String> coveredTokens) {
        int newPages = target != null ? Math.max(0, target.pages) : 0;
        boolean targetComplete = hasAllTokens(target);
        String[] tokens = new String[targetComplete ? newPages : 0];
        HashMap<String, Integer> targetCounts = new HashMap<>();
        for (int page = 0; page < tokens.length; page++) {
            tokens[page] = target.pTokenMap.get(page);
            targetCounts.merge(tokens[page], 1, Integer::sum);
        }
        int oldPages = 0;
        int[] added = new int[0], deleted = new int[0];
        boolean complete = false;
        boolean additionsKnown = false;
        List<SourceDeletion> groups = new ArrayList<>();
        for (Source source : sources) {
            SpiderInfo info = source.info;
            GalleryPageRange range = source.range;
            int total = range != null ? range.totalPages
                    : info != null && info.pages > 0 ? info.pages : Math.max(0, source.totalPages);
            int comparisonPages = range != null ? range.size() : total;
            boolean partialRange = comparisonPages < total;
            boolean sourceComplete = targetComplete && hasTokens(info, range);
            Map<String, Integer> counts = new HashMap<>();
            ArrayList<Integer> removed = new ArrayList<>();
            if (sourceComplete) {
                int[] scopedPages = range != null ? range.pages() : null;
                int size = scopedPages != null ? scopedPages.length : total;
                for (int i = 0; i < size; i++) {
                    int page = scopedPages != null ? scopedPages[i] : i;
                    String token = info.pTokenMap.get(page);
                    if (counts.merge(token, 1, Integer::sum) > targetCounts.getOrDefault(token, 0)) {
                        removed.add(page);
                    }
                }
            }
            int[] removedPages = removed.stream().mapToInt(Integer::intValue).toArray();
            groups.add(new SourceDeletion(source.gid, total, comparisonPages, partialRange, sourceComplete, removedPages));
            if (source.gid == sourceGid) {
                complete = sourceComplete;
                oldPages = total;
                deleted = removedPages;
                additionsKnown = coveredTokens == null && sourceComplete && (!partialRange || hasAllTokens(info));
                if (additionsKnown) {
                    // Deletion counts above cover the selected pages. Additions need all old occurrences.
                    if (partialRange) {
                        counts.clear();
                        for (int page = 0; page < total; page++) {
                            counts.merge(info.pTokenMap.get(page), 1, Integer::sum);
                        }
                    }
                    ArrayList<Integer> additions = new ArrayList<>();
                    for (int page = 0; page < tokens.length; page++) {
                        if (counts.merge(tokens[page], -1, Integer::sum) < 0) additions.add(page);
                    }
                    added = additions.stream().mapToInt(Integer::intValue).toArray();
                }
            }
        }
        boolean localCoverage = coveredTokens != null;
        if (localCoverage) {
            added = uncoveredPages(tokens, coveredTokens);
            complete = targetComplete;
            additionsKnown = targetComplete;
        }
        return new GalleryUpdateRecord(targetGid, sourceGid, 0, oldPages, newPages, complete,
                added, deleted, 0, "", "", new long[0], 0, tokens,
                target != null ? target.token : "", groups, additionsKnown, localCoverage);
    }

    /** Map historical additions to the current version, preserving duplicate-token counts. */
    public int[] resolveAddedPages(SpiderInfo current) {
        return Arrays.stream(resolveAddedPageMap(current)).filter(page -> page >= 0).sorted().toArray();
    }

    /** One current page per original addition, or -1 if it no longer exists. */
    public int[] resolveAddedPageMap(SpiderInfo current) {
        int[] result = new int[addedPages.length];
        Arrays.fill(result, -1);
        if (!hasKnownAdditions() || isFailure() || !hasAllTokens(current)) return result;
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

    /** Merge checked page identities without replacing the reader's position or other metadata. */
    public SpiderInfo seedDownload(SpiderInfo local) {
        if (!hasTokenSnapshot() || (local != null
                && (local.gid != targetGid || !targetToken.equals(local.token) || local.pages != newPages))) return local;
        SpiderInfo info = local != null ? local : new SpiderInfo();
        info.gid = targetGid;
        info.token = targetToken;
        info.pages = newPages;
        if (info.pTokenMap == null) info.pTokenMap = new android.util.SparseArray<>(newPages);
        for (int i = 0; i < newPages; i++) {
            String token = info.pTokenMap.get(i);
            if (token == null || token.isEmpty() || "failed".equals(token)) info.pTokenMap.put(i, targetTokens[i]);
        }
        return info;
    }

    static boolean hasAllTokens(SpiderInfo info) {
        return hasTokens(info, null);
    }

    static boolean hasTokens(SpiderInfo info, GalleryPageRange range) {
        if (info == null || info.pages <= 0 || info.pTokenMap == null) return false;
        if (range != null && range.totalPages != info.pages) return false;
        int[] pages = range != null ? range.pages() : null;
        int size = pages != null ? pages.length : info.pages;
        for (int i = 0; i < size; i++) {
            if (!usableToken(info.pTokenMap.get(pages != null ? pages[i] : i))) return false;
        }
        return true;
    }

    static boolean usableToken(String token) {
        return token != null && !token.isEmpty() && !"failed".equals(token);
    }

    boolean matchesTarget(SpiderInfo target) {
        if (!hasTokenSnapshot() || target == null || target.gid != targetGid
                || !targetToken.equals(target.token) || target.pages != newPages
                || target.pTokenMap == null) return false;
        for (int i = 0; i < targetTokens.length; i++) {
            if (!targetTokens[i].equals(target.pTokenMap.get(i))) return false;
        }
        return true;
    }

    String toJson() throws JSONException {
        return toPayload().toString();
    }

    JSONObject toPayload() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("first_gid", firstGid);
        json.put("target_token", targetToken);
        JSONArray tokens = new JSONArray();
        for (String token : targetTokens) tokens.put(token);
        json.put("target_tokens", tokens);
        json.put("old_pages", oldPages);
        json.put("new_pages", newPages);
        json.put("complete", complete);
        json.put("added", array(addedPages));
        if (complete && !hasKnownAdditions()) json.put("additions_known", false);
        if (localCoverage) {
            json.put("local_coverage", true);
            if (!hasKnownDeletions()) json.put("deletions_known", false);
        }
        json.put("deleted", array(deletedPages));
        if (!sourceDeletions.isEmpty()) {
            JSONArray sources = new JSONArray();
            for (SourceDeletion source : sourceDeletions) {
                JSONObject group = new JSONObject().put("gid", source.gid).put("total", source.totalPages)
                        .put("range", source.partialRange).put("complete", source.complete)
                        .put("pages", array(source.pages));
                if (source.comparisonPages >= 0) group.put("comparison_pages", source.comparisonPages);
                sources.put(group);
            }
            json.put("source_deletions", sources);
        }
        json.put("source_title", sourceTitle);
        json.put("error_reason", errorReason);
        JSONArray retained = new JSONArray();
        for (long gid : retainedParentGids) retained.put(gid);
        json.put("retained_parents", retained);
        return json;
    }

    static GalleryUpdateRecord fromJson(long targetGid, long sourceGid, long completedAt,
                                        int readingPage, String raw) throws JSONException {
        return fromPayload(targetGid, sourceGid, completedAt, readingPage, new JSONObject(raw));
    }

    static GalleryUpdateRecord fromPayload(long targetGid, long sourceGid, long completedAt,
                                           int readingPage, JSONObject json) throws JSONException {
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
        JSONArray sources = json.optJSONArray("source_deletions");
        List<SourceDeletion> groups = new ArrayList<>();
        if (sources != null) {
            HashSet<Long> gids = new HashSet<>();
            for (int i = 0; i < sources.length(); i++) {
                JSONObject group = sources.getJSONObject(i);
                long gid = group.getLong("gid");
                int total = group.getInt("total");
                boolean partialRange = group.getBoolean("range");
                int comparisonPages = group.optInt("comparison_pages", partialRange ? -1 : total);
                if (gid <= 0 || gid >= targetGid || total < 0 || comparisonPages < -1 || comparisonPages > total
                        || !gids.add(gid))
                    throw new JSONException("Invalid source gallery");
                groups.add(new SourceDeletion(gid, total, comparisonPages, partialRange,
                        group.getBoolean("complete"), indexes(group.getJSONArray("pages"), total)));
            }
        }
        return new GalleryUpdateRecord(targetGid, sourceGid, completedAt, oldPages, newPages,
                json.getBoolean("complete"), indexes(json.getJSONArray("added"), newPages),
                indexes(json.getJSONArray("deleted"), oldPages), readingPage,
                json.optString("source_title", ""), json.optString("error_reason", ""), retainedGids,
                json.optLong("first_gid", 0), targetTokens, json.optString("target_token", ""), groups,
                json.optBoolean("additions_known", true), json.optBoolean("local_coverage"));
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
