package com.hippo.ehviewer.client;

import com.hippo.ehviewer.client.data.ListUrlBuilder;

import java.math.BigDecimal;
import java.util.Locale;

import okhttp3.HttpUrl;

/** Search thresholds are stored as hundredths, independently of the site's integer filter. */
public final class SearchRating {
    private SearchRating() {}

    public static int parse(String input) {
        String text = input.trim();
        if (!text.matches("(?:[0-5](?:\\.[0-9]{1,2})?|\\.[0-9]{1,2})")) {
            throw new IllegalArgumentException("Invalid rating");
        }
        int value = new BigDecimal(text).movePointRight(2).intValueExact();
        if (value > 500) throw new IllegalArgumentException("Invalid rating");
        return value;
    }

    public static String format(int hundredths) {
        return String.format(Locale.ROOT, "%.2f", hundredths / 100.0);
    }

    public static int serverMinimum(int hundredths) {
        // Empirically, site option N admits scores from N - 0.25.
        // Use the strongest option whose lower bound does not exceed our exact threshold.
        return hundredths >= 175 ? Math.min(5, (hundredths + 25) / 100) : -1;
    }

    public static boolean isClientOnlyMode(int mode) {
        return mode == ListUrlBuilder.MODE_WHATS_HOT
                || mode == ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION
                || mode == ListUrlBuilder.MODE_GLOBAL_SUBSCRIPTION;
    }

    public static String withoutServerFilter(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || parsed.queryParameter("f_sr") == null
                && parsed.queryParameter("f_srdd") == null) return url;
        return parsed.newBuilder().removeAllQueryParameters("f_sr")
                .removeAllQueryParameters("f_srdd").build().toString();
    }

    public static boolean matches(float rating, int hundredths) {
        return hundredths <= 0 || Float.isFinite(rating) && rating >= hundredths / 100f;
    }
}
