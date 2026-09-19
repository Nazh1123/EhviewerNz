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
        parts.add(Settings.getEhConfig().uconfig());
        parts.add("gallery-pages:" + Settings.getShowGalleryPages());
        parts.add("thumbnail-info:" + Settings.isThumbnailInfoBarEffective());
        for (Cookie cookie : EhApplication.getEhCookieStore(EhApplication.getInstance())
                .loadForRequest(HttpUrl.parse(EhUrl.getHost()))) {
            parts.add(cookie.name() + "=" + cookie.value());
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
}
