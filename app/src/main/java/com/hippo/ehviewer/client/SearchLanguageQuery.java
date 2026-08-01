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
        Pattern tokenPattern = Pattern.compile(
                "(?i)(?<!\\S)" + Pattern.quote(token) + "(?!\\S)");
        Matcher matcher = tokenPattern.matcher(normalizedQuery);
        if (matcher.find()) {
            return matcher.replaceAll("").trim().replaceAll("\\s{2,}", " ");
        }
        return normalizedQuery.isEmpty() ? token : normalizedQuery + " " + token;
    }
}
