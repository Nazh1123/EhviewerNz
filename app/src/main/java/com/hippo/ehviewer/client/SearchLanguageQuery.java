package com.hippo.ehviewer.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchLanguageQuery {

    private SearchLanguageQuery() {
    }

    @NonNull
    public static String token(@NonNull String language) {
        return "l:\"" + language + "$\"";
    }

    @NonNull
    public static String toggle(@Nullable String query, @NonNull String language) {
        String normalizedQuery = query == null ? "" : query.trim();
        String token = token(language);
        String quotedToken = Pattern.quote(token);
        Pattern tokenPattern = Pattern.compile(
                "(?i)(?:" +
                        "(?<![^\\s,])" + quotedToken + "\\s*,\\s*" +
                        "|,\\s*" + quotedToken + "(?=$|[\\s,])" +
                        "|(?<![^\\s,])" + quotedToken + "(?=$|[\\s,])" +
                        ")");
        Matcher matcher = tokenPattern.matcher(normalizedQuery);
        if (matcher.find()) {
            return matcher.replaceAll("").trim().replaceAll("\\s{2,}", " ");
        }
        if (normalizedQuery.isEmpty()) {
            return token;
        }
        String separator = hasCommaSeparator(normalizedQuery) ? ", " : " ";
        return normalizedQuery + separator + token;
    }

    private static boolean hasCommaSeparator(@NonNull String query) {
        boolean quoted = false;
        for (int i = 0; i < query.length(); i++) {
            char character = query.charAt(i);
            if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                return true;
            }
        }
        return false;
    }
}
