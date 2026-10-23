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
    private record Rule(ListUrlBuilder builder, List<String> terms) {}
    private final Map<String, List<Rule>> index = new HashMap<>();
    private final List<Rule> unindexed = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<QuickSearch> supported = new ArrayList<>();
    private final List<QuickSearch> fallback = new ArrayList<>();

    public SubscriptionHomepageMatcher(List<QuickSearch> bookmarks) {
        for (QuickSearch bookmark : bookmarks) {
            if (!bookmark.subscribed || !BookmarkSubscriptionPlanner.isSupported(bookmark)) continue;
            List<String> terms = BookmarkSubscriptionPlanner.homepageTerms(bookmark);
            if (terms == null) { fallback.add(bookmark); continue; }
            List<String> normalized = new ArrayList<>();
            for (String term : terms) normalized.add(normalize(term));
            ListUrlBuilder builder = new ListUrlBuilder();
            builder.set(bookmark);
            Rule rule = new Rule(builder, normalized);
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
        Set<String> values = new HashSet<>();
        if (gallery.uploader != null) values.add("uploader:" + gallery.uploader.toLowerCase(Locale.ROOT));
        if (gallery.simpleTags != null) for (String tag : gallery.simpleTags) values.add(normalize(tag));
        Set<Rule> candidates = new LinkedHashSet<>(unindexed);
        for (String value : values) candidates.addAll(index.getOrDefault(value, java.util.Collections.emptyList()));
        for (Rule rule : candidates) {
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
            if (match) return true;
        }
        return false;
    }

    /** HTML tags can be incomplete and ratings rounded: reject only authoritative list fields. */
    public boolean mayMatch(GalleryInfo gallery) {
        for (Rule rule : rules) {
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
