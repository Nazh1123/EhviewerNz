package com.hippo.ehviewer.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import com.hippo.ehviewer.client.SearchQueryTokenizer.Term;

import static com.hippo.ehviewer.client.SearchQueryTokenizer.terms;

/** Controls one namespaced tag without changing unrelated search terms. */
public final class SearchPresetQuery {
    public static final int EXCLUDE = -1;
    public static final int NORMAL = 0;
    public static final int INCLUDE = 1;
    public static final int OR = 2;

    private SearchPresetQuery() {}

    public static boolean isValid(@Nullable String preset) {
        return preset != null && parse(preset.trim()) != null;
    }

    /** Database suggestions contain unquoted English values, which may include spaces. */
    @Nullable
    public static String formatSuggestion(@NonNull String english) {
        int colon = english.indexOf(':');
        if (colon <= 0) return null;
        String namespace = SearchQueryTokenizer.canonicalNamespace(english.substring(0, colon));
        // Namespace headings and plain-text dictionary entries are not preset tag expressions.
        if ("rows".equals(namespace) || "misc".equals(namespace)) return null;
        String value = english.substring(colon + 1).trim();
        String prefix = EhTagDatabase.namespaceToPrefix(namespace);
        if (prefix == null || prefix.isEmpty()) prefix = namespace + ":";
        String query = prefix + "\"" + value + "$\"";
        return isValid(query) ? query : null;
    }

    /** Returns namespace:value with aliases, quotes and the exact-match suffix normalized. */
    @Nullable
    public static String identity(@NonNull String preset) {
        return parse(preset.trim());
    }

    @Nullable
    private static String parse(String token) {
        if (token.isEmpty() || "-+~".indexOf(token.charAt(0)) >= 0) return null;
        SearchQueryTokenizer.Tag tag = SearchQueryTokenizer.parseTag(token, true);
        return tag == null ? null : tag.identity();
    }

    public static int getState(@Nullable String query, @NonNull String preset) {
        String identity = identity(preset);
        if (identity == null || query == null) return NORMAL;
        int state = NORMAL;
        for (Term term : terms(query)) {
            String token = query.substring(term.start(), term.end());
            boolean excluded = token.startsWith("-");
            boolean or = token.startsWith("~");
            if (excluded || or || token.startsWith("+")) token = token.substring(1);
            // If both signs were entered manually, the last occurrence controls the button.
            if (identity.equals(parse(token))) state = excluded ? EXCLUDE : or ? OR : INCLUDE;
        }
        return state;
    }

    @NonNull
    public static String setState(@Nullable String query, @NonNull String preset, int state) {
        String source = query == null ? "" : query;
        String identity = identity(preset);
        if (identity == null) return source;
        List<Term> terms = terms(source);
        StringBuilder result = new StringBuilder();
        int previous = -1;
        boolean removed = false;
        boolean commaSeparated = hasCommaSeparator(source);
        for (int i = 0; i < terms.size(); i++) {
            Term term = terms.get(i);
            String token = source.substring(term.start(), term.end());
            String unsigned = token.startsWith("-") || token.startsWith("+") || token.startsWith("~")
                    ? token.substring(1) : token;
            if (identity.equals(parse(unsigned))) {
                removed = true;
                continue;
            }
            if (previous >= 0) {
                result.append(previous == i - 1
                        ? source.substring(terms.get(previous).end(), term.start())
                        : commaSeparated ? ", " : " ");
            }
            result.append(token);
            previous = i;
        }
        String updated = removed ? result.toString() : source.trim();
        if (state == NORMAL) return removed ? updated : source;
        String token = (state == EXCLUDE ? "-" : state == OR ? "~" : "") + preset.trim();
        return updated.isEmpty() ? token : updated + (commaSeparated ? ", " : " ") + token;
    }

    private static boolean hasCommaSeparator(String query) {
        boolean quoted = false;
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return true;
        }
        return false;
    }

}
