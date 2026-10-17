package com.hippo.ehviewer.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Controls one namespaced tag without changing unrelated search terms. */
public final class SearchPresetQuery {
    public static final int EXCLUDE = -1;
    public static final int NORMAL = 0;
    public static final int INCLUDE = 1;

    private static final Pattern TAG = Pattern.compile(
            "([^-+~\\s:,\"]+):(?:\"([^\"\\r\\n]+)\"|([^\\s,\"]+))");

    private SearchPresetQuery() {}

    public static boolean isValid(@Nullable String preset) {
        return preset != null && parse(preset.trim()) != null;
    }

    /** Returns namespace:value with aliases, quotes and the exact-match suffix normalized. */
    @Nullable
    public static String identity(@NonNull String preset) {
        return parse(preset.trim());
    }

    @Nullable
    private static String parse(String token) {
        Matcher matcher = TAG.matcher(token);
        if (!matcher.matches()) return null;
        String namespace = matcher.group(1).toLowerCase(Locale.ROOT);
        namespace = switch (namespace) {
            case "l" -> "language";
            case "o" -> "other";
            case "f" -> "female";
            case "m" -> "male";
            case "a" -> "artist";
            case "c" -> "character";
            case "g" -> "group";
            case "p" -> "parody";
            case "r" -> "reclass";
            default -> namespace;
        };
        String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (value.endsWith("$")) value = value.substring(0, value.length() - 1);
        if (value.trim().isEmpty()) return null;
        return namespace + ":" + value.toLowerCase(Locale.ROOT);
    }

    public static int getState(@Nullable String query, @NonNull String preset) {
        String identity = identity(preset);
        if (identity == null || query == null) return NORMAL;
        int state = NORMAL;
        for (Term term : terms(query)) {
            String token = query.substring(term.start, term.end);
            boolean excluded = token.startsWith("-");
            if (excluded || token.startsWith("+")) token = token.substring(1);
            // If both signs were entered manually, the last occurrence controls the button.
            if (identity.equals(parse(token))) state = excluded ? EXCLUDE : INCLUDE;
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
            String token = source.substring(term.start, term.end);
            String unsigned = token.startsWith("-") || token.startsWith("+")
                    ? token.substring(1) : token;
            if (identity.equals(parse(unsigned))) {
                removed = true;
                continue;
            }
            if (previous >= 0) {
                result.append(previous == i - 1
                        ? source.substring(terms.get(previous).end, term.start)
                        : commaSeparated ? ", " : " ");
            }
            result.append(token);
            previous = i;
        }
        String updated = removed ? result.toString() : source.trim();
        if (state == NORMAL) return removed ? updated : source;
        String token = (state == EXCLUDE ? "-" : "") + preset.trim();
        return updated.isEmpty() ? token : updated + (commaSeparated ? ", " : " ") + token;
    }

    private static List<Term> terms(String query) {
        List<Term> result = new ArrayList<>();
        boolean quoted = false;
        int start = -1;
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (!quoted && (Character.isWhitespace(ch) || ch == ',')) {
                if (start >= 0) result.add(new Term(start, i));
                start = -1;
            } else {
                if (start < 0) start = i;
                if (ch == '"') quoted = !quoted;
            }
        }
        if (start >= 0) result.add(new Term(start, query.length()));
        return result;
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

    private record Term(int start, int end) {}
}
