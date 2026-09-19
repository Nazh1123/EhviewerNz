package com.hippo.ehviewer.client;

import androidx.annotation.Nullable;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unions searches along one dimension only. Saved bookmarks are never rewritten. */
public final class BookmarkSubscriptionPlanner {
    private static final int MAX_QUERY_BYTES = 200;
    private static final Pattern TERM = Pattern.compile(
            "(-?)([a-z]+):\\s*(?:\"([^\"]+)\"(\\$)?|([^\\s\"]+))", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TAG_FIELDS = new HashSet<>(Arrays.asList(
            "a", "artist", "g", "group", "circle", "l", "lang", "language", "p", "parody",
            "series", "c", "char", "character", "f", "female", "m", "male", "x", "mixed",
            "o", "other", "r", "reclass", "cos", "cosplayer", "loc", "location", "tag"));
    private enum Axis { NONE, UPLOADER, TAG, CATEGORY }
    private record Term(String field, String value, boolean negative) implements Comparable<Term> {
        String wire() { return (negative ? "-" : "") + field + ":\"" + value + "\""; }
        Axis axis() { return negative ? Axis.NONE : field.equals("uploader")
                ? Axis.UPLOADER : TAG_FIELDS.contains(field) ? Axis.TAG : Axis.NONE; }
        @Override public int compareTo(Term other) { return wire().compareTo(other.wire()); }
    }
    private record Entry(QuickSearch bookmark, ListUrlBuilder builder, @Nullable List<Term> terms) {}
    private BookmarkSubscriptionPlanner() {}

    public static final class Source {
        private final List<Entry> entries = new ArrayList<>();
        private final ListUrlBuilder builder;
        private Axis axis = Axis.NONE;
        private List<Term> common;
        private final SortedSet<Term> alternatives = new TreeSet<>();
        private Source(Entry entry) {
            entries.add(entry);
            builder = entry.builder.clone();
            common = entry.terms;
        }
        public ListUrlBuilder createBuilder() { return builder.clone(); }
        public boolean isMergedUploaderSearch() { return axis == Axis.UPLOADER; }
        public boolean requiresVerification() { return axis == Axis.TAG; }
        public boolean isCombined() { return axis != Axis.NONE; }
        public String getCacheKey() { return axis + ":" + queryKey(builder, true); }
        public List<QuickSearch> getBookmarks() {
            List<QuickSearch> result = new ArrayList<>();
            for (Entry entry : entries) result.add(entry.bookmark);
            return result;
        }
        public List<QuickSearch> matchingBookmarks(@Nullable String uploader) {
            GalleryInfo gallery = new GalleryInfo();
            gallery.uploader = uploader;
            return matchingGalleryBookmarks(gallery);
        }
        public List<QuickSearch> matchingGalleryBookmarks(GalleryInfo gallery) {
            if (requiresVerification()) return Collections.emptyList();
            List<QuickSearch> matches = new ArrayList<>();
            for (Entry entry : entries) {
                if (axis == Axis.CATEGORY && !containsCategory(entry.builder, gallery.category)) continue;
                if (axis == Axis.UPLOADER) {
                    String expected = uploader(entry.terms);
                    if (expected == null || !expected.equalsIgnoreCase(gallery.uploader)) continue;
                }
                matches.add(entry.bookmark);
            }
            return matches;
        }
        private boolean add(Entry entry) {
            if (!queryKey(builder, false).equals(queryKey(entry.builder, false))) return false;
            boolean sameQuery = Objects.equals(builder.getKeyword(), entry.builder.getKeyword());
            if (axis == Axis.NONE && sameQuery && builder.getCategory() == entry.builder.getCategory()) {
                entries.add(entry);
                return true;
            }
            // Account-default categories cannot be converted into an explicit mask.
            if ((axis == Axis.NONE || axis == Axis.CATEGORY) && sameQuery
                    && explicitCategories(builder) && explicitCategories(entry.builder)) {
                builder.setCategory(builder.getCategory() | entry.builder.getCategory());
                entries.add(entry);
                axis = Axis.CATEGORY;
                return true;
            }
            if (axis == Axis.CATEGORY || common == null || entry.terms == null
                    || builder.getCategory() != entry.builder.getCategory()) return false;
            List<Term> newCommon = new ArrayList<>(common);
            List<Term> difference = new ArrayList<>(entry.terms);
            Axis newAxis = axis;
            SortedSet<Term> newAlternatives = new TreeSet<>(alternatives);
            if (axis == Axis.NONE) {
                newCommon.retainAll(entry.terms);
                List<Term> oldDifference = new ArrayList<>(common);
                oldDifference.removeAll(newCommon);
                difference.removeAll(newCommon);
                if (oldDifference.size() != 1 || difference.size() != 1) return false;
                Term old = oldDifference.get(0);
                newAxis = old.axis();
                if (newAxis == Axis.NONE || difference.get(0).axis() != newAxis) return false;
                newAlternatives.add(old);
            } else {
                if (!entry.terms.containsAll(common)) return false;
                difference.removeAll(common);
                if (difference.size() != 1 || difference.get(0).axis() != axis) return false;
            }
            newAlternatives.add(difference.get(0));
            long inclusions = newCommon.stream().filter(t -> !t.negative && !t.field.equals("uploader")).count();
            if (inclusions > (newAxis == Axis.TAG ? 4 : 5)) return false;
            List<String> words = new ArrayList<>();
            for (Term term : newCommon) words.add(term.wire());
            for (Term term : newAlternatives) words.add((newAxis == Axis.TAG ? "~" : "") + term.wire());
            String keyword = String.join(" ", words);
            if (keyword.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) return false;
            common = newCommon;
            axis = newAxis;
            alternatives.clear();
            alternatives.addAll(newAlternatives);
            builder.setKeyword(keyword);
            entries.add(entry);
            return true;
        }
    }
    public static boolean isSupported(@Nullable QuickSearch q) {
        return q != null && (q.mode == ListUrlBuilder.MODE_NORMAL || q.mode == ListUrlBuilder.MODE_UPLOADER
                || q.mode == ListUrlBuilder.MODE_TAG || q.mode == ListUrlBuilder.MODE_FILTER);
    }
    public static List<Source> plan(List<QuickSearch> bookmarks) {
        List<Entry> entries = new ArrayList<>();
        for (QuickSearch q : bookmarks) if (isSupported(q) && q.subscribed) entries.add(entry(q));
        entries.sort(Comparator.comparing((Entry e) -> queryKey(e.builder, true))
                .thenComparing(e -> String.valueOf(e.bookmark.id)));
        List<Source> sources = new ArrayList<>();
        for (Entry entry : entries) {
            boolean added = false;
            for (Source source : sources) if (source.add(entry)) { added = true; break; }
            if (!added) sources.add(new Source(entry));
        }
        sources.sort(Comparator.comparing(Source::getCacheKey));
        return sources;
    }
    private static Entry entry(QuickSearch q) {
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.set(q);
        builder.setPageIndex(0);
        String keyword = q.keyword;
        boolean convertible = q.mode == ListUrlBuilder.MODE_NORMAL || q.mode == ListUrlBuilder.MODE_FILTER;
        if (q.mode == ListUrlBuilder.MODE_UPLOADER && safeUploader(keyword)) {
            keyword = "uploader:\"" + keyword + "\"";
            convertible = true;
        } else if (q.mode == ListUrlBuilder.MODE_TAG && keyword != null) {
            int colon = keyword.indexOf(':');
            if (colon > 0 && TAG_FIELDS.contains(keyword.substring(0, colon).toLowerCase(Locale.ROOT))) {
                String value = keyword.substring(colon + 1);
                if (!value.contains("\"") && !value.endsWith("$")) {
                    keyword = keyword.substring(0, colon + 1) + "\"" + value + "$\"";
                    convertible = true;
                }
            }
        }
        List<Term> terms = convertible ? parse(keyword) : null;
        if (terms != null && q.mode != ListUrlBuilder.MODE_NORMAL) {
            builder.reset();
            builder.setMode(ListUrlBuilder.MODE_NORMAL);
        }
        if (terms != null) {
            List<String> words = new ArrayList<>();
            for (Term term : terms) words.add(term.wire());
            String normalized = String.join(" ", words);
            if (normalized.getBytes(StandardCharsets.UTF_8).length <= MAX_QUERY_BYTES) builder.setKeyword(normalized);
            else { builder.set(q); terms = null; }
        }
        if (builder.getMode() == ListUrlBuilder.MODE_NORMAL && builder.getAdvanceSearch() == -1) {
            builder.setMinRating(-1); builder.setPageFrom(-1); builder.setPageTo(-1);
        }
        return new Entry(q, builder, terms);
    }
    @Nullable private static List<Term> parse(@Nullable String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return null;
        String text = keyword.trim();
        List<Term> terms = new ArrayList<>();
        Matcher matcher = TERM.matcher(text);
        int end = 0, uploaders = 0, includes = 0, excludes = 0;
        while (matcher.find()) {
            if (!text.substring(end, matcher.start()).trim().isEmpty()
                    || (end > 0 && matcher.start() == end)) return null;
            String field = matcher.group(2).toLowerCase(Locale.ROOT);
            if (!TAG_FIELDS.contains(field) && !field.equals("uploader") && !field.equals("title")) return null;
            String value = matcher.group(3) != null ? matcher.group(3) : matcher.group(5);
            if (matcher.group(4) != null && !value.endsWith("$")) value += "$";
            boolean negative = !matcher.group(1).isEmpty();
            if (field.equals("uploader")) {
                if (negative || !safeUploader(value) || ++uploaders > 1) return null;
                value = value.toLowerCase(Locale.ROOT);
            }
            for (char c : value.toCharArray())
                if (Character.isISOControl(c) || "\\~*%()".indexOf(c) >= 0) return null;
            Term term = new Term(field, value, negative);
            if (!terms.contains(term)) terms.add(term);
            if (negative) excludes++; else if (!field.equals("uploader")) includes++;
            end = matcher.end();
        }
        if (end != text.length() || includes > 5 || excludes > 10) return null;
        Collections.sort(terms);
        return terms;
    }
    private static boolean safeUploader(@Nullable String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (char c : value.toCharArray()) if (Character.isISOControl(c) || "\"\\~*$%:".indexOf(c) >= 0) return false;
        return ("uploader:\"" + value + "\"").getBytes(StandardCharsets.UTF_8).length <= MAX_QUERY_BYTES;
    }
    @Nullable static String getUploader(QuickSearch q) { return uploader(entry(q).terms); }
    @Nullable private static String uploader(@Nullable List<Term> terms) {
        if (terms != null) for (Term term : terms) if (term.field.equals("uploader")) return term.value;
        return null;
    }
    private static boolean explicitCategories(ListUrlBuilder b) {
        return b.getMode() == ListUrlBuilder.MODE_NORMAL && b.getCategory() > 0;
    }
    private static boolean containsCategory(ListUrlBuilder b, int category) {
        return b.getCategory() == EhUtils.NONE || (b.getCategory() & category) != 0;
    }
    private static String queryKey(ListUrlBuilder b, boolean includeQuery) {
        String filters = b.getMode() + ":" + b.getAdvanceSearch() + ":" + b.getMinRating()
                + ":" + b.getPageFrom() + ":" + b.getPageTo();
        String keyword = b.getKeyword();
        return includeQuery ? filters + ":" + b.getCategory() + ":"
                + (keyword == null ? -1 : keyword.length()) + ":" + keyword : filters;
    }
}
