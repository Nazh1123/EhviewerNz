package com.hippo.ehviewer.client;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.dao.QuickSearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compiles bookmarks once and indexes positive uploader/tag terms to avoid a full cross product. */
public final class SubscriptionHomepageMatcher {
    private record Rule(QuickSearch bookmark, ListUrlBuilder builder, List<String> terms,
                        GallerySeenState cursor) {}
    private final Map<String, List<Rule>> index = new HashMap<>();
    private final List<Rule> unindexed = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<QuickSearch> supported = new ArrayList<>();
    private final List<QuickSearch> fallback = new ArrayList<>();
    private final Map<QuickSearch, GallerySeenState> observed = new HashMap<>();

    public SubscriptionHomepageMatcher(List<QuickSearch> bookmarks) {
        this(bookmarks, java.util.Collections.emptyMap());
    }

    public SubscriptionHomepageMatcher(List<QuickSearch> bookmarks,
            Map<QuickSearch, GallerySeenState> cursors) {
        for (QuickSearch bookmark : bookmarks) {
            if (!bookmark.subscribed || !BookmarkSubscriptionPlanner.isSupported(bookmark)) continue;
            List<String> terms = BookmarkSubscriptionPlanner.homepageTerms(bookmark);
            if (terms == null) { fallback.add(bookmark); continue; }
            List<String> normalized = new ArrayList<>();
            for (String term : terms) normalized.add(normalize(term));
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.set(bookmark);
            Rule rule = new Rule(bookmark, builder, normalized, cursors.get(bookmark));
            rules.add(rule);
            String anchor = null;
            for (String term : normalized) {
                if (!term.startsWith("-")) {
                    anchor = term;
                    if (term.startsWith("uploader:")) break;
                }
            }
            if (anchor == null) unindexed.add(rule);
            else index.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(rule);
            supported.add(bookmark);
        }
    }

    public List<QuickSearch> fallbackBookmarks() { return new ArrayList<>(fallback); }
    public boolean isEmpty() { return supported.isEmpty(); }

    public String fingerprint() {
        List<String> keys = new ArrayList<>();
        for (QuickSearch bookmark : supported) {
            // Include every effective query option; order and names do not change semantics.
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.set(bookmark);
            keys.add(builder.buildWithoutRatingFilter() + ":" + builder.getSubscriptionMinRatingHundredths());
        }
        java.util.Collections.sort(keys);
        return String.join("\n", keys);
    }

    public boolean matches(GalleryInfo gallery) {
        return !matchingRules(gallery, true).isEmpty();
    }

    /** Only completed pages are observed; request retries must not mutate detection cursors. */
    public void observe(List<GalleryInfo> galleries) {
        for (GalleryInfo gallery : galleries) {
            for (Rule rule : matchingRules(gallery, false)) {
                observed.compute(rule.bookmark, (bookmark, state) ->
                        (state == null ? GallerySeenState.empty() : state).observe(List.of(gallery)));
            }
        }
    }

    public GallerySeenState observed(List<QuickSearch> bookmarks) {
        GallerySeenState state = GallerySeenState.empty();
        for (QuickSearch bookmark : bookmarks) {
            state = state.merge(observed.getOrDefault(bookmark, GallerySeenState.empty()));
        }
        return state;
    }

    private List<Rule> matchingRules(GalleryInfo gallery, boolean newOnly) {
        Set<String> values = new HashSet<>();
        if (gallery.uploader != null) values.add("uploader:" + gallery.uploader.toLowerCase(Locale.ROOT));
        if (gallery.simpleTags != null) for (String tag : gallery.simpleTags) values.add(normalize(tag));
        Set<Rule> candidates = new LinkedHashSet<>(unindexed);
        for (String value : values) candidates.addAll(index.getOrDefault(value, java.util.Collections.emptyList()));
        List<Rule> matches = new ArrayList<>();
        for (Rule rule : candidates) {
            if (newOnly && rule.cursor != null && !rule.cursor.isNew(gallery)) continue;
            ListUrlBuilder builder = rule.builder;
            if (builder.getMode() == ListUrlBuilder.MODE_NORMAL) {
                int categories = builder.getCategory() == EhUtils.NONE
                        ? Settings.getDefaultCategories() : builder.getCategory();
                if ((categories & gallery.category) == 0) continue;
                if (builder.getAdvanceSearch() != -1
                        && ((builder.getPageFrom() >= 0 && gallery.pages < builder.getPageFrom())
                        || (builder.getPageTo() >= 0 && gallery.pages > builder.getPageTo()))) continue;
            }
            int minimum = Math.max(Settings.getPersistentMinRatingHundredths(), builder.getSubscriptionMinRatingHundredths());
            if (minimum > 0 && !SearchRating.matches(gallery.rating, minimum)) continue;
            boolean match = true;
            for (String term : rule.terms) {
                boolean negative = term.startsWith("-");
                if (values.contains(negative ? term.substring(1) : term) == negative) { match = false; break; }
            }
            if (match) matches.add(rule);
        }
        return matches;
    }

    /** HTML tags can be incomplete and ratings rounded: reject only authoritative list fields. */
    public boolean mayMatch(GalleryInfo gallery) {
        for (Rule rule : rules) {
            if (rule.cursor != null && !rule.cursor.isNew(gallery)) continue;
            ListUrlBuilder builder = rule.builder;
            if (builder.getMode() == ListUrlBuilder.MODE_NORMAL) {
                int categories = builder.getCategory() == EhUtils.NONE
                        ? Settings.getDefaultCategories() : builder.getCategory();
                if (gallery.category > 0 && gallery.category != EhUtils.UNKNOWN
                        && (categories & gallery.category) == 0) continue;
                if (gallery.pages > 0 && builder.getAdvanceSearch() != -1
                        && ((builder.getPageFrom() >= 0 && gallery.pages < builder.getPageFrom())
                        || (builder.getPageTo() >= 0 && gallery.pages > builder.getPageTo()))) continue;
            }
            boolean possible = true;
            if (gallery.uploader != null && !gallery.uploader.isEmpty()) {
                String uploader = "uploader:" + gallery.uploader.toLowerCase(Locale.ROOT);
                for (String term : rule.terms) {
                    if (term.startsWith("uploader:") && !term.equals(uploader)) {
                        possible = false;
                        break;
                    }
                }
            }
            if (possible) return true;
        }
        return false;
    }

    private static String normalize(String term) {
        int colon = term.indexOf(':');
        if (colon < 0) return term.toLowerCase(Locale.ROOT);
        String field = term.substring(0, colon).toLowerCase(Locale.ROOT);
        boolean negative = field.startsWith("-");
        if (negative) field = field.substring(1);
        field = switch (field) {
            case "a" -> "artist";
            case "g", "circle" -> "group";
            case "l", "lang" -> "language";
            case "p", "series" -> "parody";
            case "c", "char" -> "character";
            case "f" -> "female";
            case "m" -> "male";
            case "x" -> "mixed";
            case "o" -> "other";
            case "r" -> "reclass";
            case "cos" -> "cosplayer";
            case "loc" -> "location";
            default -> field;
        };
        return (negative ? "-" : "") + field + term.substring(colon).toLowerCase(Locale.ROOT);
    }
}
