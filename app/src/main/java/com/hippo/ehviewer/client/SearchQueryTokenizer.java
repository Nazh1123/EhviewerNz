package com.hippo.ehviewer.client;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lossless ranges into a search query. Only explicit tag expressions become chips. */
public final class SearchQueryTokenizer {
    private static final Pattern TAG = Pattern.compile(
            "([^-+~\\s:,\"]+):(?:\"([^\"\\r\\n]+)\"(\\$?)|([^\\s,\"]+))");

    private SearchQueryTokenizer() {}

    public record Term(int start, int end) {}

    public record Tag(String namespace, String value, char operator, boolean exact) {
        public String identity() {
            return namespace + ":" + value.toLowerCase(Locale.ROOT);
        }
    }

    public static boolean isSeparator(char ch) {
        return Character.isWhitespace(ch) || ch == ',';
    }

    /** Normalize a typed tag fragment for the local tag database, including unfinished quotes. */
    public static String suggestionKeyword(String keyword) {
        keyword = keyword.trim();
        if (!keyword.isEmpty() && "-+~".indexOf(keyword.charAt(0)) >= 0) keyword = keyword.substring(1);
        int colon = keyword.indexOf(':');
        if (colon >= 0) keyword = canonicalNamespace(keyword.substring(0, colon)) + keyword.substring(colon);
        return keyword.replace("\"", "").replace("$", "").trim();
    }

    public static List<Term> terms(CharSequence query) {
        List<Term> result = new ArrayList<>();
        boolean quoted = false;
        int start = -1;
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (!quoted && isSeparator(ch)) {
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

    public static String canonicalNamespace(String namespace) {
        return switch (namespace.toLowerCase(Locale.ROOT)) {
            case "a", "artist" -> "artist";
            case "c", "char", "character" -> "character";
            case "cos", "cosplayer" -> "cosplayer";
            case "f", "female" -> "female";
            case "g", "circle", "group" -> "group";
            case "l", "lang", "language" -> "language";
            case "loc", "location" -> "location";
            case "m", "male" -> "male";
            case "x", "mixed" -> "mixed";
            case "o", "other" -> "other";
            case "p", "series", "parody" -> "parody";
            case "r", "reclass" -> "reclass";
            default -> namespace.toLowerCase(Locale.ROOT);
        };
    }

    @Nullable
    public static Tag parseTag(String token, boolean allowCustomNamespace) {
        char operator = '+';
        if (!token.isEmpty() && "-+~".indexOf(token.charAt(0)) >= 0) {
            operator = token.charAt(0);
            token = token.substring(1);
        }
        Matcher matcher = TAG.matcher(token);
        if (!matcher.matches()) return null;
        String namespace = canonicalNamespace(matcher.group(1));
        if (!allowCustomNamespace && !isTagNamespace(namespace)) return null;
        String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
        boolean exact = value.endsWith("$") || "$".equals(matcher.group(3));
        if (value.endsWith("$")) value = value.substring(0, value.length() - 1);
        if (value.trim().isEmpty()) return null;
        return new Tag(namespace, value, operator, exact);
    }

    private static boolean isTagNamespace(String namespace) {
        return switch (namespace) {
            case "artist", "character", "cosplayer", "female", "group", "language",
                    "location", "male", "mixed", "other", "parody", "reclass", "tag" -> true;
            default -> false;
        };
    }
}
