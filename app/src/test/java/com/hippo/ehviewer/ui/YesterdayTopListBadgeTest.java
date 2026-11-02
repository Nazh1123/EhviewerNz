package com.hippo.ehviewer.ui;

import android.app.Application;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.navigation.NavigationView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.YesterdayTopListReadState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class YesterdayTopListBadgeTest {
    private static final long BOUNDARY = Instant.parse("2026-11-02T00:05:00Z").toEpochMilli();
    private MainActivity activity;
    private NavigationView navigation;
    private TextView badge;
    private long now;

    @Before public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().clear().commit();
        Settings.initialize(RuntimeEnvironment.getApplication());
        now = BOUNDARY - 1000L;
        createBadge();
    }

    private void createBadge() {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        updateClock();
        activity.setTheme(R.style.AppTheme_Main);
        navigation = new NavigationView(activity);
        navigation.inflateMenu(R.menu.nav_drawer_main);
        ReflectionHelpers.setField(activity, "mNavView", navigation);
        badge = ReflectionHelpers.callInstanceMethod(activity, "initNavigationBadge",
                ReflectionHelpers.ClassParameter.from(int.class, R.id.nav_top_lists));
        ReflectionHelpers.setField(activity, "mYesterdayTopListBadge", badge);
        render();
    }

    private void render() {
        ReflectionHelpers.callInstanceMethod(activity, "renderYesterdayTopListBadge");
    }

    private void updateClock() {
        ReflectionHelpers.setField(activity, "mYesterdayTopListClock",
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }

    @Test public void onlyTheRankingRowShowsNewAndItClearsAfterAVisitAcrossRecreation() {
        assertEquals("New!", badge.getText().toString());
        assertEquals(View.VISIBLE, badge.getVisibility());
        assertNull(navigation.getMenu().findItem(R.id.nav_whats_hot).getActionView());
        activity.onYesterdayTopListRead(YesterdayTopListReadState.periodAt(now));
        assertEquals(View.GONE, badge.getVisibility());
        createBadge();
        assertEquals(View.GONE, badge.getVisibility());
    }

    @Test public void foregroundReminderReturnsAtTheBoundaryWithoutReopeningTheDrawer() {
        activity.onYesterdayTopListRead(YesterdayTopListReadState.periodAt(now));
        ReflectionHelpers.setField(activity, "mYesterdayTopListBadgeRunning", true);
        render();
        now += 999;
        updateClock();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999));
        assertEquals(View.GONE, badge.getVisibility());
        now++;
        updateClock();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals(View.VISIBLE, badge.getVisibility());
        assertEquals("New!", badge.getText().toString());
    }

    @Test public void settingTogglesBadgeWithoutDisablingTheRankingRow() {
        Settings.putBoolean(Settings.KEY_SHOW_YESTERDAY_TOP_LIST_NEW_BADGE, false);
        render();
        assertEquals(View.GONE, badge.getVisibility());
        MenuItem ranking = navigation.getMenu().findItem(R.id.nav_top_lists);
        assertTrue(ranking.isVisible());
        assertTrue(ranking.isEnabled());
        Settings.putBoolean(Settings.KEY_SHOW_YESTERDAY_TOP_LIST_NEW_BADGE, true);
        render();
        assertEquals(View.VISIBLE, badge.getVisibility());
    }
}
