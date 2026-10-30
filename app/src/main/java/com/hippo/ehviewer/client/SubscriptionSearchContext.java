package com.hippo.ehviewer.client;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.Filter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import okhttp3.Cookie;
import okhttp3.HttpUrl;

/** Invalidate reuse when account, site or effective local configuration changes. */
public final class SubscriptionSearchContext {
    private SubscriptionSearchContext() {}
    public static String key() {
        List<String> parts = new ArrayList<>();
        parts.add(EhUrl.getHost());
        // Keep existing ordinary-search checkpoint keys compatible with the disabled mode.
        // parts.add("homepage-scan:" + Settings.getSubscriptionHomepageScan());
        parts.add("homepage-scan:false");
        parts.add(searchConfig(Settings.getEhConfig().uconfig()));
        parts.add("default-rating:" + Settings.getPersistentMinRatingHundredths());
        parts.add("precise-rating:" + Settings.getEnablePreciseRatingFilter());
        for (Cookie cookie : EhApplication.getEhCookieStore(EhApplication.getInstance())
                .loadForRequest(HttpUrl.parse(EhUrl.getHost()))) {
            // Session/challenge cookies can rotate in an ordinary search response. Only
            // account identity and search preferences define the reusable result scope.
            String name = cookie.name();
            if (EhCookieStore.KEY_IPD_MEMBER_ID.equals(name)
                    || EhCookieStore.KEY_IPD_PASS_HASH.equals(name)
                    || EhCookieStore.KEY_IGNEOUS.equals(name)
                    || EhConfig.KEY_UCONFIG.equals(name)
                    || EhConfig.KEY_CONTENT_WARNING.equals(name)
                    || "sp".equals(name)) {
                parts.add(name + "=" + (EhConfig.KEY_UCONFIG.equals(name)
                        ? searchConfig(cookie.value()) : cookie.value()));
            }
        }
        EhFilter filters = EhFilter.getInstance();
        synchronized (filters) {
            for (List<Filter> group : Arrays.asList(filters.getTitleFilterList(), filters.getUploaderFilterList(),
                    filters.getTagFilterList(), filters.getTagNamespaceFilterList())) {
                for (Filter filter : group) parts.add(filter.mode + ":" + filter.enable + ":" + filter.text);
            }
        }
        Collections.sort(parts);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                hash.update((byte) ':'); hash.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : hash.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static String searchConfig(String config) {
        List<String> parts = new ArrayList<>();
        for (String part : config.split("-")) {
            if (part.matches("(?:cats|xns|xl)_.*")) parts.add(part);
        }
        Collections.sort(parts);
        return String.join("-", parts);
    }

    public static String cacheKey() {
        return key() + ":display:" + Settings.getEhConfig().uconfig()
                + ":" + Settings.getShowGalleryPages() + ":"
                + (Settings.isThumbnailInfoBarEffective() && Settings.getShowThumbnailInfoDetails());
    }
}
