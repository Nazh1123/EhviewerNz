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

    public static boolean retainsSearchCandidates(int mode) {
        return mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_SUBSCRIPTION
                || mode == ListUrlBuilder.MODE_TAG || mode == ListUrlBuilder.MODE_UPLOADER
                || mode == ListUrlBuilder.MODE_FILTER;
    }

    /** The lowest score the server could have returned for this actual request. */
    public static int serverFloor(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return -1;
        // Current website continuation links keep f_srdd but omit the legacy f_sr toggle.
        String minimum = parsed.queryParameter("f_srdd");
        if (minimum == null || minimum.isEmpty() || "0".equals(minimum)) return 0;
        try {
            int option = Integer.parseInt(minimum);
            return option >= 2 && option <= 5 ? option * 100 - 25 : -1;
        } catch (NumberFormatException error) { return -1; }
    }

    /** Keep the website's continuation cursor and every unrelated search condition. */
    public static String withServerMinimum(String url, int option) {
        if (url == null || url.isEmpty()) return url;
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return url;
        HttpUrl.Builder builder = parsed.newBuilder().removeAllQueryParameters("f_sr")
                .removeAllQueryParameters("f_srdd");
        if (option >= 2) builder.setQueryParameter("advsearch", "1")
                .addQueryParameter("f_sr", "on").addQueryParameter("f_srdd", String.valueOf(option));
        return builder.build().toString();
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
