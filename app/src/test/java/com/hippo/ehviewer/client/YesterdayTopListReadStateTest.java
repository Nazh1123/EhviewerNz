package com.hippo.ehviewer.client;

import android.app.Application;
import android.preference.PreferenceManager;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.util.TimeZone;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class YesterdayTopListReadStateTest {
    private static final long BOUNDARY = Instant.parse("2026-11-02T00:05:00Z").toEpochMilli();

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
    }

    @Test public void resetIsExactlyAtFiveMinutesPastUtcMidnight() {
        long previous = YesterdayTopListReadState.periodAt(BOUNDARY - 1);
        assertEquals(previous, YesterdayTopListReadState.periodAt(BOUNDARY - 300_000));
        assertEquals(previous + 1, YesterdayTopListReadState.periodAt(BOUNDARY));
        assertEquals(BOUNDARY, YesterdayTopListReadState.nextPeriodStartAt(BOUNDARY - 1));
        assertEquals(BOUNDARY + 86_400_000L,
                YesterdayTopListReadState.nextPeriodStartAt(BOUNDARY));
    }

    @Test public void deviceTimeZoneAndDstDoNotChangeThePeriod() {
        TimeZone original = TimeZone.getDefault();
        try {
            long expected = YesterdayTopListReadState.periodAt(BOUNDARY);
            for (String zone : new String[]{"Asia/Shanghai", "America/New_York", "Pacific/Honolulu"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(expected, YesterdayTopListReadState.periodAt(BOUNDARY));
                assertEquals(BOUNDARY, YesterdayTopListReadState.nextPeriodStartAt(BOUNDARY - 1));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test public void viewedStateSurvivesSettingsReloadAndExpiresAtTheBoundary() {
        assertTrue(Settings.getShowYesterdayTopListNewBadge());
        assertTrue(YesterdayTopListReadState.isUnread(BOUNDARY - 1));
        assertTrue(YesterdayTopListReadState.markRead(
                YesterdayTopListReadState.periodAt(BOUNDARY - 1), BOUNDARY - 1));
        Settings.initialize(RuntimeEnvironment.getApplication());
        assertFalse(YesterdayTopListReadState.isUnread(BOUNDARY - 1));
        assertTrue(YesterdayTopListReadState.isUnread(BOUNDARY));
    }

    @Test public void responseOrCachedPageFromBeforeTheResetCannotClearTheNewReminder() {
        long previous = YesterdayTopListReadState.periodAt(BOUNDARY - 1);
        assertFalse(YesterdayTopListReadState.markRead(previous, BOUNDARY));
        assertTrue(YesterdayTopListReadState.isUnread(BOUNDARY));
        long current = YesterdayTopListReadState.periodAt(BOUNDARY);
        assertTrue(YesterdayTopListReadState.markRead(current, BOUNDARY));
        assertFalse(YesterdayTopListReadState.markRead(previous, BOUNDARY + 1));
        assertEquals(current, Settings.getYesterdayTopListReadPeriod());
    }

    @Test public void disablingHidesTheReminderWithoutErasingViewingHistory() {
        Settings.putBoolean(Settings.KEY_SHOW_YESTERDAY_TOP_LIST_NEW_BADGE, false);
        assertFalse(YesterdayTopListReadState.isUnread(BOUNDARY));
        Settings.putBoolean(Settings.KEY_SHOW_YESTERDAY_TOP_LIST_NEW_BADGE, true);
        assertTrue(YesterdayTopListReadState.isUnread(BOUNDARY));
        Settings.putBoolean(Settings.KEY_SHOW_YESTERDAY_TOP_LIST_NEW_BADGE, false);
        YesterdayTopListReadState.markRead(YesterdayTopListReadState.periodAt(BOUNDARY), BOUNDARY);
        Settings.putBoolean(Settings.KEY_SHOW_YESTERDAY_TOP_LIST_NEW_BADGE, true);
        assertFalse(YesterdayTopListReadState.isUnread(BOUNDARY));
    }

    @Test public void overviewOtherRankingsAndMissingResponsesAreNotYesterdayVisits() {
        assertFalse(YesterdayTopListReadState.markRead(YesterdayTopListReadState.NO_PERIOD, BOUNDARY));
        assertTrue(YesterdayTopListReadState.isUnread(BOUNDARY));
        assertFalse(YesterdayTopListReadState.isYesterdayTopList(null));
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setFollow("tl=15");
        assertFalse(YesterdayTopListReadState.isYesterdayTopList(builder));
        builder.setMode(ListUrlBuilder.MODE_TOP_LIST);
        assertTrue(YesterdayTopListReadState.isYesterdayTopList(builder));
        builder.setFollow("tl=13");
        assertFalse(YesterdayTopListReadState.isYesterdayTopList(builder));
    }
}
