package com.hippo.ehviewer.client;

import android.net.Uri;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;

/** Daily viewing periods begin at 00:05 UTC, independently of the device time zone. */
public final class YesterdayTopListReadState {
    public static final long NO_PERIOD = Long.MIN_VALUE;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long RESET_OFFSET_MILLIS = 300_000L;

    private YesterdayTopListReadState() {}

    public static long periodAt(long timeMillis) {
        return Math.floorDiv(timeMillis - RESET_OFFSET_MILLIS, DAY_MILLIS);
    }

    public static long nextPeriodStartAt(long timeMillis) {
        return (periodAt(timeMillis) + 1L) * DAY_MILLIS + RESET_OFFSET_MILLIS;
    }

    public static boolean isYesterdayTopList(ListUrlBuilder builder) {
        return builder != null && builder.getMode() == ListUrlBuilder.MODE_TOP_LIST
                && "15".equals(Uri.parse("?" + builder.getFollow()).getQueryParameter("tl"));
    }

    public static boolean isUnread(long timeMillis) {
        return Settings.getShowYesterdayTopListNewBadge()
                && Settings.getYesterdayTopListReadPeriod() != periodAt(timeMillis);
    }

    /** A response requested before the boundary must not acknowledge the next day's list. */
    public static boolean markRead(long loadedPeriod, long timeMillis) {
        if (loadedPeriod == NO_PERIOD || loadedPeriod != periodAt(timeMillis)) return false;
        Settings.putYesterdayTopListReadPeriod(loadedPeriod);
        return true;
    }
}
